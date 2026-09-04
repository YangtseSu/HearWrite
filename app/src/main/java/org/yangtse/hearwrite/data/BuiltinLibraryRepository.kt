package org.yangtse.hearwrite.data

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yangtse.hearwrite.domain.WordEntry
import org.yangtse.hearwrite.domain.builtinListId
import org.yangtse.hearwrite.domain.compareLabels
import org.yangtse.hearwrite.domain.parseWordEntries
import java.util.concurrent.ConcurrentHashMap

/** A built-in library list at packaged asset path `<category>/<label>.txt`. */
data class LibraryList(val category: String, val label: String) {
    /** Stable storage key (`default_<category>_<label>`, AGENTS.md "Built-in library"). */
    val id: String get() = builtinListId(category, label)
}

/** One browsable category directory with its list count. */
data class LibraryCategory(val name: String, val listCount: Int)

/** A list containing headwords that match the query, with up to three examples. */
data class WordHit(val list: LibraryList, val words: List<String>)

data class LibrarySearchResult(
    val labelHits: List<LibraryList>,
    val wordHits: List<WordHit>,
)

/**
 * Asset-backed access to the built-in library (`app/src/main/assets/` shipped as
 * APK assets). Content is read from assets on demand and cached in memory for the
 * process lifetime — never persisted (AGENTS.md). Asset enumeration, parsing and
 * sorting all run on [Dispatchers.IO].
 */
class BuiltinLibraryRepository(private val assets: AssetManager) {

    /** Asset top-level dirs that are not library categories (non-.txt siblings). */
    private val nonLibrary = setOf("dict", "compounds", "audio")

    private val entriesCache = ConcurrentHashMap<String, List<WordEntry>>()

    /** All 10 textbook categories, ordered like the upstream library generator. */
    suspend fun categories(): List<LibraryCategory> = withContext(Dispatchers.IO) {
        assets.list("")
            .orEmpty()
            .filter { name -> name !in nonLibrary }
            .filter { name -> assets.list(name).orEmpty().any { it.endsWith(".txt") } }
            .sortedWith(::compareLabels)
            .map { name -> LibraryCategory(name, listsOn(name).size) }
    }

    /** Lists of [category], sorted by the upstream label ordering. */
    suspend fun lists(category: String): List<LibraryList> = withContext(Dispatchers.IO) {
        listsOn(category).map { LibraryList(category, it) }
    }

    /** Parsed entries of one list (cached after first load). */
    suspend fun entries(list: LibraryList): List<WordEntry> = withContext(Dispatchers.IO) {
        entriesCache.getOrPut(list.id) {
            val text = assets.open("${list.category}/${list.label}.txt")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            parseWordEntries(text)
        }
    }

    /**
     * Full-library search: lists whose label contains the query, then lists
     * containing matching headwords (case-insensitive contains). Reads every
     * list once (filling the cache); the payload is small (≈ 0.5 MB total).
     */
    suspend fun search(query: String): LibrarySearchResult = withContext(Dispatchers.IO) {
        val q = query.trim()
        val labelHits = mutableListOf<LibraryList>()
        val wordHits = mutableListOf<WordHit>()
        if (q.isNotEmpty()) {
            val cats = assets.list("").orEmpty()
                .filter { it !in nonLibrary && assets.list(it).orEmpty().any { f -> f.endsWith(".txt") } }
                .sortedWith(::compareLabels)
            for (category in cats) {
                for (label in listsOn(category)) {
                    val list = LibraryList(category, label)
                    if (label.contains(q, ignoreCase = true)) {
                        labelHits += list
                        continue
                    }
                    val matched = entries(list)
                        .map { it.word }
                        .distinct()
                        .filter { it.contains(q, ignoreCase = true) }
                    if (matched.isNotEmpty()) {
                        wordHits += WordHit(list, matched.take(3))
                    }
                }
            }
        }
        LibrarySearchResult(labelHits, wordHits)
    }

    private fun listsOn(category: String): List<String> =
        assets.list(category).orEmpty()
            .filter { it.endsWith(".txt") }
            .map { it.removeSuffix(".txt") }
            .sortedWith(::compareLabels)
}
