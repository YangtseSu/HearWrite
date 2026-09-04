package org.yangtse.hearwrite.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 虚词/助词字读 "X的X" 很别扭，直接读单字（upstream set, AGENTS.md）。 */
private val NO_COMPOUND_HEADS = setOf(
    "的", "地", "得", "着", "了", "吗", "呢", "吧", "啊", "呀", "啦", "嘛", "么",
)

/**
 * 带调拼音字母 → 无调字母 + 调号数字："yuè" → "yue4"；带调 ü → v（与常用词表一致）。
 */
private val TONE_DIGIT: Map<Char, String> = mapOf(
    'ā' to "a1", 'á' to "a2", 'ǎ' to "a3", 'à' to "a4",
    'ē' to "e1", 'é' to "e2", 'ě' to "e3", 'è' to "e4",
    'ī' to "i1", 'í' to "i2", 'ǐ' to "i3", 'ì' to "i4",
    'ō' to "o1", 'ó' to "o2", 'ǒ' to "o3", 'ò' to "o4",
    'ū' to "u1", 'ú' to "u2", 'ǔ' to "u3", 'ù' to "u4",
    'ǖ' to "v1", 'ǘ' to "v2", 'ǚ' to "v3", 'ǜ' to "v4",
)

/** 带调拼音转数字调形式（无声调输入原样返回）。 */
private fun toneToDigit(pinyin: String): String {
    var tone = ""
    val out = StringBuilder(pinyin.length + 1)
    for (ch in pinyin.trim().lowercase()) {
        val t = TONE_DIGIT[ch]
        if (t == null) out.append(ch) else {
            out.append(t[0])
            tone = t.substring(1)
        }
    }
    return out.append(tone).toString()
}

/**
 * 组词候选的读音是否可用：条目带调拼音与词中该字音节（数字调）须一致；
 * 任一方无调（轻声或未标注）时放行（如 "头" tóu vs "石头" shí·tou）。
 */
private fun syllableMatches(headPinyin: String, syllable: String): Boolean {
    if (headPinyin.isEmpty() || syllable.isEmpty()) return true
    if (headPinyin == syllable) return true
    return !headPinyin.last().isDigit() || !syllable.last().isDigit()
}

/** One 组词 candidate row: the word and the head char's syllable inside it (tone digits, ü = v, light tone unmarked). */
data class CompoundWord(val word: String, val syllable: String)

/**
 * 单字组词候选表（`app/src/main/assets/compounds/compounds.json` 的内存形态）:
 * [common] 按《现代汉语常用词表》频级升序（越靠前越常用），[learned] 为教材已学词。
 * Pool order is significant: candidates are walked row by row and duplicates
 * are preserved — one char can carry two readings of the same word
 * (澄: 澄清 cheng2/deng4, 朝: 朝阳 zhao1/chao2), and deduping by word would
 * orphan the second reading (AGENTS.md ⚠️ — never dedupe candidates).
 */
class CompoundTables(
    val common: Map<String, List<CompoundWord>>,
    val learned: Map<String, List<CompoundWord>>,
) {
    companion object {
        /** No candidate tables — dictation degrades to the bare char. */
        val EMPTY = CompoundTables(emptyMap(), emptyMap())
    }
}

/** Parse the shipped `compounds.json` text (`{compounds, learned}` pools). */
fun parseCompoundTables(json: String): CompoundTables {
    fun pool(key: String): Map<String, List<CompoundWord>> {
        val obj = Json.parseToJsonElement(json).jsonObject[key]?.jsonObject ?: return emptyMap()
        return obj.mapValues { (_, rows) ->
            rows.jsonArray.map { row ->
                val pair = row.jsonArray
                CompoundWord(pair[0].jsonPrimitive.content, pair[1].jsonPrimitive.content)
            }
        }
    }
    return CompoundTables(pool("compounds"), pool("learned"))
}

