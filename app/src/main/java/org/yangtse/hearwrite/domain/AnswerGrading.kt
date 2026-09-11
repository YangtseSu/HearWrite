package org.yangtse.hearwrite.domain

/**
 * 拍照批改 (Roadmap #11): compare a photographed, OCR'd answer sheet against
 * the run's own word list and judge every answer. Pure Kotlin — the vision
 * call lives in `data/OcrService.kt`, the review/confirm surface in the UI.
 *
 * The machine's verdicts are a **proposal**, never a final grade: every
 * non-CORRECT item is flagged for the human to check before it reaches the
 * 错词本 (AGENTS.md — AI 识图可能存在误差).
 */
enum class AnswerVerdict {
    /** The answer matches the dictated word. */
    CORRECT,

    /** The student wrote something else for that word. */
    WRONG,

    /** No answer for that word (left blank — or the model could not read it). */
    MISSING,

    /** An extra line with no word to sit against (stray text, or a misread line). */
    EXTRA,
}

/**
 * One judged line of a 拍照批改 pass. [slot] is the 1-based answer number in
 * the run's order (the dictated order — 随机顺序 is baked into the staged
 * lines), or 0 for an [AnswerVerdict.EXTRA] line that has no slot.
 */
data class GradedAnswer(
    val slot: Int,
    /** Speakable headword of the run's line — the 错词本 key; null for EXTRA. */
    val expected: String?,
    /** What the student wrote, without its 题号; null when nothing was read. */
    val answer: String?,
    val verdict: AnswerVerdict,
    /**
     * True when the machine cannot be sure and the human should look at the
     * paper: a near-miss spelling (OCR may have misread a letter), an empty
     * answer (blank — or unreadable), an unassignable extra line.
     */
    val doubt: Boolean,
    /** The answer matched a different position of the list (行序错位). */
    val outOfOrder: Boolean = false,
)

/** The result of one 拍照批改 pass over the run's lines. */
data class GradeResult(
    val items: List<GradedAnswer>,
    val expectedTotal: Int,
) {
    val correctCount: Int get() = count(AnswerVerdict.CORRECT)
    val wrongCount: Int get() = count(AnswerVerdict.WRONG)
    val missingCount: Int get() = count(AnswerVerdict.MISSING)
    val extraCount: Int get() = count(AnswerVerdict.EXTRA)

    /** Items needing the human's eyes (every non-CORRECT one carries a badge). */
    val doubtCount: Int get() = items.count { it.doubt }

    private fun count(verdict: AnswerVerdict): Int = items.count { it.verdict == verdict }

    /**
     * Answers that should reach the 错词本 by default: the misspelled ones
     * and the blanks. Extras have no word to book; the human may still tick a
     * CORRECT item (or untick a wrong one) before confirming.
     */
    fun defaultSelection(): Set<Int> = items.indices
        .filter {
            val verdict = items[it].verdict
            verdict == AnswerVerdict.WRONG || verdict == AnswerVerdict.MISSING
        }
        .toSet()

    /**
     * True when the paper barely overlaps this run's list — the photo may be
     * of a different page (or nearly everything was answered wrong). Not a
     * block: the grading is still shown, with a warning to re-check/retake.
     */
    val mismatched: Boolean get() {
        if (expectedTotal < 3) return false
        // Nothing was read at all — the sheet is blank/unreadable, not "a
        // different page".
        if (items.none { it.answer != null && it.verdict != AnswerVerdict.EXTRA }) return false
        val evidence = items.count {
            it.verdict == AnswerVerdict.CORRECT ||
                (it.verdict == AnswerVerdict.WRONG && it.doubt)
        }
        return evidence * 3 < expectedTotal
    }

    companion object {
        /** No answers were read at all: every slot is MISSING. */
        fun allMissing(runLines: List<String>): GradeResult {
            val expected = runLines.mapNotNull(::expectedAnswer)
            return GradeResult(
                items = expected.mapIndexed { index, answer ->
                    GradedAnswer(
                        slot = index + 1,
                        expected = answer.headword,
                        answer = null,
                        verdict = AnswerVerdict.MISSING,
                        doubt = true,
                    )
                },
                expectedTotal = expected.size,
            )
        }
    }
}

