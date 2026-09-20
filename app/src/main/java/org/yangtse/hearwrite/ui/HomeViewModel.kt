package org.yangtse.hearwrite.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.data.HistoryEntry
import org.yangtse.hearwrite.data.LibraryList
import org.yangtse.hearwrite.data.NormalizedRect
import org.yangtse.hearwrite.data.OCR_PROGRESS_COMPRESSING
import org.yangtse.hearwrite.data.OCR_PROGRESS_RECOGNIZING
import org.yangtse.hearwrite.data.OcrLang
import org.yangtse.hearwrite.data.inferOcrLang
import org.yangtse.hearwrite.data.OcrOutcome
import org.yangtse.hearwrite.data.WrongWordMark
import org.yangtse.hearwrite.domain.CJK_RE
import org.yangtse.hearwrite.domain.DEFAULT_INTERVAL_SEC
import org.yangtse.hearwrite.domain.MAX_INTERVAL_SEC
import org.yangtse.hearwrite.domain.MIN_INTERVAL_SEC
import org.yangtse.hearwrite.domain.ResolvedWord
import org.yangtse.hearwrite.domain.WordRow
import org.yangtse.hearwrite.domain.parseWordRows
import org.yangtse.hearwrite.domain.parseWords
import org.yangtse.hearwrite.domain.prepareStartRows
import org.yangtse.hearwrite.domain.resolveWord
import org.yangtse.hearwrite.domain.rowToLine

/**
 * A removed history row kept for its 撤销: the exact stored row plus whether
 * its star was set, so [HomeViewModel.restoreHistory] can put both back and a
 * 错词本 source pointing at the id resolves again.
 */
data class HistoryUndo(
    val entry: HistoryEntry,
    val wasFavorited: Boolean,
)

/** Debounce for draft persistence; the flush on dispose covers the tail. */
private const val DRAFT_DEBOUNCE_MS = 500L

private const val TAG = "HomeViewModel"

/** One resolvable favorite row for the 收藏 sheet. */
data class FavoriteUiItem(
    val id: String,
    val title: String,
    val subtitle: String,
    /** Word-list lines handed to the draft when the row is applied. */
    val linesText: String,
)

/**
 * A prepared dictation from Home: resolved rows (slice → shuffle applied) plus
 * the provenance of the recorded history row, which becomes the run's 错词本
 * source label (Roadmap #1). A bare-word start (听写错词 over the book)
 * carries a null source.
 */
data class PreparedSession(
    val rows: List<ResolvedWord>,
    val historyId: String?,
)

/**
 * One 错词本 drawer group: words sharing a resolved source. [sourceTitle] is
 * the resolved list label (null = 未知来源), [jumpCategory]/[jumpLabel] carry
 * the built-in list the group came from so the drawer can offer 查看词表
 * (source resolution to a clickable library jump).
 */
data class WrongWordGroup(
    /**
     * The raw source id the group was built from (null = manual / orphaned).
     * It — not the resolved title — is the group's identity: resolved titles
     * are not unique (未知来源, 多词表 and 多词表（N 个词表） are shared by
     * every unresolvable source), so keying a LazyColumn on the title would
     * throw "Key was already used" on exactly the degradation path this model
     * exists to support.
     */
    val sourceId: String?,
    val sourceTitle: String?,
    val marks: List<WrongWordMark>,
    val jumpCategory: String?,
    val jumpLabel: String?,
) {
    /** Groups sort by their top word's count (most-wrong group first). */
    val topSortKey: Pair<Int, Long> get() = marks.first().sortKey
}

