package org.yangtse.hearwrite.domain

/**
 * How a [ResolvedWord] becomes text on a display surface
 * (`docs/2026-09-18-DATA-MODEL.md` §4.1/§4.2) — pure, so every surface stitches
 * the same segments instead of re-deriving them.
 *
 * The 音标 is never a placeholder: an accent the lexicon does not carry simply
 * leaves its segment out. Two transcription rules, both from §4.2:
 * - the dial shows **美式** alone — the accent the TTS chain speaks;
 * - the lists and the 详情卡 label every accent the lexicon carries, **英 first**
 *   (the order the 仁爱 textbook prints them).
 */

/** `/let/` — one bracketed transcription; null when that accent is blank. */
private fun transcription(ipa: String?): String? =
    ipa?.trim()?.takeIf { it.isNotEmpty() }?.let { "/$it/" }

/** `英 /let/ · 美 /let/` — only the accents present, 英 first; null when neither. */
fun ipaLabels(ipa: Ipa?): String? = listOfNotNull(
    transcription(ipa?.uk)?.let { "英 $it" },
    transcription(ipa?.us)?.let { "美 $it" },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** The dial's own transcription: **美式**, the accent the playback chain speaks. */
fun dialIpa(ipa: Ipa?): String? = transcription(ipa?.us)

/** The 词性 the hint line leads with — the entry's first sense. */
internal val ResolvedWord.mainPos: String? get() = senses.firstOrNull()?.pos

/**
 * 释义/组词 text of a row: a 汉字 row reads its resolved 组词, an English row
 * its senses joined with `；` — a later sense's 词性 spelled inline when it
 * differs from the first, exactly the shape the old flat asset stored
 * (`fine | adj. | 身体好的，健康的；v. 对……处以罚款`).
 */
fun ResolvedWord.glossText(): String? = when (kind) {
    WordKind.HANZI -> compound
    WordKind.WORD -> null
    WordKind.EN -> {
        val main = mainPos
        senses.joinToString("；") { sense ->
            if (sense.pos != null && sense.pos != main) "${sense.pos} ${sense.gloss}" else sense.gloss
        }.ifEmpty { null }
    }
}

/**
 * The dial's single hint line. An English row composes its 美式 音标 **and** its
 * 词性 into one line (`/ˈæpəl/ n.`) — a 音标 line of its own would push a
 * two-line word past what a 204 dp disc affords (§4.1). A single 汉字 shows its
 * 拼音 (Chinese rows carry no 音标 by design, §9); a 词语 shows nothing.
 */
fun ResolvedWord.dialHint(): String? = when (kind) {
    WordKind.HANZI -> pinyin
    WordKind.WORD -> null
    WordKind.EN -> listOfNotNull(dialIpa(ipa), mainPos)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" ")
}

/**
 * A list row's meta line: `英 /let/ · 美 /let/ · v. · 让，允许` for an English
 * row, `yuè 月亮` for a 汉字 one (拼音 and 组词 are the row's whole story
 * there — Chinese rows never gain an 音标 segment). Null when the row carries
 * nothing to hint at, which is when the caller shows its placeholder.
 */
fun ResolvedWord.listMeta(): String? = when (kind) {
    WordKind.HANZI -> listOfNotNull(pinyin, compound)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" ")
    WordKind.WORD -> null
    WordKind.EN -> listOfNotNull(ipaLabels(ipa), mainPos, glossText())
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
}

/**
 * The first resolved row whose speakable headword is [word], or null. The
 * 错词本 keys on exactly that headword (AGENTS.md "Persistence"), so this is
 * how a marked word is matched back to its line (Roadmap #7): the row keeps
 * its 词性/释义 or 拼音/组词 (and its 音标), and an expansion row (`you're =
 * you are`) matches the left side the book stored.
 */
fun findResolvedByHeadword(rows: List<ResolvedWord>, word: String): ResolvedWord? =
    rows.firstOrNull { it.speak == word }