/** 1-based 题号 prefix of a handwritten answer (`1.` `2、` `（3）` `1）` `1 月`). */
private val ANSWER_NUMBER_RE = Regex("""^[\s（(\[]*(\d{1,3})\s*[.、)）:：。\s]\s*""")

/** 全角 ASCII (U+FF01–FF5E) and ideographic space → halfwidth. */
private fun toHalfwidth(s: String): String = buildString(s.length) {
    for (ch in s) {
        when {
            ch.code == 0x3000 -> append(' ')
            ch.code in 0xFF01..0xFF5E -> append((ch.code - 0xFEE0).toChar())
            else -> append(ch)
        }
    }
}

/** Every apostrophe/quote variant a keyboard or OCR may emit. */
private val APOSTROPHE_RE = Regex("[\u2018\u2019\u02BC\u00B4`\u2032]")

/**
 * Punctuation that never changes whether a dictated answer is right.
 * Apostrophe and hyphen are **kept** — `don't`/`its` and `ice-cream` are
 * spellings, not formatting; a difference there lands in the near-miss pass
 * instead of being silently accepted.
 */
private val ANSWER_PUNCT_RE = Regex(
    """[.,;:!?"()\[\]{}<>~@#$%^&*_+=|\\/。，、；：！？…—－·「」『』《》〈〉（）【】〔〕“”‘’]""",
)

private val SPACE_RE = Regex("\\s+")

/** Runs of 汉字 — the English/pinyin that a 汉字 answer may carry alongside. */
private val CJK_RUN_RE = Regex("[\\u4e00-\\u9fff]+")
private val CJK_CHAR_RE = Regex("[\\u4e00-\\u9fff]")

/**
 * Equality key of one answer: case, spacing, character width and punctuation
 * folded. Lowercasing is a no-op for Chinese, so one key serves both
 * languages and a run never needs to pick a normalization.
 */
internal fun normalizeAnswer(raw: String): String =
    toHalfwidth(raw).lowercase()
        .replace(APOSTROPHE_RE, "'")
        .replace(SPACE_RE, "")
        .replace(ANSWER_PUNCT_RE, "")

/**
 * Every key an answer may be recognized by. [normalizeAnswer] plus, when the
 * answer carries 汉字, the 汉字-only form: a 汉字听写 answer cannot
 * legitimately contain Latin, so a pinyin annotation the vision model echoed
 * (`月(yuè)` → `月`) must not turn a correct answer into a wrong one. The
 * reverse is deliberately **not** done — stripping 汉字 off an English answer
 * would let a stray annotation pass as the word.
 */
internal fun answerKeys(raw: String): Set<String> {
    val plain = normalizeAnswer(raw)
    val keys = linkedSetOf(plain)
    if (CJK_CHAR_RE.containsMatchIn(plain)) {
        val hanzi = CJK_RUN_RE.findAll(plain).joinToString("") { it.value }
        if (hanzi.isNotEmpty()) keys += hanzi
    }
    return keys.filterTo(mutableSetOf()) { it.isNotEmpty() }
}

/**
 * Split an answer line into its 题号 and the answer text. The number is only
 * a hint — [gradeAnswers] uses it when it is a plausible, unique slot of this
 * run, and ignores it otherwise.
 */
internal fun stripAnswerNumber(raw: String): Pair<Int?, String> {
    val match = ANSWER_NUMBER_RE.find(raw) ?: return null to jsEdgeTrim(raw)
    val index = match.groupValues[1].toIntOrNull()
    return index to jsEdgeTrim(raw.substring(match.range.last + 1))
}

/**
 * One expected answer of a run: the headword (the 错词本 key) plus every
 * normalized form the student may legitimately have written. An expansion
 * line `you're = you are` speaks only `you're` but accepts both sides.
 */
private data class Expected(
    val headword: String,
    val forms: Set<String>,
)

