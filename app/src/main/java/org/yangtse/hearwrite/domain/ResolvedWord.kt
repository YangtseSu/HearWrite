package org.yangtse.hearwrite.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * One meaning of a lexicon entry: the 词性 plus its gloss
 * (`docs/2026-09-18-DATA-MODEL.md` §1.2). A structured list rather than the
 * old flat `"pos|gloss"` string, whose internal `；` had to be lossily folded
 * to `，` to survive the round-trip through a word-list line.
 *
 * The `p`/`g` serial names are the asset's wire format: the lexicon decodes
 * straight into these types instead of through a parallel DTO graph — the
 * table is 53k entries, so a second graph pays for itself twice in the
 * transient heap of the first lookup (measured on device: a ~96 MB peak
 * while the retained table is ~23 MB).
 */
@Serializable
data class Sense(
    @SerialName("p") val pos: String? = null,
    @SerialName("g") val gloss: String,
)

/**
 * IPA of one headword, `us` / `uk` — either side nullable, because a source
 * may print only one (the 仁爱 textbook prints a single symbol when both
 * accents read the same). Not part of a word-list row: a reading belongs to
 * the word, not to the list that happens to contain it.
 *
 * On the wire it is the two-element array `[us, uk]`; an entry with no
 * transcription omits the field entirely.
 */
@Serializable(with = IpaSerializer::class)
data class Ipa(val us: String?, val uk: String?)

/** `["us","uk"]` ⇄ [Ipa]; exact length 2 — the asset never writes a hole. */
object IpaSerializer : KSerializer<Ipa> {
    private val delegate = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Ipa {
        val parts = decoder.decodeSerializableValue(delegate)
        require(parts.size == 2) { "IPA array must hold [us, uk], got ${parts.size}" }
        return Ipa(us = parts[0], uk = parts[1])
    }

    override fun serialize(encoder: Encoder, value: Ipa) {
        encoder.encodeSerializableValue(delegate, listOf(value.us.orEmpty(), value.uk.orEmpty()))
    }
}

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

/**
 * One English dictionary entry — the value of `dict/lexicon-en.json`
 * (`docs/2026-09-18-DATA-MODEL.md` §1.2). A shared lookup table's value, not
 * a word-list row: the same entry answers for every list that contains the
 * word. [senses] is empty when ECDICT has no row for the headword.
 */
@Serializable
data class LexEntry(
    @SerialName("s") val senses: List<Sense> = emptyList(),
    @SerialName("i") val ipa: Ipa? = null,
)

/**
 * One bare-char entry — the value of `dict/lexicon-hanzi.json`. [compound] is
 * null when the char has no derivable 组词, which is not a defect: the row
 * then carries the reading alone (`字 | 拼音`).
 */
@Serializable
data class HanziEntry(
    @SerialName("p") val pinyin: String,
    @SerialName("c") val compound: String? = null,
)

/**
 * Flatten a resolved word back into the `word | pos | gloss` row the dial,
 * the display list and the 错词本 restore read **today** — a presentation
 * adapter, not a write-back: rows are never persisted with looked-up columns
 * (`§0`), and the surfaces move to reading [ResolvedWord] itself in Phase 3,
 * which retires this function.
 *
 * The two columns are the row's own when it has them, the lexicon's otherwise
 * (`§1.4`): a 汉字 row shows 拼音/组词, an English row 词性 + 释义 — senses
 * rejoined with `；`, a later sense's POS spelled out inline exactly as the
 * old flat asset stored it (`fine | adj. | 身体好的…；v. 对……处以罚款`).
 */
fun ResolvedWord.displayRow(): WordRow {
    val mainPos = senses.firstOrNull()?.pos
    val gloss = senses.joinToString("；") { sense ->
        if (sense.pos != null && sense.pos != mainPos) "${sense.pos} ${sense.gloss}" else sense.gloss
    }
    return wordRowOf(display, pos = pinyin ?: mainPos, gloss = compound ?: gloss.ifEmpty { null })
}
