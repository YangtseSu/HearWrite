package org.yangtse.hearwrite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.data.HistoryEntry
import org.yangtse.hearwrite.data.LibraryList
import org.yangtse.hearwrite.domain.entryToLine
import org.yangtse.hearwrite.domain.parseWords

/** Debounce for draft persistence; the flush on dispose covers the tail. */
private const val DRAFT_DEBOUNCE_MS = 500L

/** One resolvable favorite row for the 收藏 sheet. */
data class FavoriteUiItem(
    val id: String,
    val title: String,
    val subtitle: String,
    /** Word-list lines handed to the draft when the row is applied. */
    val linesText: String,
)

/**
 * Home: pasted word list with the persisted draft (500 ms debounce + flush on
 * dispose), start options 起始序号 (clamped when the list shrinks) and 随机顺序
 * (session-local Fisher–Yates). Starting enriches bare English words with the
 * offline ECDICT meta, records the list in history (enriched text attached,
 * cap 50), then slices/shuffles and hands the lines to the dictation session.
 * Also owns the 历史 / 收藏 drawer state.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HearWriteApplication
    private val settings = app.settingsRepository
    private val historyRepository = app.historyRepository
    private val favoritesRepository = app.favoritesRepository
    private val libraryRepository = app.libraryRepository
    private val dictionaryRepository = app.dictionaryRepository

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _startIndex = MutableStateFlow(0)
    /** Index into the parsed word list where dictation starts (0-based). */
    val startIndex: StateFlow<Int> = _startIndex.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    /** Live parsed count of the draft. */
    val wordCount: StateFlow<Int> = _draft.map { parseWords(it).size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ---- 历史 / 收藏 drawer state ----------------------------------------

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    /** History/library entries whose id is favorited, in favorite order. */
    private val _favoriteItems = MutableStateFlow<List<FavoriteUiItem>>(emptyList())
    val favoriteItems: StateFlow<List<FavoriteUiItem>> = _favoriteItems.asStateFlow()

    private val _starting = MutableStateFlow(false)
    /** True while the start action enriches/records the list (button spin). */
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    init {
        // Seed the draft from the persisted value, then watch for changes.
        viewModelScope.launch {
            _draft.value = settings.draft.first()
        }
        // Persist debounced; flushDraft() covers the pending tail on dispose.
        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            _draft.debounce(DRAFT_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { settings.setDraft(it) }
        }
        // Clamp the start index whenever the list shrinks below it.
        viewModelScope.launch {
            wordCount.collect { count ->
                if (count == 0) {
                    _startIndex.value = 0
                } else if (_startIndex.value >= count) {
                    _startIndex.value = count - 1
                }
            }
        }
        // History rows + favorite ids stay in sync with Room; the favorite
        // items resolve their source text whenever either changes.
        viewModelScope.launch {
            combine(historyRepository.observe(), favoritesRepository.observeIds()) { h, f ->
                h to f
            }.collect { (h, f) ->
                _history.value = h
                _favorites.value = f
                _favoriteItems.value = resolveFavorites(h, f)
            }
        }
    }

    // ------------------------------------------------------------- draft

    fun onDraftChange(value: String) {
        _draft.value = value
    }

    fun fillSample(text: String) {
        _draft.value = text
        _startIndex.value = 0
        viewModelScope.launch { settings.setDraft(text) }
    }

    fun clearDraft() = onDraftChange("")

    fun adjustStartIndex(delta: Int) {
        val count = wordCount.value
        if (count == 0) return
        _startIndex.value = (_startIndex.value + delta).coerceIn(0, count - 1)
    }

    fun onShuffleChange(on: Boolean) {
        _shuffle.value = on
    }

    /** Flush the pending debounced draft (DisposableEffect.onDispose). */
    fun flushDraft() {
        viewModelScope.launch { settings.setDraft(_draft.value) }
    }

    /**
     * Apply a history/favorite entry to the draft (enriched text when the row
     * has it, else the original text) — the upstream "已载入" behavior.
     */
    fun applyEntry(linesText: String) {
        _draft.value = linesText
        _startIndex.value = 0
        viewModelScope.launch { settings.setDraft(linesText) }
    }

    // ------------------------------------------------- 历史 / 收藏 actions

    fun deleteHistory(id: String) {
        viewModelScope.launch {
            try {
                historyRepository.delete(id)
            } catch (e: Exception) {
                // DB failures must not crash the screen; next launch re-reads.
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            try {
                historyRepository.clear()
            } catch (e: Exception) {
                // Best effort.
            }
        }
    }

    /** Toggle the favorite state of an entry id (library or history row). */
    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            try {
                favoritesRepository.toggle(id)
            } catch (e: Exception) {
                // Best effort.
            }
        }
    }

    private suspend fun resolveFavorites(
        history: List<HistoryEntry>,
        ids: Set<String>,
    ): List<FavoriteUiItem> = ids.mapNotNull { id ->
        if (id.startsWith("default_")) resolveLibrary(id)
        else history.firstOrNull { it.id == id }?.let(::resolveHistory)
    }

    /** `default_<category>_<label>` — labels never contain underscores. */
    private suspend fun resolveLibrary(id: String): FavoriteUiItem? {
        val parts = id.removePrefix("default_").split("_", limit = 2)
        if (parts.size != 2) return null
        val list = LibraryList(parts[0], parts[1])
        val entries = try {
            libraryRepository.entries(list)
        } catch (e: Exception) {
            null
        } ?: return null
        if (entries.isEmpty()) return null
        return FavoriteUiItem(
            id = id,
            title = list.label,
            subtitle = "${list.category} · ${entries.size} 词",
            linesText = entries.joinToString("\n") { entryToLine(it) },
        )
    }

    private fun resolveHistory(row: HistoryEntry): FavoriteUiItem? {
        val text = row.enrichedText ?: row.text
        if (text.isBlank()) return null
        val count = parseWords(text).size
        return FavoriteUiItem(
            id = row.id,
            title = text.lineSequence().first { it.isNotBlank() }.trim(),
            subtitle = "历史记录 · $count 词",
            linesText = text,
        )
    }

    // ------------------------------------------------------------- start

    /**
     * Prepare the dictation list: enrich bare words with ECDICT meta, record
     * the user list in history (deduped, cap 50), slice from the clamped
     * 起始序号, then apply 随机顺序. Returns null when there is nothing to
     * dictate or another start is already in flight.
     */
    suspend fun prepareAndRecord(): List<String>? {
        if (_starting.value) return null
        val text = _draft.value
        val all = parseWords(text)
        if (all.isEmpty()) return null
        _starting.value = true
        try {
            // Enrichment failure (asset missing, parse error) degrades to the
            // plain text — dictation never blocks on the dictionary.
            val enriched = try {
                dictionaryRepository.enrichText(text)
            } catch (e: Exception) {
                text
            }
            historyRepository.add(text, enriched)
            val entries = parseWords(enriched)
            val clamped = _startIndex.value.coerceIn(0, entries.size - 1)
            var lines = entries.subList(clamped, entries.size)
            if (_shuffle.value) lines = lines.shuffled()
            return lines
        } finally {
            _starting.value = false
        }
    }
}