private fun expectedAnswer(line: String): Expected? {
    val headword = speakTextFromEntry(line)
    if (headword.isEmpty()) return null
    val forms = linkedSetOf(normalizeAnswer(headword))
    // `A = B` (the word column, pos/meaning columns already separate): the app
    // dictates A, the student may write either spelling.
    val wordColumn = parseWordLine(line).word
    val eq = wordColumn.indexOfFirst { it == '=' || it == '＝' }
    if (eq != -1) {
        val right = normalizeAnswer(jsEdgeTrim(wordColumn.substring(eq + 1)))
        if (right.isNotEmpty()) forms += right
    }
    return Expected(headword, forms.filterTo(mutableSetOf()) { it.isNotEmpty() })
}

/**
 * True when the run is a 汉字/词语 dictation (a strict majority of its
 * speakable headwords are Chinese) — picks the vision prompt of a 拍照批改
 * pass. A tie resolves to English; only a truly mixed list is affected and
 * neither language's extractor could read that paper anyway.
 */
fun isCjkRun(lines: List<String>): Boolean {
    var cjk = 0
    var other = 0
    for (line in lines) {
        if (speakTextFromEntry(line).isEmpty()) continue
        if (isCjkEntry(line)) cjk++ else other++
    }
    return cjk > other
}

/** Levenshtein distance over two already-normalized strings. */
internal fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val substitution = prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(substitution, prev[j] + 1, curr[j - 1] + 1)
        }
        val swap = prev
        prev = curr
        curr = swap
    }
    return prev[b.length]
}

/** How far a spelled answer may drift and still count as "probably this word". */
private fun nearMissThreshold(length: Int): Int = (length / 4).coerceIn(1, 3)

/**
 * A near miss: one or two characters off — a real spelling error, or the
 * model misreading a handwritten letter. Either way the human decides, so the
 * item is flagged 存疑 instead of being silently accepted or rejected.
 */
internal fun isNearMiss(expected: String, answer: String): Boolean {
    if (expected.isEmpty()) return false
    val distance = editDistance(expected, answer)
    return distance in 1..nearMissThreshold(expected.length)
}

private const val SCORE_MATCH = 3
private const val SCORE_NEAR = 1
private const val SCORE_MISMATCH = -1
private const val SCORE_GAP = 1

private fun pairScore(expected: Expected, answer: Set<String>): Int = when {
    answer.any { it in expected.forms } -> SCORE_MATCH
    answer.any { key -> expected.forms.any { isNearMiss(it, key) } } -> SCORE_NEAR
    else -> SCORE_MISMATCH
}

/**
 * Judge one answer sheet against the run's lines.
 *
 * Placement, in order:
 * 1. **题号** — an answer carrying a plausible, unique 题号 of this run is
 *    placed at that slot outright. This is what makes a skipped word work:
 *    with `1. 2. 4.` on the paper, slot 3 is a blank instead of everything
 *    after it shifting by one.
 * 2. **Sequence alignment** — the remaining answers align against the
 *    remaining slots (Needleman–Wunsch; mismatches still pair up, so a wrong
 *    answer is reported against its word rather than as missing + extra).
 * 3. **Recovery** — an unplaced answer that exactly equals a still-empty
 *    slot's word is assigned there and marked [GradedAnswer.outOfOrder]
 *    (tolerates answers written out of order).
 *
 * Whatever is still unmatched is MISSING (no answer) or EXTRA (a line with no
 * word to sit against).
 */
