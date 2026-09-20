package org.yangtse.hearwrite.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * One meaning of a lexicon entry: the 词性 plus its gloss
 * (`docs/WORDLIST.md` §9). A structured list rather than the
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
 * may print only one: the two ipa-dict files cover different word sets
 * (`abase` is UK-only, `aargh` US-only) and the 仁爱 textbook prints a single
 * symbol when both accents read the same. A missing accent is a JSON `null`
 * on the wire, and every display surface prints only the accents that exist
 * ([ipaLabels] leads with 英). Not part of a word-list row: a reading belongs
 * to the word, not to the list that happens to contain it.
 *
 * On the wire it is the two-element array `[us, uk]`, either element perhaps
 * `null`; an entry with no transcription omits the field entirely.
 */
@Serializable(with = IpaSerializer::class)
data class Ipa(val us: String?, val uk: String?)

/**
 * `["us", null]` ⇄ [Ipa]; exactly two elements — a side no source covers is a
 * JSON `null`, never `""` (an empty string is not a transcription and would
 * read as one).
 */
object IpaSerializer : KSerializer<Ipa> {
    private val delegate = ListSerializer(String.serializer().nullable)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Ipa {
        val parts = decoder.decodeSerializableValue(delegate)
        require(parts.size == 2) { "IPA array must hold [us, uk], got ${parts.size}" }
        return Ipa(us = parts[0], uk = parts[1])
    }

    override fun serialize(encoder: Encoder, value: Ipa) {
        encoder.encodeSerializableValue(delegate, listOf(value.us, value.uk))
    }
}

/**
 * 行覆盖 + 词典回填 = the shape the dial and the playback engine consume
 * (AGENTS.md *Built-in library*): a pure read-time result, never
 * persisted back into a row. The row wins field by field — its `pos`/`gloss`
 * (词性/释义 or 拼音/组词, whichever the list's kind carries) else the
 * lexicon's senses; IPA only ever comes from the lexicon.
 */
data class ResolvedWord(
    val display: String,
    override val speak: String,
    override val kind: WordKind,
    val senses: List<Sense>,
    val pinyin: String?,
    val compound: String?,
    val ipa: Ipa?,
) : SpeakableWord

/**
 * A resolved word with nothing but its headword: what a 错词本 mark degrades to
 * when its source list is gone (the book outlives its sources, AGENTS.md
 * "Persistence"). [ResolvedWord.speak] and [ResolvedWord.kind] derive from the
 * headword exactly as a parsed row's do.
 */
fun bareResolvedWord(headword: String): ResolvedWord = resolveWord(wordRowOf(headword), null, null)

/**
 * 行覆盖 + 词典回填 (AGENTS.md *Built-in library*): the row wins **field
 * by field** — its own 词性/释义 (EN) or 拼音/组词 (HANZI) else the entry's.
 * 音标 only ever comes from the lexicon; a row never carries one, so a 仁爱 row
 * that prints its own 词性/释义 still gains the textbook reading (`docs/WORDLIST.md` §9).
 *
 * A [WordKind.WORD] row is never looked up (a multi-char Chinese word has no
 * English gloss to take). Null [entry]/[hanzi] is the "no dictionary" case —
 * what the composition degrades to when the asset cannot be read — and it
 * still yields the row's own columns.
 */
fun resolveWord(row: WordRow, entry: LexEntry?, hanzi: HanziEntry?): ResolvedWord =
    when (row.kind) {
        WordKind.EN -> ResolvedWord(
            display = row.display,
            speak = row.speak,
            kind = row.kind,
            senses = if (row.pos == null && row.gloss == null) {
                entry?.senses.orEmpty()
            } else {
                listOf(Sense(row.pos, row.gloss.orEmpty()))
            },
            pinyin = null,
            compound = null,
            ipa = entry?.ipa,
        )

        WordKind.HANZI -> ResolvedWord(
            display = row.display,
            speak = row.speak,
            kind = row.kind,
            senses = emptyList(),
            pinyin = row.pos ?: hanzi?.pinyin,
            compound = row.gloss ?: hanzi?.compound,
            ipa = null,
        )

        WordKind.WORD -> ResolvedWord(
            display = row.display,
            speak = row.speak,
            kind = row.kind,
            senses = emptyList(),
            pinyin = null,
            compound = null,
            ipa = null,
        )
    }

/**
 * One English dictionary entry — the value of `dict/lexicon-en.json`
 * (`docs/WORDLIST.md` §9). A shared lookup table's value, not
 * a word-list row: the same entry answers for every list that contains the
 * word. [senses] is empty when ECDICT has no row for the headword; [ipa] is
 * null when neither source transcribes it, with either side of an existing
 * [Ipa] null when only one source does.
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


