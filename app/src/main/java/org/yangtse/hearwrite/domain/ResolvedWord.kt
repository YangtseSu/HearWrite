package org.yangtse.hearwrite.domain

/**
 * One meaning of a lexicon entry: the 词性 plus its gloss
 * (`docs/2026-09-18-DATA-MODEL.md` §1.2). A structured list rather than the
 * old flat `"pos|gloss"` string, whose internal `；` had to be lossily folded
 * to `，` to survive the round-trip through a word-list line.
 */
data class Sense(val pos: String?, val gloss: String)

/**
 * IPA of one headword, `us` / `uk` — either side nullable, because a source
 * may print only one (the 仁爱 textbook prints a single symbol when both
 * accents read the same). Not part of a word-list row: a reading belongs to
 * the word, not to the list that happens to contain it.
 */
data class Ipa(val us: String?, val uk: String?)

/**
 * 行覆盖 + 词典回填 = the shape the dial and the playback engine consume
 * (`docs/2026-09-18-DATA-MODEL.md` §1.3): a pure read-time result, never
 * persisted back into a row. The row wins field by field — its `pos`/`gloss`
 * (词性/释义 or 拼音/组词, whichever the list's kind carries) else the
 * lexicon's senses; IPA only ever comes from the lexicon.
 */
data class ResolvedWord(
    val display: String,
    val speak: String,
    val kind: WordKind,
    val senses: List<Sense>,
    val pinyin: String?,
    val compound: String?,
    val ipa: Ipa?,
)
