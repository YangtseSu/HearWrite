package org.yangtse.hearwrite.data

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.yangtse.hearwrite.domain.POS_PREFIX_RE
import org.yangtse.hearwrite.domain.WordEntry
import org.yangtse.hearwrite.domain.entryToLine
import org.yangtse.hearwrite.domain.normalizePos
import org.yangtse.hearwrite.domain.parseWordLine
import org.yangtse.hearwrite.domain.parseWords
import org.yangtse.hearwrite.domain.speakTextFromEntry

/** Offline word meta from ECDICT: pos + 中文释义. */
data class WordMeta(val pos: String?, val meaning: String?)

private const val ECDICT_PATH = "dict/ecdict-meta.json"

/**
 * Offline English→Chinese ECDICT lookup for list enrichment (`word | pos |
 * meaning`), ported from `alice/src/lib/dictionary.ts`. AGENTS.md: the
 * ~53k-entry map is parsed lazily on the first lookup on [Dispatchers.IO] and
 * kept in a process-lifetime memory singleton — never on the startup path.
 */
class DictionaryRepository(private val assets: AssetManager) {

    @Volatile
    private var meta: Map<String, String>? = null

    private suspend fun table(): Map<String, String> {
        meta?.let { return it }
        return withContext(Dispatchers.IO) {
            synchronized(this@DictionaryRepository) {
                meta ?: parseAsset().also { meta = it }
            }
        }
    }

    /** Parse the compact map: lowercased word → `"pos|meaning"` (pos may be empty). */
    private fun parseAsset(): Map<String, String> {
        val text = assets.open(ECDICT_PATH).bufferedReader().use { it.readText() }
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
     * stay unchanged; bare headwords get the ECDICT meta appended. Also
     * canonicalizes blank/CRLF lines like `parseWords` does (upstream
     * `enrichWordListText`).
     */
    suspend fun enrichText(text: String): String = withContext(Dispatchers.IO) {
        val t = table()
        parseWords(text).joinToString("\n") { line -> enrichLine(t, line) }
    }

    /** Line-wise variant for callers that already hold canonical lines. */
    suspend fun enrichLines(lines: List<String>): List<String> = withContext(Dispatchers.IO) {
        val t = table()
        lines.map { line -> enrichLine(t, line) }
    }

    private fun enrichLine(table: Map<String, String>, line: String): String {
        val entry = parseWordLine(line)
        if (entry.pos != null || entry.meaning != null) return line
        val meta = lookupIn(table, speakTextFromEntry(line)) ?: return line
        if (meta.pos == null && meta.meaning == null) return line
        return entryToLine(WordEntry(word = entry.word, pos = meta.pos, meaning = meta.meaning))
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