/**
 * 中文单字的组词朗读文本（传统听写模式）："月 | yuè | 月亮" → "月亮的月"。
 *
 * - 仅单字 CJK 条目有效；词语/短句与非中文条目返回 ""（词语按原样朗读两次）；
 * - 虚词/助词（[NO_COMPOUND_HEADS]）不组词，返回 ""（单字照读）。
 *
 * Candidate tiers, first (frequency-)best match wins:
 *  1. 条目释义列：按「；」再「，,、」拆分、去括注与边缘标点，保留含该字的二字词，
 *     不按读音过滤（课本释义权威）；
 *  2. 已学词池：[learnedWords]（当前词表其他含该字的二字词，按出现顺序、不过滤）
 *     + [tables.learned]（按读音过滤），整体按常用词表频级排序，未收录词按出现顺序排后；
 *  3. [tables.common] 常用词池兜底（按读音过滤，频级升序取第一个通过的）。
 *
 * 多音字按条目带调拼音过滤：音节须一致（数字调，ü = v），任一方无调放行。
 * ⚠️ Candidates are never deduped by word — the raw pool arrays are walked and
 * the first row passing the reading filter wins (upstream deduped 澄清 keeping
 * only cheng2, orphaning the dèng reading entirely; 朝 pool carries 朝阳 zhao1
 * and 朝阳 chao2 side by side).
 */
fun cjkWordSpeech(
    entry: String,
    tables: CompoundTables,
    learnedWords: List<String> = emptyList(),
): String {
    val head = speakTextFromEntry(entry)
    if (head.length != 1 || !CJK_RE.containsMatchIn(head)) return ""
    if (head in NO_COMPOUND_HEADS) return ""

    val parsed = parseWordLine(entry)
    val headPinyin = parsed.pos?.let(::toneToDigit) ?: ""

    // Tier 1: the entry's own meaning column (authoritative, no reading filter).
    val fromMeaning = mutableListOf<CompoundWord>()
    for (raw in (parsed.meaning ?: "").split(SENSE_SPLIT_RE)) {
        for (chunk in raw.split(GLOSS_SPLIT_RE)) {
            val word = chunk.replace(MEANING_PAREN_RE, "")
                .replace(MEANING_EDGE_PUNCT_RE, "")
            if (word.length != 2 || word == head || !word.contains(head)) continue
            fromMeaning += CompoundWord(word, "")
        }
    }

    // Tier 2: 已学词 = the current list's other words + the learned pool.
    val fromLearned = mutableListOf<CompoundWord>()
    for (line in learnedWords) {
        val word = speakTextFromEntry(line)
        if (word.length != 2 || word == head || !word.contains(head)) continue
        fromLearned += CompoundWord(word, "")
    }
    for (row in tables.learned[head] ?: emptyList()) {
        if (syllableMatches(headPinyin, row.syllable)) fromLearned += row
    }

    // Tier 3: the common-word pool, reading-filtered, frequency-ordered.
    val fromCommon = tables.common[head]
        ?.filter { syllableMatches(headPinyin, it.syllable) }
        ?: emptyList()

    // Tiers 1+2 merge into one pool ranked by the common-word frequency table;
    // unranked words keep their appearance order (meaning, then list, then
    // learned rows). Stable first-lowest-rank pick ≡ upstream sort + first.
    val rank = HashMap<String, Int>(fromCommon.size)
    fromCommon.forEachIndexed { i, row -> rank[row.word] = i }
    var best: CompoundWord? = null
    var bestRank = Int.MAX_VALUE
    for (row in fromMeaning.asSequence() + fromLearned.asSequence()) {
        val r = rank[row.word] ?: rank.size
        if (r < bestRank) {
            bestRank = r
            best = row
        }
    }
    if (best != null) return "${best.word}的$head"
    val fallback = fromCommon.firstOrNull() ?: return ""
    return "${fallback.word}的$head"
}
