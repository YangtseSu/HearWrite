package org.yangtse.hearwrite.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.data.LibraryCategory
import org.yangtse.hearwrite.data.LibraryList
import org.yangtse.hearwrite.data.LibrarySearchResult
import org.yangtse.hearwrite.domain.ResolvedWord
import org.yangtse.hearwrite.domain.WordRow
import org.yangtse.hearwrite.domain.isCjkRun
import org.yangtse.hearwrite.domain.prepareStartRows
import org.yangtse.hearwrite.domain.resolveWord
import org.yangtse.hearwrite.domain.rowToLine
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Once-per-process guard for the lexicon probe: the warm-up it triggers is
     * idempotent, this only stops a 230-list category from asking 230 times.
     */
    private val lexiconProbeDone = AtomicBoolean(false)

    /**
     * Whether this category needs the English dictionary — asked of rows
     * [BuiltinLibraryRepository.wordCount] has **already parsed and cached**, so
     * the answer costs no extra read. An English category warms the table while
     * the user is still picking a list (a device spends ~4 s here, against a
     * ~745 ms parse); a Chinese-only one (课标 字表, 语文 读读写写) never warms
     * it — the dictionary staying lazy for such a list is a design constraint,
     * not an implementation detail (AGENTS.md "Lexicon asset loading").
     */
    private suspend fun warmLexiconIfEnglish(list: LibraryList) {
        if (lexiconProbeDone.get()) return
        val rows = try {
            repository.entries(list)
        } catch (e: Exception) {
            // The probe is an optimisation; an unreadable list just skips it.
            return
        }
        if (!isCjkRun(rows) && lexiconProbeDone.compareAndSet(false, true)) {
            app.warmEnglishLexicon()
        }
    }

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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 词数 is decoration — a failed count leaves the row
                        // without a subtitle; log so asset problems surface.
                        Log.w("LibraryListsViewModel", "wordCount failed for ${list.id}", e)
                        return@launch
                    }
                    // wordCount parsed — and cached — this list's rows, so the
                    // language probe below costs no extra read.
                    warmLexiconIfEnglish(list)
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
    private val lexiconRepository = app.lexiconRepository
    val category: String = checkNotNull(handle["category"])
    val label: String = checkNotNull(handle["label"])

    /**
     * The list's authored rows, kept for [draftLines]: 载入草稿 hands the draft
     * the list as the asset holds it, never a row the dictionary has been
     * written into (`docs/2026-09-18-DATA-MODEL.md` §0).
     */
    @Volatile
    private var parsedRows: List<WordRow> = emptyList()

    private val _entries = MutableStateFlow<List<ResolvedWord>?>(null)
    /** null = still loading. Rows carry the lexicon's 音标/词性/释义 (or 拼音/组词). */
    val entries: StateFlow<List<ResolvedWord>?> = _entries.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    /** 随机顺序 — session-local, defaults off (never persisted). */
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _startIndex = MutableStateFlow(0)
    /** 起始序号: 0-based index of the tapped start word; 0 = whole list. */
    val startIndex: StateFlow<Int> = _startIndex.asStateFlow()

    private val _starting = MutableStateFlow(false)
    /** True while [startRows] is preparing (the lazy lexicon parse — hundreds
     *  of ms on a cold process). The button spins instead of looking dead,
     *  mirroring Home's 整理词表… state. */
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

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

    /** Final rows for one start: slice from 起始序号, then 随机顺序 — the same
     *  ordering Home applies (AGENTS.md playback engine stays dumb). Rows are
     *  already resolved (the list is read once), so a start never races the
     *  dictionary. Double invocations are rejected (not queued); null when
     *  another start is already in flight or the list failed to load. */
    suspend fun startRows(): List<ResolvedWord>? {
        if (!startGate.tryLock()) return null
        _starting.value = true
        try {
            val current = _entries.value ?: return null
            return prepareStartRows(current, _startIndex.value, _shuffle.value)
        } finally {
            _starting.value = false
            startGate.unlock()
        }
    }

    /** 载入草稿: the list's authored lines, exactly as the asset holds them. */
    fun draftLines(): List<String> = parsedRows.map(::rowToLine)

    init {
        viewModelScope.launch {
            try {
                // Authored rows first, published with their own columns so the
                // list renders immediately; then the dictionary pass on IO (the
                // lexicon parses lazily on first lookup — never on the startup
                // path, AGENTS.md). A bare English word gains ECDICT 音标/义项,
                // a bare single Chinese char gains 拼音/组词 from
                // `lexicon-hanzi.json`; a multi-char Chinese word has no offline
                // source. A stale result is dropped if the list changed.
                val parsed = repository.entries(LibraryList(category, label))
                parsedRows = parsed
                val immediate = parsed.map { resolveWord(it, entry = null, hanzi = null) }
                _entries.value = immediate
                val resolved = lexiconRepository.resolve(parsed)
                if (_entries.value == immediate) _entries.value = resolved
            } catch (e: Exception) {
                // Asset/parse failure degrades to the plain list (or to the
                // still-loading state) — the preview keeps working.
                Log.w("LibraryPreviewViewModel", "preview resolve failed for $category/$label", e)
            }
        }
    }
}