/**
 * Home: pasted word list with the persisted draft (500 ms debounce + flush on
 * dispose), start options 起始序号 (clamped when the list shrinks) and 随机顺序
 * (session-local Fisher–Yates). The draft stays exactly as authored — 展示态
 * and the started run read the offline lexicon's 词性/释义 (and 拼音/组词)
 * resolved at that moment, and only the plain list is recorded in history
 * (cap 50). Also owns the 历史 / 收藏 drawer state and the 拍照识词 OCR flow
 * (AGENTS.md re-entry rule: a Mutex claimed synchronously before any
 * suspension, so fast double-taps can never start a second request).
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HearWriteApplication
    private val settings = app.settingsRepository
    private val historyRepository = app.historyRepository
    private val favoritesRepository = app.favoritesRepository
    private val libraryRepository = app.libraryRepository
    private val lexiconRepository = app.lexiconRepository
    private val ocrService = app.ocrService
    private val wrongWordsRepository = app.wrongWordsRepository
    private val wrongWordLines = app.wrongWordLineResolver

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /**
     * False until the persisted draft has been read. The seed is a DataStore
     * round trip, so without this gate every Home entry showed a frame of
     * "共 0 词" / an empty editor — and 开始听写 pressed in that window
     * answered 请先输入单词列表 for a list that was about to appear. The
     * screen shows a loading placeholder until it flips true (a failed read
     * still flips it, degrading to the empty draft).
     */
    private val _draftLoaded = MutableStateFlow(false)
    val draftLoaded: StateFlow<Boolean> = _draftLoaded.asStateFlow()

    private val _startIndex = MutableStateFlow(0)
    /** Index into the parsed word list where dictation starts (0-based). */
    val startIndex: StateFlow<Int> = _startIndex.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    /** 展示态 (完成) vs 编辑态 — session-local; OCR/history/library applies
     *  land back in 展示态 like alice's isDisplayMode. */
    private val _displayMode = MutableStateFlow(true)
    val displayMode: StateFlow<Boolean> = _displayMode.asStateFlow()

    /** 听写间隔秒数 — persisted DataStore key shared with 设置 / 听写页. */
    private val _intervalSec = MutableStateFlow(DEFAULT_INTERVAL_SEC)
    val intervalSec: StateFlow<Double> = _intervalSec.asStateFlow()

    /** 自动播放下一词 — persisted DataStore key shared with 设置 / 听写页. */
    private val _autoNext = MutableStateFlow(true)
    val autoNext: StateFlow<Boolean> = _autoNext.asStateFlow()

    /** Live parsed count of the draft. */
    val wordCount: StateFlow<Int> = _draft.map { parseWords(it).size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * 展示态 rows: the draft parsed and composed with the offline lexicon
     * (row columns win, `docs/2026-09-18-DATA-MODEL.md` §1.4). The draft itself
     * is never rewritten — entering 展示态 used to expand the ECDICT columns
     * *into the stored draft*, which persisted looked-up data and left every
     * row that already carried a column without its 音标 (§0).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val displayRows: StateFlow<List<ResolvedWord>> = combine(_draft, _displayMode) { text, display ->
        if (display) text else null
    }
        .mapLatest { text -> if (text == null) emptyList() else resolve(parseWordRows(text)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- 历史 / 收藏 drawer state ----------------------------------------

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    /** History/library entries whose id is favorited, in favorite order. */
    private val _favoriteItems = MutableStateFlow<List<FavoriteUiItem>>(emptyList())
    val favoriteItems: StateFlow<List<FavoriteUiItem>> = _favoriteItems.asStateFlow()

    private val _wrongWords = MutableStateFlow<List<WrongWordMark>>(emptyList())
    /** 错词本 rows (word + count + source) for the 更多 drawer, most-wrong first. */
    val wrongWords: StateFlow<List<WrongWordMark>> = _wrongWords.asStateFlow()

    /** Wrong-word marks regrouped by resolved source (null source → 未知来源). */
    private val _wrongGroups = MutableStateFlow<List<WrongWordGroup>>(emptyList())
    val wrongGroups: StateFlow<List<WrongWordGroup>> = _wrongGroups.asStateFlow()

    /** Built-in list titles by list id (`default_*` → label) for source jumps. */
    private val _libraryTitles = MutableStateFlow<Map<String, String>>(emptyMap())

    private val _starting = MutableStateFlow(false)
    /**
     * True while [prepareAndRecord] is running: the start action resolves the
     * draft's rows against the offline lexicon (a cold process pays the lazy
     * asset parse) and records the list in history, so the button spins
     * instead of looking dead.
     */
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    // ---- 拍照识词 (OCR import) state --------------------------------------

    /**
     * OCR re-entry gate: tryLock() is claimed synchronously at the top of
     * every OCR run — before the first suspension point — and released in a
     * finally. Upstream guarded after an await and double-fired requests
     * (there it double-charged credits); here it would double network calls.
     */
    private val ocrGate = Mutex()

    /** 听写错词 gate: restoring the source lines reads Room/assets, so the
     *  drawer's button awaits — claimed before that first suspension. */
    private val wrongWordGate = Mutex()

    private val _ocrBusy = MutableStateFlow(false)
    /** True while a recognition run is in flight (buttons/spinners). */
    val ocrBusy: StateFlow<Boolean> = _ocrBusy.asStateFlow()

    private val _ocrPhase = MutableStateFlow("")
    /** In-flight progress text ("处理图片中…" / "识别中…"). */
    val ocrPhase: StateFlow<String> = _ocrPhase.asStateFlow()

    private val _ocrError = MutableStateFlow<String?>(null)
    /** Terminal OCR failure (Chinese); cleared by a new run or [clearOcrError]. */
    val ocrError: StateFlow<String?> = _ocrError.asStateFlow()

    private val _ocrOutcome = MutableStateFlow<String?>(null)
    /** One-shot success toast text; consumed by the screen via [clearOcrOutcome]. */
    val ocrOutcome: StateFlow<String?> = _ocrOutcome.asStateFlow()

    private val _ocrRetryable = MutableStateFlow(false)
    /** True when the last run reached the network stage, so 重试 can re-run it. */
    val ocrRetryable: StateFlow<Boolean> = _ocrRetryable.asStateFlow()

    private val _ocrConfigured = MutableStateFlow(false)
    /** True when a complete BYOK OCR provider config is stored. */
    val ocrConfigured: StateFlow<Boolean> = _ocrConfigured.asStateFlow()

    private val _ocrModel = MutableStateFlow("")
    val ocrModel: StateFlow<String> = _ocrModel.asStateFlow()

    /**
     * Stored 识别语言 (null = the user never picked one). Kept separate from
     * [ocrLang] so "no choice yet" stays distinguishable — the sheet then opens
     * on the language the current draft is written in instead of forcing
     * ENGLISH on a 汉字 user, which used to make the first scan of a 生字表
     * answer 未识别到英文单词.
     */
    private val _ocrLangStored = MutableStateFlow<OcrLang?>(null)

    /** The sheet's selected 识别语言 (persisted on every change). */
    private val _ocrLang = MutableStateFlow(OcrLang.ENGLISH)
    val ocrLang: StateFlow<OcrLang> = _ocrLang.asStateFlow()

    /**
     * A recognition that succeeded over a non-blank draft: held until the user
     * decides whether it replaces what is there or appends to it. The draft is
     * persisted and not recoverable (only a run writes history), so a silent
     * overwrite threw the old list away with no way back.
     */
    private val _ocrPending = MutableStateFlow<List<String>?>(null)
    val ocrPending: StateFlow<List<String>?> = _ocrPending.asStateFlow()

    /**
     * The last OCR failure needs the 设置 page to be fixable (no provider
     * configured / key missing): the error card then offers 去设置. The retry
     * path cannot help — there is nothing installed to retry against.
     */
    private val _ocrNeedsSettings = MutableStateFlow(false)
    val ocrNeedsSettings: StateFlow<Boolean> = _ocrNeedsSettings.asStateFlow()

    /** dataUrl + lang of the last compressed image — the 重试 target. */
    private var lastOcrRun: Pair<String, OcrLang>? = null
    /** The in-flight recognition (cancelled by [cancelOcr]); null when idle. */
    private var ocrJob: Job? = null

    // ---- 选定识别区域 (crop step) ------------------------------------------

    /**
     * The decode half of the crop step — session id, decode job, the bitmap
     * slot and its recycle discipline ([CropSessionHost]). Only the half
     * **below** is Home's own: confirming here replaces the draft with the
     * recognized lines, where DictationViewModel grades a student's sheet.
     */
    private val cropHost = CropSessionHost { uri -> ocrService.decodeCropSource(uri) }

    /** Decoded source shown in the crop overlay (EXIF-rotated, ≤ 4096 px). */
    val cropBitmap: StateFlow<Bitmap?> = cropHost.bitmap

    /** True while the picked image is being decoded for the crop overlay. */
    val cropLoading: StateFlow<Boolean> = cropHost.loading

    /** Reclaim the decode bitmap whenever the ViewModel goes away. */
    override fun onCleared() {
        cropHost.recycle()
    }

    init {
        // Seed the draft from the persisted value, then watch for changes.
        // Every seed degrades to its default — a DataStore read failure must
        // never crash the Home screen (AGENTS.md: every launch catches).
        viewModelScope.launch {
            _draft.value = try {
                settings.draft.first()
            } catch (e: Exception) {
                Log.w(TAG, "draft seed failed", e)
                ""
            }
            // The seeded text is the sheet's first inference input: without
            // this the coroutine races the ocrLang collector below and a 汉字
            // draft can open the sheet on 英文.
            syncInferredLang()
            _draftLoaded.value = true
        }
        // Seed the playback settings for the bottom panel (设置页 writes the
        // same keys; the dictation session reads them at start).
        viewModelScope.launch {
            _intervalSec.value = try {
                settings.intervalSec.first()
            } catch (e: Exception) {
                Log.w(TAG, "interval seed failed", e)
                DEFAULT_INTERVAL_SEC
            }
            _autoNext.value = try {
                settings.autoNext.first()
            } catch (e: Exception) {
                Log.w(TAG, "auto-next seed failed", e)
                true
            }
        }
        // Persist debounced; flushDraft() covers the pending tail on dispose.
        // One failed write must not kill the collector for the whole process.
        @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
        viewModelScope.launch {
            _draft.debounce(DRAFT_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { value ->
                    try {
                        settings.setDraft(value)
                    } catch (e: Exception) {
                        Log.w(TAG, "draft persist failed", e)
                    }
                }
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
        // 错词本 rows for the 更多 drawer: Room marks (most-wrong first)
        // resolved against the live history (a user list's source title is the
        // row's first line) and the built-in title map. Groups re-form
        // whenever any input moves (a mark lands, history is pruned, titles
        // load late).
        viewModelScope.launch {
            combine(
                wrongWordsRepository.observeMarks(),
                historyRepository.observe(),
                _libraryTitles,
            ) { marks, historyRows, titles ->
                // Source resolution is shared with the 听写统计 page.
                marks.map { mark ->
                    ResolvedWrongMark(mark, resolveSourceTitle(mark.sourceLabel, historyRows, titles))
                }
            }.collect { resolved ->
                _wrongWords.value = resolved.map { it.mark }
                _wrongGroups.value = groupResolvedWrong(resolved)
            }
        }
        // Built-in list titles (id → label) load once off the main thread;
        // the combine above re-resolves the groups when they land.
        viewModelScope.launch {
            val titles = buildMap {
                libraryRepository.categories().forEach { category ->
                    try {
                        libraryRepository.lists(category.name).forEach { list ->
                            put(list.id, list.label)
                        }
                    } catch (e: Exception) {
                        // Titles are decoration; a failed category degrades
                        // that group's title to 未知来源.
                    }
                }
            }
            _libraryTitles.value = titles
        }
        // OCR provider config feeds the scan sheet's service row.
        viewModelScope.launch {
            settings.ocrProviderConfig.collect { cfg ->
                _ocrConfigured.value = cfg?.isComplete == true
                _ocrModel.value = cfg?.model.orEmpty()
            }
        }
        // 识别语言: restore the user's last choice; with none stored, infer it
        // from the draft the user is looking at (a 汉字 list opens the sheet on
        // 中文). The inference is re-run on every stored-flow emission, so it
        // also covers "user pasted a 生字表 first, never opened the sheet".
        viewModelScope.launch {
            settings.ocrLang.collect { stored ->
                _ocrLangStored.value = stored
                _ocrLang.value = stored ?: inferOcrLang(parseWordRows(_draft.value))
            }
        }
    }

    // ------------------------------------------------------------- draft

    fun onDraftChange(value: String) {
        _draft.value = value
        syncInferredLang()
    }

    /**
     * Track the draft's language while the user has never chosen one: the sheet
     * then opens on 中文 for a 汉字 list instead of 英文. A stored choice is
     * never overridden — it is the user's answer to exactly this question.
     */
    private fun syncInferredLang() {
        if (_ocrLangStored.value == null) {
            _ocrLang.value = inferOcrLang(parseWordRows(_draft.value))
        }
    }

    /** The sheet's 识别语言 tab: persisted, so the next scan remembers it. */
    fun setOcrLang(lang: OcrLang) {
        _ocrLang.value = lang
        _ocrLangStored.value = lang
        viewModelScope.launch {
            try {
                settings.setOcrLang(lang)
            } catch (e: Exception) {
                Log.w(TAG, "ocr lang persist failed", e)
            }
        }
    }

    fun fillSample(text: String) {
        _draft.value = text
        _startIndex.value = 0
        syncInferredLang()
        viewModelScope.launch { settings.setDraft(text) }
    }

    fun clearDraft() = onDraftChange("")

    /**
     * The 起始词, clamped to the current list: a display-list row tap selects
     * that row, and the playback panel's reset returns the count-in to word 1
     * ([setStartIndex] with 0). Ignored over an empty list.
     */
    fun setStartIndex(index: Int) {
        val count = wordCount.value
        if (count == 0) return
        _startIndex.value = index.coerceIn(0, count - 1)
    }

    /**
     * 编辑/完成 toggle. 展示态 renders [displayRows] — the draft resolved
     * against the offline lexicon at read time; the draft text and its
     * persisted copy stay exactly what the user typed (alice expanded the
     * stored draft here, which is what the row/lexicon split removes).
     */
    fun setDisplayMode(value: Boolean) {
        _displayMode.value = value
    }

    /**
     * Delete display-list row [index]: rewrite the draft from the remaining
     * entries with alice shift semantics — deleting before the cursor keeps
     * the cursor word selected; deleting the cursor word clamps to the new
     * last. (The wordCount collector only backstops shrink-below-cursor.)
     */
    fun deleteWord(index: Int) {
        val rows = parseWordRows(_draft.value).toMutableList()
        if (index !in rows.indices) return
        rows.removeAt(index)
        onDraftChange(rows.joinToString("\n") { rowToLine(it) })
        val count = rows.size
        _startIndex.value = when {
            index < _startIndex.value -> (_startIndex.value - 1).coerceIn(0, (count - 1).coerceAtLeast(0))
            index == _startIndex.value -> _startIndex.value.coerceIn(0, (count - 1).coerceAtLeast(0))
            else -> _startIndex.value
        }
    }

    /** 0.5 s snapped interval, persisted (设置页 slider shares the key). */
    fun onIntervalChange(sec: Double) {
        val snapped = (sec.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC) * 2).roundToInt() / 2.0
        _intervalSec.value = snapped
        viewModelScope.launch { settings.setIntervalSec(snapped) }
    }

    fun onAutoNextChange(on: Boolean) {
        _autoNext.value = on
        viewModelScope.launch { settings.setAutoNext(on) }
    }

    fun onShuffleChange(on: Boolean) {
        _shuffle.value = on
    }

    /**
     * Flush the pending debounced draft (DisposableEffect.onDispose). Runs on
     * the application scope: by the time Compose disposes this screen the
     * ViewModel is already cleared (androidx.activity clears the store on
     * ON_DESTROY), so a viewModelScope launch would never execute and the
     * last ≤500 ms of typing would be lost (AGENTS.md debounce flush).
     */
    fun flushDraft() {
        app.applicationScope.launch { settings.setDraft(_draft.value) }
    }

    /**
     * Apply a history/favorite entry to the draft — the entry's own authored
     * text (a row is never enriched; 词性/释义 are read from the lexicon at
     * display time), the upstream "已载入" behavior.
     */
    fun applyEntry(linesText: String) {
        _draft.value = linesText
        _startIndex.value = 0
        _displayMode.value = true
        syncInferredLang()
        viewModelScope.launch { settings.setDraft(linesText) }
    }

    // ------------------------------------------------- 历史 / 收藏 actions

    /**
     * The row most recently removed by [deleteHistory], kept only so the
     * confirmation's 撤销 can put it back: the sheet drops a row on one tap (a
     * confirm per row would be noise) and the message is the only way back. It
     * holds the newest deletion only — 撤销 is offered once, and a second
     * remove replaces it.
     */
    private var lastDeletedHistory: HistoryUndo? = null

    /**
     * 删除 one history row; the caller raises the confirmation **with 撤销**,
     * wired to [undoDeleteHistory].
     */
    fun deleteHistory(id: String) {
        // Snapshot before the delete: after it the row is gone from `_history`.
        lastDeletedHistory = _history.value.firstOrNull { it.id == id }?.let { entry ->
            HistoryUndo(entry, wasFavorited = id in _favorites.value)
        }
        viewModelScope.launch {
            try {
                historyRepository.delete(id)
            } catch (e: Exception) {
                // DB failures must not crash the screen; next launch re-reads.
            }
        }
    }

    /**
     * 撤销 a [deleteHistory]: re-insert the exact row (id, authored text,
     * timestamp) and its star, so a 错词本 source or favorite pointing at it
     * resolves again. No-op when nothing was deleted (or 撤销 already ran).
     */
    fun undoDeleteHistory() {
        val undo = lastDeletedHistory ?: return
        lastDeletedHistory = null
        viewModelScope.launch {
            try {
                historyRepository.restore(undo.entry, undo.wasFavorited)
            } catch (e: Exception) {
                // Best effort; the row stays deleted if Room refuses.
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

    /**
     * The mark most recently removed by [removeWrongWord] — same contract as
     * [lastDeletedHistory]: 撤销 is offered once by the message, and a second
     * removal replaces it.
     */
    private var lastRemovedWrong: WrongWordMark? = null

    /**
     * 移除 one wrong word (错词本 drawer row); the caller raises the
     * confirmation **with 撤销**, wired to [undoRemoveWrongWord].
     */
    fun removeWrongWord(word: String) {
        // Snapshot before the delete: after it the row is gone from `_wrongWords`.
        lastRemovedWrong = _wrongWords.value.firstOrNull { it.word == word }
        viewModelScope.launch {
            try {
                wrongWordsRepository.remove(word)
            } catch (e: Exception) {
                // Best effort.
            }
        }
    }

    /**
     * 撤销 a [removeWrongWord]: re-insert the mark exactly as it was (count,
     * times, source) — routing it back through `add` would count as a fresh
     * wrong run and corrupt the book's counts. No-op when nothing was removed.
     */
    fun undoRemoveWrongWord() {
        val mark = lastRemovedWrong ?: return
        lastRemovedWrong = null
        viewModelScope.launch {
            try {
                wrongWordsRepository.restore(mark)
            } catch (e: Exception) {
                // Best effort; the row stays removed if Room refuses.
            }
        }
    }

    /** Empty the whole 错词本 (confirm dialog owned by the screen). */
    fun clearWrongWords() {
        viewModelScope.launch {
            try {
                wrongWordsRepository.clear()
            } catch (e: Exception) {
                // Best effort.
            }
        }
    }

    /**
     * 听写错词 (错词本 drawer): the book's lines with their original 词性/释义
     * or 拼音/组词 restored from each mark's source (Roadmap #7) — a built-in
     * asset list or the history row it was dictated from; a mark whose source
     * is gone stays a bare headword. Null when the book is empty or another
     * start is already in flight (the gate is claimed before the first
     * suspension, AGENTS.md).
     */
    suspend fun prepareWrongWordRun(): List<ResolvedWord>? {
        if (!wrongWordGate.tryLock()) return null
        try {
            val marks = wrongWordsRepository.observeMarks().first()
            if (marks.isEmpty()) return null
            return wrongWordLines.rowsFor(marks)
        } catch (e: Exception) {
            // A failed read/gate must not start a half-resolved run; the
            // sheet simply stays as it was.
            return null
        } finally {
            wrongWordGate.unlock()
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
            linesText = entries.joinToString("\n") { rowToLine(it) },
        )
    }

    private fun resolveHistory(row: HistoryEntry): FavoriteUiItem? {
        val text = row.text
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
     * Prepare the dictation list: resolve the draft's rows against the
     * offline lexicon (row columns win — the draft itself is recorded and
     * persisted as authored), record the user list in history (deduped, cap
     * 50), slice from the clamped 起始序号, then apply 随机顺序. Returns null
     * when there is nothing to dictate or another start is already in flight.
     */
    suspend fun prepareAndRecord(): PreparedSession? {
        if (_starting.value) return null
        val text = _draft.value
        val rows = parseWordRows(text)
        if (rows.isEmpty()) return null
        _starting.value = true
        try {
            // The row id becomes the run's wrong-word source (Roadmap #1) —
            // marks from this dictation point back to the recorded list. The
            // stored text is the authored one: 词性/释义 (and 音标) are read
            // from the lexicon when they are shown, never baked into the row.
            val historyId = historyRepository.add(text)
            return PreparedSession(
                prepareStartRows(resolve(rows), _startIndex.value, _shuffle.value),
                historyId,
            )
        } finally {
            _starting.value = false
        }
    }

    /**
     * Rows composed with the offline lexicon, for the surfaces that read a
     * resolved word (the display list, the dial, 朗读释义). Any failure (asset
     * missing, parse error) degrades to the rows' own columns — never blocks
     * the UI or dictation.
     */
    private suspend fun resolve(rows: List<WordRow>): List<ResolvedWord> = try {
        lexiconRepository.resolve(rows)
    } catch (e: CancellationException) {
        // mapLatest cancels the previous pass when the draft changes; that
        // cancellation must propagate, not degrade to a stale row list.
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "lexicon resolve failed", e)
        rows.map { resolveWord(it, entry = null, hanzi = null) }
    }

    // ------------------------------------------------------- 拍照识词 (OCR)

    /**
     * Begin the 选定识别区域 crop step for a picked/captured image: decode
     * the source off the main thread; the overlay shows a spinner until
     * [cropBitmap] lands. On decode failure [ocrError] carries the Chinese
     * 读取失败 message and the overlay closes itself ([cropBitmap] stays null).
     */
    fun startOcrCrop(uri: Uri) {
        _ocrError.value = null
        cropHost.start(viewModelScope, uri) {
            _ocrError.value = "读取图片失败，请重新拍摄或选择"
        }
    }

    /** Leave the crop step without recognizing; recycles the decoded source. */
    fun cancelOcrCrop() {
        cropHost.cancel()
    }

    /**
     * Recognize the user's [rect] selection (normalized over the crop source,
     * taken with the overlay's full-frame default = whole-page OCR): crop to
     * the region, compress off the main thread, then the standard vision
     * call; success replaces the draft with the parsed lines for manual
     * correction (upstream behavior). The decoded source is recycled exactly
     * once whatever the outcome (owned tracks it until the encode consumed
     * its pixels).
     */
    fun confirmOcrCrop(rect: NormalizedRect, lang: OcrLang) {
        val source = cropHost.take() ?: return
        ocrJob = viewModelScope.launch {
            if (!ocrGate.tryLock()) {
                source.recycle()
                return@launch
            }
            var owned: Bitmap? = source
            try {
                _ocrBusy.value = true
                _ocrError.value = null
                _ocrNeedsSettings.value = false
                _ocrRetryable.value = false
                // BYOK: no config (or incomplete) → Chinese error with retry
                // hint; the user must supply their own key in 设置.
                if (ocrService.config() == null) {
                    failOcrConfig()
                    return@launch
                }
                _ocrPhase.value = OCR_PROGRESS_COMPRESSING
                val dataUrl = ocrService.cropToDataUrl(source, rect)
                if (dataUrl == null) {
                    _ocrError.value = "读取图片失败，请重新拍摄或选择"
                    return@launch
                }
                owned = null
                source.recycle()
                lastOcrRun = dataUrl to lang
                _ocrRetryable.value = true
                _ocrPhase.value = OCR_PROGRESS_RECOGNIZING
                when (val outcome = ocrService.recognize(dataUrl, lang)) {
                    is OcrOutcome.Success -> applyOcrOutcome(outcome.linesText, lang)
                    is OcrOutcome.Error -> _ocrError.value = outcome.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A recognition failure must never crash the process; the
                // error surfaces inline with the retry hint (AGENTS.md).
                Log.w(TAG, "OCR recognition failed", e)
                _ocrError.value = "识别失败，请重试"
            } finally {
                owned?.recycle()
                _ocrBusy.value = false
                _ocrPhase.value = ""
                ocrGate.unlock()
            }
        }
    }

    /** Re-run the last recognition against the same image (after an error). */
    fun retryOcr() {
        val last = lastOcrRun ?: return
        launchOcrRun(last.second) { last.first }
    }

    /**
     * Abandon the in-flight recognition (the progress strip's 取消). The
     * vision call is a 30 s round trip, so a wrong crop or a slow provider
     * must not have to be waited out; cancelling the job cancels the OkHttp
     * call through the service's cancellation hook, and the body's own
     * `finally` releases the gate and clears the busy state. The last image
     * stays the 重试 target.
     */
    fun cancelOcr() {
        val job = ocrJob ?: return
        if (!job.isActive) return
        job.cancel()
        _ocrOutcome.value = "已取消识别"
    }

    fun clearOcrError() {
        _ocrError.value = null
        _ocrNeedsSettings.value = false
    }

    fun clearOcrOutcome() {
        _ocrOutcome.value = null
    }

    /**
     * Handle a successful recognition. An empty draft is replaced silently (the
     * user has nothing to lose — the old behavior, and the common first-use
     * case); over a non-blank draft the lines are held in [ocrPending] until
     * the user chooses 替换 / 追加 / 取消 on the screen. The previous draft is
     * not recoverable afterwards (only a dictation run writes history), so a
     * silent overwrite was a one-way door.
     */
    private fun applyOcrOutcome(linesText: String, lang: OcrLang) {
        if (parseWords(_draft.value).isEmpty()) {
            commitOcrOutcome(linesText, lang)
            return
        }
        _ocrPending.value = parseWords(linesText)
        _ocrOutcome.value = ocrSuccessMessage(linesText, lang)
    }

    /** 替换草稿 with the recognized lines (the pending-result dialog). */
    fun replaceDraftWithOcr() {
        val lines = _ocrPending.value ?: return
        _ocrPending.value = null
        commitOcrOutcome(lines.joinToString("\n"), _ocrLang.value)
    }

    /** 追加 the recognized lines to the current draft, keeping what is there. */
    fun appendOcrToDraft() {
        val lines = _ocrPending.value ?: return
        _ocrPending.value = null
        val existing = parseWords(_draft.value)
        commitOcrOutcome((existing + lines).joinToString("\n"), _ocrLang.value)
    }

    /** 取消 a pending result: the draft (and its persisted copy) is untouched. */
    fun discardOcrPending() {
        _ocrPending.value = null
    }

    private fun commitOcrOutcome(linesText: String, lang: OcrLang) {
        _draft.value = linesText
        _startIndex.value = 0
        _displayMode.value = true
        syncInferredLang()
        viewModelScope.launch {
            try {
                settings.setDraft(linesText)
            } catch (e: Exception) {
                Log.w(TAG, "OCR draft persist failed", e)
            }
        }
        _ocrOutcome.value = ocrSuccessMessage(linesText, lang)
        // The error card, if any, described the run that just succeeded.
        _ocrError.value = null
        _ocrNeedsSettings.value = false
    }

    private fun launchOcrRun(lang: OcrLang, acquireDataUrl: suspend () -> String?) {
        ocrJob = viewModelScope.launch {
            // Re-entry guard: the gate is claimed synchronously BEFORE any
            // suspension (AGENTS.md) — a fast double-tap can only lose here.
            if (!ocrGate.tryLock()) return@launch
            try {
                _ocrBusy.value = true
                _ocrError.value = null
                _ocrNeedsSettings.value = false
                _ocrRetryable.value = false
                // BYOK: no config (or incomplete) → Chinese error with retry
                // hint; the user must supply their own key in 设置.
                if (ocrService.config() == null) {
                    failOcrConfig()
                    return@launch
                }
                _ocrPhase.value = OCR_PROGRESS_COMPRESSING
                val dataUrl = acquireDataUrl()
                if (dataUrl == null) {
                    _ocrError.value = "读取图片失败，请重新拍摄或选择"
                    return@launch
                }
                lastOcrRun = dataUrl to lang
                _ocrRetryable.value = true
                _ocrPhase.value = OCR_PROGRESS_RECOGNIZING
                when (val outcome = ocrService.recognize(dataUrl, lang)) {
                    is OcrOutcome.Success -> applyOcrOutcome(outcome.linesText, lang)
                    is OcrOutcome.Error -> _ocrError.value = outcome.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A recognition failure must never crash the process; the
                // error surfaces inline with the retry hint (AGENTS.md).
                Log.w(TAG, "OCR recognition failed", e)
                _ocrError.value = "识别失败，请重试"
            } finally {
                _ocrBusy.value = false
                _ocrPhase.value = ""
                ocrGate.unlock()
            }
        }
    }

    /**
     * No usable provider config: the run cannot be retried, only fixed in 设置 —
     * so the error card carries a 去设置 action (the message alone told the user
     * where to go and left them there with only 关闭).
     */
    private fun failOcrConfig() {
        _ocrError.value = "请先在设置中配置识别服务（需自备 API Key）"
        _ocrNeedsSettings.value = true
    }

    /** Success toast text: upstream OCR_OUTCOME_MESSAGES (生字/词语 split for CJK). */
    private fun ocrSuccessMessage(linesText: String, lang: OcrLang): String {
        val entries = parseWordRows(linesText)
        return if (lang == OcrLang.CHINESE) {
            val chars = entries.count { it.display.length == 1 && CJK_RE.containsMatchIn(it.display) }
            val terms = entries.size - chars
            when {
                terms == 0 -> "已识别 $chars 个生字"
                chars == 0 -> "已识别 $terms 个词语"
                else -> "已识别 $chars 个生字、$terms 个词语"
            }
        } else {
            "已识别 ${entries.size} 个单词"
        }
    }
}

/**
 * A wrong-word mark paired with its resolved source title. [title] is null
 * when the source is gone (a deleted history row) or the word was entered by
 * hand — the UI degrades that to "未知来源" (the book outlives its sources).
 */
private data class ResolvedWrongMark(
    val mark: WrongWordMark,
    val title: String?,
)

/**
 * Group resolved marks by their (raw) source id so the drawer shows one
 * section per dictation source. Group key = source id; the title displayed is
 * the resolved label of the group's first mark (all marks of one id resolve
 * identically). Built-in sources (`default_*`) carry their category/label for
 * a 查看词表 jump; history/manual/null sources do not. Input arrives
 * most-wrong first; groups and marks preserve that order.
 */
private fun groupResolvedWrong(resolved: List<ResolvedWrongMark>): List<WrongWordGroup> {
    val bySource = LinkedHashMap<String?, MutableList<ResolvedWrongMark>>()
    resolved.forEach { row -> bySource.getOrPut(row.mark.sourceLabel) { mutableListOf() }.add(row) }
    return bySource.map { (sourceLabel, rows) ->
        val first = rows.first()
        // The resolved title is what the library browser shows; the jump is
        // offered only for a source that still resolves to a library list.
        val jump = resolveSourceJump(sourceLabel, first.title)
        WrongWordGroup(
            sourceId = sourceLabel,
            sourceTitle = first.title,
            marks = rows.map { it.mark },
            jumpCategory = jump?.category,
            jumpLabel = jump?.label,
        )
    }
}
