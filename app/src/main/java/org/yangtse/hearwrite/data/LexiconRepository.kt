package org.yangtse.hearwrite.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.yangtse.hearwrite.domain.HanziEntry
import org.yangtse.hearwrite.domain.Ipa
import org.yangtse.hearwrite.domain.LexEntry
import org.yangtse.hearwrite.domain.ResolvedWord
import org.yangtse.hearwrite.domain.Sense
import org.yangtse.hearwrite.domain.WordKind
import org.yangtse.hearwrite.domain.WordRow
import org.yangtse.hearwrite.domain.kindOf
import java.io.InputStream

private const val EN_PATH = "dict/lexicon-en.json"
private const val HANZI_PATH = "dict/lexicon-hanzi.json"

/** The two assets' `{"v":2,"entries":{…}}` envelopes. */
@Serializable
private class EnglishLexicon(val entries: Map<String, LexEntry> = emptyMap())

@Serializable
private class HanziLexicon(val entries: Map<String, HanziEntry> = emptyMap())

private val JSON = Json { ignoreUnknownKeys = true }

/** Anything that is not a plain lowercase letter, digit, blank, `'`, `-`, `.` or `/`. */
private val OCR_NOISE_RE = Regex("""[^a-z0-9\s'\-./]""")

/**
 * The offline dictionary — the shared lookup table the word-model rewrite
 * split out of the word lists (`docs/2026-09-18-DATA-MODEL.md` §1.2). Two
 * assets, each parsed lazily on first lookup on [Dispatchers.IO] and kept in a
 * process-lifetime memory singleton, never on the startup path (AGENTS.md
 * "Built-in library"):
 *
 * - `dict/lexicon-en.json` (~6.5 MB, ~53k entries) — English headword →
 *   义项 + IPA, built by `scripts/build-lexicon.py` from ECDICT (义项),
 *   ipa-dict (音标) and the 仁爱 textbook's own IPA;
 * - `dict/lexicon-hanzi.json` (~155 KB, ~5.1k entries) — single Chinese char →
 *   拼音 + 组词, built by `scripts/build-hanzi-lexicon.py`. A Chinese-only
 *   list never parses the English lexicon.
 *
 * [readAssetStream] is the asset seam: the app opens the packaged asset, JVM
 * tests pass a stub stream (same pattern as [WrongWordLineResolver]). The
 * assets are decoded **from the stream, straight into the domain types** — no
 * intermediate JSON string and no DTO graph. Measured on a device, the first
 * lookup of `lexicon-en.json` keeps ~13 MB of the 53k entries and peaks ~54 MB
 * while parsing; building a JSON string plus a parallel DTO graph first cost
 * ~23 MB retained and a ~96 MB peak for the same table (~745 ms either way).
 *
 * Nothing here writes back: [resolve] composes a row with its entry into a
 * [ResolvedWord] the caller consumes, and the row itself — the draft, a
 * history row, a staged session — stays exactly as authored (§0).
 */
class LexiconRepository(private val readAssetStream: (String) -> InputStream) {

    @Volatile
    private var english: Map<String, LexEntry>? = null

    @Volatile
    private var hanzi: Map<String, HanziEntry>? = null

    private fun englishTable(): Map<String, LexEntry> {
        english?.let { return it }
        return synchronized(this) {
            english ?: parseEnglish().also { english = it }
        }
    }

    private fun hanziTable(): Map<String, HanziEntry> {
        hanzi?.let { return it }
        return synchronized(this) {
            hanzi ?: parseHanzi().also { hanzi = it }
        }
    }

    private fun parseEnglish(): Map<String, LexEntry> =
        readAssetStream(EN_PATH).use { JSON.decodeFromStream<EnglishLexicon>(it).entries }

    private fun parseHanzi(): Map<String, HanziEntry> =
        readAssetStream(HANZI_PATH).use { JSON.decodeFromStream<HanziLexicon>(it).entries }

    // ------------------------------------------------------------- lookup

