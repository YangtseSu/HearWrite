package org.yangtse.hearwrite.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.yangtse.hearwrite.domain.CJK_RE
import org.yangtse.hearwrite.domain.POS_PREFIX_RE
import org.yangtse.hearwrite.domain.WordEntry
import org.yangtse.hearwrite.domain.entryToLine
import org.yangtse.hearwrite.domain.normalizePos
import org.yangtse.hearwrite.domain.parseWordLine
import org.yangtse.hearwrite.domain.parseWords
import org.yangtse.hearwrite.domain.speakTextFromEntry

/** Offline word meta from ECDICT (English) or `hanzi-meta.json` (Chinese): the
 *  two enrichment columns — pos/拼音 and 释义/组词. */
data class WordMeta(val pos: String?, val meaning: String?)

private const val ECDICT_PATH = "dict/ecdict-meta.json"
private const val HANZI_PATH = "dict/hanzi-meta.json"

/**
 * Offline meta lookup for list enrichment (`word | pos | meaning`), ported
 * from `alice/src/lib/dictionary.ts`. Two derived assets, both flat
 * `key → "col2|col3"` maps, both parsed lazily on first lookup on
 * [Dispatchers.IO] and kept in a process-lifetime memory singleton — never on
 * the startup path (AGENTS.md "Built-in library"):
 *
 * - `dict/ecdict-meta.json` (3.3 MB, ~53k entries) — English headword → 词性 +
 *   中文释义, from ECDICT via `scripts/build-ecdict-meta.py`;
 * - `dict/hanzi-meta.json` (93 KB, ~4.7k entries) — single Chinese char →
 *   拼音 + 组词, from the textbook lists + the frequency table via
 *   `scripts/build-hanzi-meta.py`. Without it a bare char row (pasted list,
 *   OCR result, 课标字表) shows no hint at all and 组词朗读 has no anchor.
 *
 * [readAsset] is the asset seam: the app passes an `AssetManager` reader, JVM
 * tests pass a map lookup (same pattern as [WrongWordLineResolver]).
 */
class DictionaryRepository(private val readAsset: (String) -> String) {

    @Volatile
    private var meta: Map<String, String>? = null

    @Volatile
    private var hanzi: Map<String, String>? = null

    private suspend fun table(): Map<String, String> {
        meta?.let { return it }
        return withContext(Dispatchers.IO) {
            synchronized(this@DictionaryRepository) {
                meta ?: parseAsset().also { meta = it }
            }
        }
    }

    /** Parse the compact map: lowercased word → `"pos|meaning"` (pos may be empty). */
    private fun parseAsset(): Map<String, String> = parseFlatMap(ECDICT_PATH)

    /** Single Chinese char → `"拼音|组词"` (both columns always present). */
    private suspend fun hanziTable(): Map<String, String> {
        hanzi?.let { return it }
        return withContext(Dispatchers.IO) {
            synchronized(this@DictionaryRepository) {
                hanzi ?: parseFlatMap(HANZI_PATH).also { hanzi = it }
            }
        }
    }

    private fun parseFlatMap(path: String): Map<String, String> {
        val text = readAsset(path)
        val json = Json.parseToJsonElement(text) as JsonObject
        val result = HashMap<String, String>(json.size)
        for ((key, value) in json) {
            if (value is JsonPrimitive && value.isString) {
                result[key] = value.content
            }
        }
        return result
    }

    /**
     * Enrich multiline list text: lines that already carry `| pos | meaning`
     * stay unchanged; bare headwords get their offline meta appended — ECDICT
     * for English words, `hanzi-meta.json` for a single Chinese char. Also
     * canonicalizes blank/CRLF lines like `parseWords` does (upstream
     * `enrichWordListText`).
     */
    suspend fun enrichText(text: String): String = enrichLines(parseWords(text)).joinToString("\n")

    /** Line-wise variant for callers that already hold canonical lines. */
    suspend fun enrichLines(lines: List<String>): List<String> = withContext(Dispatchers.IO) {
        // Each map is parsed at most once and only when a row actually needs
        // it: ECDICT is 3.3 MB (~15–25 MB heap), so a Chinese-only list — the
        // 课标 字表, an OCR'd 生字表 — must never pay for it (AGENTS.md
        // "never on the startup path" applies to the whole enrichment call).
        //
        // Routing: a bare English headword →
        // ECDICT; a **single Han char** → `hanzi-meta.json` 拼音/组词; a
        // multi-char Chinese word → nothing at all (no offline source exists,
        // and resolving it from ECDICT would put an English gloss where its
        // 释义 belongs). Rows that already carry columns are never rewritten.
        var ecdict: Map<String, String>? = null
        var hanzi: Map<String, String>? = null
        lines.map { line ->
            val entry = parseWordLine(line)
            if (entry.pos != null || entry.meaning != null) {
                line
            } else {
                val head = speakTextFromEntry(line)
                val meta: WordMeta? = when {
                    !CJK_RE.containsMatchIn(head) ->
                        lookupIn(ecdict ?: table().also { ecdict = it }, head)
                    head.length == 1 ->
                        (hanzi ?: hanziTable().also { hanzi = it })[head]?.let(::decodeStored)
                    else -> null
                }
                if (meta == null || (meta.pos == null && meta.meaning == null)) {
                    line
                } else {
                    entryToLine(WordEntry(entry.word, meta.pos, meta.meaning))
                }
            }
        }
    }

    /** Case-insensitive lookup; null when the word is absent from ECDICT. */
    suspend fun lookupWordMeta(word: String): WordMeta? {
        if (word.isBlank()) return null
        return withContext(Dispatchers.IO) { lookupIn(table(), word) }
    }

    private fun lookupIn(table: Map<String, String>, word: String): WordMeta? {
        val key = word.trim().lowercase()
        if (key.isEmpty()) return null
        table[key]?.let { return decodeStored(it) }

        // Strip trailing punctuation / soft hyphens OCR sometimes leaves behind.
        val stripped = key.replace(Regex("""[^a-z0-9\s'\-./]"""), "").trim()
        if (stripped.isNotEmpty() && stripped != key) {
            table[stripped]?.let { return decodeStored(it) }
        }

        // "a/an" style alternatives — try each side.
        if (key.contains("/")) {
            for (part in key.split("/")) {
                lookupIn(table, part)?.let { return it }
            }
        }
        return null
    }

    /** Stored as `"pos|meaning"` (pos may be empty → `"|meaning"`). */
    private fun decodeStored(raw: String): WordMeta? {
        val bar = raw.indexOf("|")
        if (bar == -1) return splitPosMeaning(raw)
        val pos = raw.substring(0, bar).trim().ifEmpty { null }
        val meaning = normalizeMeaning(raw.substring(bar + 1))
        if (pos == null && meaning == null) return null
        return WordMeta(pos, meaning)
    }

    /** Legacy fallback for a raw gloss like `"n. 苹果"` without the pipe. */
    private fun splitPosMeaning(raw: String): WordMeta? {
        var text = raw.trim()
        if (text.isEmpty() || text.startsWith("【")) return null
        var pos: String? = null
        val match = POS_PREFIX_RE.find(text)
        if (match != null) {
            pos = normalizePos(match.value)
            text = text.substring(match.range.last + 1).trim()
        }
        val meaning = normalizeMeaning(text)
        if (pos == null && meaning == null) return null
        return WordMeta(pos, meaning)
    }

    /** Keep the full gloss (senses stay `；`-separated, like the build script). */
    private fun normalizeMeaning(raw: String): String? = raw.trim().ifEmpty { null }
}
