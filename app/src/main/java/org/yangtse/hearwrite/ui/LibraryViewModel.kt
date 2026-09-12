package org.yangtse.hearwrite.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.data.LibraryCategory
import org.yangtse.hearwrite.data.LibraryList
import org.yangtse.hearwrite.data.LibrarySearchResult
import org.yangtse.hearwrite.domain.WordEntry
import org.yangtse.hearwrite.domain.entryToLine
import org.yangtse.hearwrite.domain.isCjkEntry
import org.yangtse.hearwrite.domain.parseWordLine
import org.yangtse.hearwrite.domain.prepareStartLines

/** Search UI state: idle (no query), loading, or the finished result. */
sealed interface LibrarySearchState {
    data object Idle : LibrarySearchState
    data object Loading : LibrarySearchState
    data class Done(val result: LibrarySearchResult) : LibrarySearchState
}

/** Library browse screen: category list + full-library search (label and word hits). */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as HearWriteApplication).libraryRepository

    private val _categories = MutableStateFlow<List<LibraryCategory>?>(null)
    /** null = still loading (asset scan). */
    val categories: StateFlow<List<LibraryCategory>?> = _categories.asStateFlow()

    private val _queryText = MutableStateFlow("")
    val queryText: StateFlow<String> = _queryText.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val searchState: StateFlow<LibrarySearchState> = _queryText
        .debounce(250)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf<LibrarySearchState>(LibrarySearchState.Idle)
            else flow<LibrarySearchState> {
                emit(LibrarySearchState.Loading)
                emit(LibrarySearchState.Done(repository.search(q)))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibrarySearchState.Idle)

    init {
        viewModelScope.launch { _categories.value = repository.categories() }
    }

    fun onQueryChange(newQuery: String) {
        _queryText.value = newQuery
    }
}

/** One category's lists (label ordering from the domain comparator). */
class LibraryListsViewModel(
    application: Application,
    handle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val app = application as HearWriteApplication
    private val repository = app.libraryRepository
    private val favoritesRepository = app.favoritesRepository
    val category: String = checkNotNull(handle["category"])

    private val _lists = MutableStateFlow<List<LibraryList>?>(null)
    /** null = still loading. */
    val lists: StateFlow<List<LibraryList>?> = _lists.asStateFlow()

    private val _wordCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Cached 词数 per list id (fills in as counts finish; rows show them as
     *  they arrive so the screen never blocks on the file reads). */
    val wordCounts: StateFlow<Map<String, Int>> = _wordCounts.asStateFlow()

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    /** Favorited entry ids of this screen (stars on list rows). */
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = repository.lists(category)
            _lists.value = loaded
            // 词数 are best-effort decoration: read in parallel off the main
            // thread (entries() parses on Dispatchers.IO and fills the shared
            // cache — a later preview/start costs nothing), each count lands
            // in the map the moment it is done so the list scrolls in first.
            loaded.forEach { list ->
                launch {
                    try {
                        val count = repository.wordCount(list)
                        _wordCounts.update { it + (list.id to count) }
                    } catch (e: Exception) {
                        // 词数 is decoration — a failed count leaves the row
                        // without a subtitle; log so asset problems surface.
                        Log.w("LibraryListsViewModel", "wordCount failed for ${list.id}", e)
                    }
                }
            }
        }
        viewModelScope.launch {
            favoritesRepository.observeIds().collect { _favoriteIds.value = it }
        }
    }

    /** Toggle the favorite state of a built-in list entry. */
    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            try {
                favoritesRepository.toggle(id)
            } catch (e: Exception) {
                // Best effort; the star follows the Room state on change.
            }
        }
    }
}

/** Parsed entries of one list for the preview screen. */
class LibraryPreviewViewModel(
    application: Application,
    handle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val app = application as HearWriteApplication
    private val repository = app.libraryRepository
    private val dictionaryRepository = app.dictionaryRepository
    val category: String = checkNotNull(handle["category"])
    val label: String = checkNotNull(handle["label"])

    private val _entries = MutableStateFlow<List<WordEntry>?>(null)
    /** null = still loading. */
    val entries: StateFlow<List<WordEntry>?> = _entries.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    /** 随机顺序 — session-local, defaults off (never persisted). */
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _startIndex = MutableStateFlow(0)
    /** 起始序号: 0-based index of the tapped start word; 0 = whole list. */
    val startIndex: StateFlow<Int> = _startIndex.asStateFlow()

    private val _starting = MutableStateFlow(false)
    /**
     * True while [startLines] is preparing (awaits the lazy ECDICT enrich —
     * hundreds of ms on a cold process). The button spins instead of looking
     * dead, mirroring Home's 整理词表… state.
     */
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    /** Completes once the initial enrich pass settled (done, skipped, or
     *  failed) — [startLines] awaits it so a start in the enrich window still
     *  ships the ECDICT meanings (朗读释义 needs them). */
    private val enrichSettled = CompletableDeferred<Unit>()

    /** Serializes starts; claimed before the first suspension (AGENTS.md
     *  re-entry guard) so a double tap cannot queue two sessions. */
    private val startGate = Mutex()

    fun onShuffleChange(value: Boolean) {
        _shuffle.value = value
    }

    fun selectStart(index: Int) {
        _startIndex.value = index
    }

    fun resetStart() {
        _startIndex.value = 0
    }

    /** Final lines for one start: slice from 起始序号, then 随机顺序 — the same
     *  ordering Home applies (AGENTS.md playback engine stays dumb). Waits
     *  for the initial ECDICT enrich to settle so a fast start does not drop
     *  the spoken meanings; double invocations are rejected (not queued).
     *  Returns null when another start is already in flight. */
    suspend fun startLines(): List<String>? {
        if (!startGate.tryLock()) return null
        _starting.value = true
        try {
            enrichSettled.await()
            val current = _entries.value ?: return null
            return prepareStartLines(current.map(::entryToLine), _startIndex.value, _shuffle.value)
        } finally {
            _starting.value = false
            startGate.unlock()
        }
    }

    init {
        viewModelScope.launch {
            // enrichSettled MUST complete on every path (success, skip,
            // asset failure) — startLines() awaits it and would hang forever
            // on an uncompleted deferred.
            try {
                loadAndEnrich()
            } catch (e: Exception) {
                // Asset/parse failure degrades to the plain list — the
                // preview still shows the headwords (like Home's enrich).
                Log.w("LibraryPreviewViewModel", "preview enrich failed for $category/$label", e)
            } finally {
                enrichSettled.complete(Unit)
            }
        }
    }

    private suspend fun loadAndEnrich() {
        val list = LibraryList(category, label)
        // Parsed rows first so the list renders immediately; then enrich
        // English headwords with the offline ECDICT meta on IO (the
        // dictionary parses lazily on first lookup — never on the startup
        // path, AGENTS.md). Only bare English words are touched: Chinese
        // entries keep their pinyin/组词 columns and enriched lines stay
        // unchanged. A stale result is dropped if the list changed.
        val parsed = repository.entries(list)
        _entries.value = parsed
        // Bare English words get ECDICT meta; a bare single Chinese char gets
        // 拼音/组词 from `dict/hanzi-meta.json`. Multi-char Chinese words have
        // no offline source (and no columns to fill), so they never trigger it.
        val needsEnrich = parsed.any {
            it.pos == null && it.meaning == null && (!isCjkEntry(it.word) || it.word.length == 1)
        }
        if (!needsEnrich) return
        val enriched = dictionaryRepository.enrichLines(parsed.map(::entryToLine))
            .map(::parseWordLine)
        if (_entries.value == parsed) _entries.value = enriched
    }
}