    /**
     * The dictionary entry for [headword], composed into a [ResolvedWord]
     * whose `display`/`speak` are the headword itself and whose columns come
     * from exactly one source: a **single Han char** reads
     * `lexicon-hanzi.json` (拼音/组词), everything else `lexicon-en.json`
     * (义项/IPA). A **multi-char Chinese word never reaches the English
     * dictionary** — it would take an English gloss where its 释义 belongs —
     * and so has no entry at all.
     *
     * Null when the headword is absent, or blank.
     */
    suspend fun lookup(headword: String): ResolvedWord? =
        withContext(Dispatchers.IO) { lookupIn(headword) }

    private fun lookupIn(headword: String): ResolvedWord? {
        val head = headword.trim()
        if (head.isEmpty()) return null
        return when (kindOf(head)) {
            WordKind.HANZI -> hanziTable()[head]?.let { entry ->
                ResolvedWord(
                    display = head,
                    speak = head,
                    kind = WordKind.HANZI,
                    senses = emptyList(),
                    pinyin = entry.pinyin,
                    compound = entry.compound,
                    ipa = null,
                )
            }

            WordKind.EN -> lookupEnglish(head)?.let { entry ->
                ResolvedWord(
                    display = head,
                    speak = head,
                    kind = WordKind.EN,
                    senses = entry.senses,
                    pinyin = null,
                    compound = null,
                    ipa = entry.ipa,
                )
            }

            WordKind.WORD -> null
        }
    }

    /**
     * Case-insensitive English lookup, tolerating what an OCR pass or a
     * hand-typed list leaves behind: trailing punctuation is stripped once,
     * and a `a/an` headword is retried per side. Null when the word is absent.
     */
    private fun lookupEnglish(word: String): LexEntry? {
        val table = englishTable()
        val key = word.trim().lowercase()
        if (key.isEmpty()) return null
        table[key]?.let { return it }

        val stripped = OCR_NOISE_RE.replace(key, "").trim()
        if (stripped.isNotEmpty() && stripped != key) {
            table[stripped]?.let { return it }
        }
        if (key.contains("/")) {
            for (part in key.split("/")) {
                if (part.isNotEmpty()) table[part]?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------ resolve

    /**
     * 行覆盖 + 词典回填 (`§1.3`): the row's own columns win **field by field**,
     * the lexicon fills what it left empty — the rule that finally gives a
     * 仁爱 row (which prints its own 词性/释义) the textbook IPA it never could
     * get while enrichment skipped any row that already carried a column.
     *
     * IPA only ever comes from the lexicon (a row does not carry one), and a
     * [WordKind.WORD] row is never looked up at all.
     */
    suspend fun resolve(row: WordRow): ResolvedWord = withContext(Dispatchers.IO) { resolveIn(row) }

    /** [resolve] for a whole list, in one IO hop. */
    suspend fun resolve(rows: List<WordRow>): List<ResolvedWord> =
        withContext(Dispatchers.IO) { rows.map(::resolveIn) }

    private fun resolveIn(row: WordRow): ResolvedWord = when (row.kind) {
        WordKind.EN -> {
            val entry = lookupEnglish(row.speak)
            ResolvedWord(
                display = row.display,
                speak = row.speak,
                kind = row.kind,
                senses = rowSenses(row) ?: entry?.senses.orEmpty(),
                pinyin = null,
                compound = null,
                ipa = entry?.ipa,
            )
        }

        WordKind.HANZI -> {
            val entry = hanziTable()[row.speak]
            ResolvedWord(
                display = row.display,
                speak = row.speak,
                kind = row.kind,
                senses = emptyList(),
                pinyin = row.pos ?: entry?.pinyin,
                compound = row.gloss ?: entry?.compound,
                ipa = null,
            )
        }

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

    /** A row's own `pos`/`gloss` as one sense — null when it carries neither. */
    private fun rowSenses(row: WordRow): List<Sense>? =
        if (row.pos == null && row.gloss == null) null
        else listOf(Sense(row.pos, row.gloss.orEmpty()))
}
