package org.yangtse.hearwrite.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.data.LibraryList
import org.yangtse.hearwrite.domain.dedupeByHeadword
import org.yangtse.hearwrite.domain.entryToLine
import org.yangtse.hearwrite.domain.multiSourceLabel
import org.yangtse.hearwrite.domain.parseBuiltinListId
import org.yangtse.hearwrite.domain.sampleWords

/** One ticked list of a 抽词听写 pool, with its loaded 词数. */
data class DrawListInfo(val id: String, val category: String, val label: String, val wordCount: Int)

/** The 抽词听写 pool: the ticked lists, the merged candidate count, and how
 *  many cross-list duplicates the headword dedupe dropped. */
data class DrawPoolState(
    val loading: Boolean = true,
    val lists: List<DrawListInfo> = emptyList(),
    /** Candidate count X is drawn from (cross-list duplicates merged). */
    val poolSize: Int = 0,
    val mergedCount: Int = 0,
)

/** A startable draw: the sampled lines plus the run's provenance label. */
data class DrawSession(val lines: List<String>, val sourceLabel: String)

/**
 * 抽词听写 (Roadmap #9): the 多选词库 selection is loaded as one candidate pool
 * — lists in selection order, cross-list duplicates merged by speakable
 * headword, bare English headwords enriched with the offline ECDICT meta —
 * then X lines are drawn without replacement. The pool is assembled before
 * the start entry points, so the result continues through the existing
 * staging into the unchanged playback engine.
 */
class LibraryDrawViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HearWriteApplication
    private val repository = app.libraryRepository
    private val dictionaryRepository = app.dictionaryRepository
    private val selection = app.librarySelection

    private val _pool = MutableStateFlow<DrawPoolState>(DrawPoolState())
    val pool: StateFlow<DrawPoolState> = _pool.asStateFlow()

    private val _count = MutableStateFlow(0)
    /** Requested draw size X, clamped against the pool on every use. */
    val count: StateFlow<Int> = _count.asStateFlow()

    /** The assembled candidate lines (enriched), kept for [prepareSession]. */
    @Volatile
    private var candidates: List<String> = emptyList()

    /** Serializes starts; claimed before the first suspension (AGENTS.md
     *  re-entry guard) so a double tap cannot stage two sessions. */
    private val startGate = Mutex()

    init {
        viewModelScope.launch {
            val ids = selection.selectedIds.value.toList()
            var merged = 0
            val lists = ArrayList<DrawListInfo>(ids.size)
            val raw = ArrayList<String>()
            ids.forEach { id ->
                val parts = parseBuiltinListId(id)
                if (parts == null) {
                    // A selection id always comes from a library list row; a
                    // malformed one is dropped rather than failing the pool.
                    Log.w("LibraryDrawViewModel", "unusable selection id: $id")
                    return@forEach
                }
                val (category, label) = parts
                // A list that fails to load (asset read error) degrades by
                // being left out; the rest of the pool still dictates.
                val lines = try {
                    repository.entries(LibraryList(category, label)).map(::entryToLine)
                } catch (e: Exception) {
                    Log.w("LibraryDrawViewModel", "list load failed for $id", e)
                    emptyList()
                }
                if (lines.isEmpty()) return@forEach
                lists += DrawListInfo(id, category, label, lines.size)
                raw += lines
            }
            val deduped = dedupeByHeadword(raw)
            merged = raw.size - deduped.size
            // Same enrichment as the list preview: the ECDICT 词性/释义 (and
            // hanzi 拼音/组词) columns must ride into the dictation, or an
            // English draw would dictate bare words with no hints at all.
            val enriched = try {
                dictionaryRepository.enrichLines(deduped)
            } catch (e: Exception) {
                Log.w("LibraryDrawViewModel", "pool enrich failed", e)
                deduped
            }
            candidates = enriched
            _count.value = enriched.size
            _pool.value = DrawPoolState(
                loading = false,
                lists = lists,
                poolSize = enriched.size,
                mergedCount = merged,
            )
        }
    }

    /** Clamp a typed X to [1, poolSize]; 0 when the pool is empty. */
    fun onCountChange(value: Int) {
        val size = _pool.value.poolSize
        _count.value = if (size == 0) 0 else value.coerceIn(1, size)
    }

    /**
     * Draw this session: X distinct lines from the pool (all of it when X ≥
     * 词数), plus the run's provenance label naming the member lists. Null
     * when the pool is empty or another start is already in flight.
     */
    suspend fun prepareSession(): DrawSession? {
        if (!startGate.tryLock()) return null
        try {
            val pool = candidates
            if (pool.isEmpty()) return null
            val x = _count.value.coerceIn(1, pool.size)
            val lines = sampleWords(pool, x)
            if (lines.isEmpty()) return null
            val ids = _pool.value.lists.map { it.id }
            return DrawSession(lines, multiSourceLabel(ids))
        } finally {
            startGate.unlock()
        }
    }
}