fun gradeAnswers(runLines: List<String>, recognized: List<String>): GradeResult {
    val expected = runLines.mapNotNull(::expectedAnswer)
    if (expected.isEmpty()) return GradeResult(emptyList(), 0)
    if (recognized.isEmpty()) return GradeResult.allMissing(runLines)

    val slots = arrayOfNulls<GradedAnswer>(expected.size)
    val pool = mutableListOf<String>()
    // A 题号 with nothing after it is a blank row, not an answer.
    val entries = recognized.map { raw ->
        val (number, text) = stripAnswerNumber(raw)
        if (text.isEmpty()) null else number to text
    }
    val numbered = entries.mapNotNull { it?.first }.groupingBy { it }.eachCount()

    entries.forEach { entry ->
        val (number, text) = entry ?: return@forEach
        val slot = number?.takeIf { it in 1..expected.size && numbered[it] == 1 }?.minus(1)
        if (slot != null && slots[slot] == null) slots[slot] = graded(slot, expected[slot], text)
        else pool += text
    }

    // Align the unplaced answers against the still-empty slots.
    val free = slots.indices.filter { slots[it] == null }
    val used = BooleanArray(pool.size)
    alignPairs(free.map { expected[it] }, pool.map(::answerKeys)).forEach { (slotIndex, answerIndex) ->
        slots[free[slotIndex]] = graded(free[slotIndex], expected[free[slotIndex]], pool[answerIndex])
        used[answerIndex] = true
    }

    // Recovery: an answer written out of order (or one the alignment had to
    // drop) that exactly matches a still-empty slot belongs there.
    pool.forEachIndexed { answerIndex, text ->
        if (used[answerIndex]) return@forEachIndexed
        val keys = answerKeys(text)
        val target = slots.indices.firstOrNull { slots[it] == null && keys.any { key -> key in expected[it].forms } }
        if (target != null) {
            slots[target] = graded(target, expected[target], text).copy(outOfOrder = true)
            used[answerIndex] = true
        }
    }

    val items = slots.mapIndexedNotNull { slot, answer ->
        answer ?: GradedAnswer(
            slot = slot + 1,
            expected = expected[slot].headword,
            answer = null,
            verdict = AnswerVerdict.MISSING,
            doubt = true,
        )
    } + pool.filterIndexed { index, _ -> !used[index] }.map { text ->
        GradedAnswer(
            slot = 0,
            expected = null,
            answer = text,
            verdict = AnswerVerdict.EXTRA,
            doubt = true,
        )
    }

    return GradeResult(items = items, expectedTotal = expected.size)
}

/** Verdict of one answer placed against a known slot. */
private fun graded(slot: Int, expected: Expected, answer: String): GradedAnswer {
    val keys = answerKeys(answer)
    val exact = keys.any { it in expected.forms }
    return GradedAnswer(
        slot = slot + 1,
        expected = expected.headword,
        answer = answer,
        verdict = if (exact) AnswerVerdict.CORRECT else AnswerVerdict.WRONG,
        doubt = !exact && keys.any { key -> expected.forms.any { isNearMiss(it, key) } },
    )
}

/**
 * Optimal alignment of [expected] against [answers] (both normalized), as
 * (expected index, answer index) pairs in order. Mismatches still pair up —
 * SCORE_MISMATCH beats dropping both sides — so a wrong answer is reported
 * against its word.
 */
private fun alignPairs(expected: List<Expected>, answers: List<Set<String>>): List<Pair<Int, Int>> {
    val n = expected.size
    val m = answers.size
    if (n == 0 || m == 0) return emptyList()
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in 1..n) dp[i][0] = dp[i - 1][0] - SCORE_GAP
    for (j in 1..m) dp[0][j] = dp[0][j - 1] - SCORE_GAP
    for (i in 1..n) {
        for (j in 1..m) {
            dp[i][j] = maxOf(
                dp[i - 1][j - 1] + pairScore(expected[i - 1], answers[j - 1]),
                dp[i - 1][j] - SCORE_GAP,
                dp[i][j - 1] - SCORE_GAP,
            )
        }
    }
    val pairs = mutableListOf<Pair<Int, Int>>()
    var i = n
    var j = m
    while (i > 0 && j > 0) {
        val diagonal = dp[i - 1][j - 1] + pairScore(expected[i - 1], answers[j - 1])
        when {
            dp[i][j] == diagonal -> {
                pairs += (i - 1) to (j - 1)
                i--
                j--
            }

            dp[i][j] == dp[i - 1][j] - SCORE_GAP -> i--
            else -> j--
        }
    }
    return pairs.asReversed()
}
