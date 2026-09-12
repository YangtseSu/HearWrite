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
import org.yangtse.hearwrite.data.OcrOutcome
import org.yangtse.hearwrite.data.WrongWordMark
import org.yangtse.hearwrite.domain.CJK_RE
import org.yangtse.hearwrite.domain.DEFAULT_INTERVAL_SEC
import org.yangtse.hearwrite.domain.entryToLine
import org.yangtse.hearwrite.domain.MAX_INTERVAL_SEC
import org.yangtse.hearwrite.domain.MIN_INTERVAL_SEC
import org.yangtse.hearwrite.domain.parseWordEntries
import org.yangtse.hearwrite.domain.parseWords
import org.yangtse.hearwrite.domain.prepareStartLines

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
 * A prepared dictation from Home: canonical lines (slice → shuffle applied)
 * plus the provenance of the recorded history row, which becomes the run's
 * 错词本 source label (Roadmap #1). A bare-word start (听写错词 over the book)
 * carries a null source.
 */
data class PreparedSession(
    val lines: List<String>,
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
 * (session-local Fisher–Yates). Starting enriches bare English words with the
 * offline ECDICT meta, records the list in history (enriched text attached,
 * cap 50), then slices/shuffles and hands the lines to the dictation session.
 * Also owns the 历史 / 收藏 drawer state and the 拍照识词 OCR flow (AGENTS.md
 * re-entry rule: a Mutex claimed synchronously before any suspension, so
 * fast double-taps can never start a second request).
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HearWriteApplication
    private val settings = app.settingsRepository
    private val historyRepository = app.historyRepository
    private val favoritesRepository = app.favoritesRepository
    private val libraryRepository = app.libraryRepository
    private val dictionaryRepository = app.dictionaryRepository
    private val ocrService = app.ocrService
    private val wrongWordsRepository = app.wrongWordsRepository
    private val wrongWordLines = app.wrongWordLineResolver

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

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
    /** True while the start action enriches/records the list (button spin). */
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

    /** dataUrl + lang of the last compressed image — the 重试 target. */
    private var lastOcrRun: Pair<String, OcrLang>? = null

    // ---- 选定识别区域 (crop step) ------------------------------------------

    /** Reclaim the decode bitmap whenever the ViewModel goes away. */
    override fun onCleared() {
        cropDecodeJob?.cancel()
        cropDecodeJob = null
        _cropBitmap.value?.recycle()
        _cropBitmap.value = null
    }

    private var cropDecodeJob: Job? = null

    /** Crop session id: bumped on start/cancel so stale decode results die. */
    private var cropSession = 0

    private val _cropBitmap = MutableStateFlow<Bitmap?>(null)
    /** Decoded source shown in the crop overlay (EXIF-rotated, ≤ 4096 px). */
    val cropBitmap: StateFlow<Bitmap?> = _cropBitmap.asStateFlow()

    private val _cropLoading = MutableStateFlow(false)
    /** True while the picked image is being decoded for the crop overlay. */
    val cropLoading: StateFlow<Boolean> = _cropLoading.asStateFlow()

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

    /** Display-list row tap: the tapped row becomes the 起始词. */
    fun setStartIndex(index: Int) {
        val count = wordCount.value
        if (count == 0) return
        _startIndex.value = index.coerceIn(0, count - 1)
    }

    /**
     * 编辑/完成 toggle. Entering 展示态 ("完成") enriches the draft with the
     * offline ECDICT meta (alice behavior) so the list shows 词性/释义; a
     * stale result (user switched back and typed meanwhile) is dropped.
     */
    fun setDisplayMode(value: Boolean) {
        _displayMode.value = value
        if (value) enrichDraft()
    }

    private fun enrichDraft() {
        viewModelScope.launch {
            val original = _draft.value
            if (parseWords(original).isEmpty()) return@launch
            val enriched = enrich(original)
            if (enriched != original && _draft.value == original) {
                _draft.value = enriched
                settings.setDraft(enriched)
            }
        }
    }

    /**
     * Delete display-list row [index]: rewrite the draft from the remaining
     * entries with alice shift semantics — deleting before the cursor keeps
     * the cursor word selected; deleting the cursor word clamps to the new
     * last. (The wordCount collector only backstops shrink-below-cursor.)
     */
    fun deleteWord(index: Int) {
        val entries = parseWordEntries(_draft.value).toMutableList()
        if (index !in entries.indices) return
        entries.removeAt(index)
        onDraftChange(entries.joinToString("\n") { entryToLine(it) })
        val count = entries.size
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
     * Apply a history/favorite entry to the draft (enriched text when the row
     * has it, else the original text) — the upstream "已载入" behavior.
     */
    fun applyEntry(linesText: String) {
        _draft.value = linesText
        _startIndex.value = 0
        _displayMode.value = true
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

    /** Remove one wrong word (错词本 drawer row 移除). */
    fun removeWrongWord(word: String) {
        viewModelScope.launch {
            try {
                wrongWordsRepository.remove(word)
            } catch (e: Exception) {
                // Best effort.
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
    suspend fun prepareWrongWordRun(): List<String>? {
        if (!wrongWordGate.tryLock()) return null
        try {
            val marks = wrongWordsRepository.observeMarks().first()
            if (marks.isEmpty()) return null
            return wrongWordLines.linesFor(marks)
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
    suspend fun prepareAndRecord(): PreparedSession? {
        if (_starting.value) return null
        val text = _draft.value
        val all = parseWords(text)
        if (all.isEmpty()) return null
        _starting.value = true
        try {
            val enriched = enrich(text)
            // The row id becomes the run's wrong-word source (Roadmap #1) —
            // marks from this dictation point back to the recorded list.
            val historyId = historyRepository.add(text, enriched)
            val lines = prepareStartLines(parseWords(enriched), _startIndex.value, _shuffle.value)
            return PreparedSession(lines, historyId)
        } finally {
            _starting.value = false
        }
    }

    /**
     * Offline ECDICT enrichment; any failure (asset missing, parse error)
     * degrades to the plain text — never blocks the UI or dictation.
     */
    private suspend fun enrich(text: String): String = try {
        dictionaryRepository.enrichText(text)
    } catch (e: Exception) {
        text
    }

    // ------------------------------------------------------- 拍照识词 (OCR)

    /**
     * Begin the 选定识别区域 crop step for a picked/captured image: decode
     * the source off the main thread; the overlay shows a spinner until
     * [cropBitmap] lands. On decode failure [ocrError] carries the Chinese
     * 读取失败 message and the overlay closes itself ([cropBitmap] stays null).
     */
    fun startOcrCrop(uri: Uri) {
        cropDecodeJob?.cancel()
        val session = ++cropSession
        _cropBitmap.value?.recycle()
        _cropBitmap.value = null
        _ocrError.value = null
        _cropLoading.value = true
        cropDecodeJob = viewModelScope.launch {
            val decoded = ocrService.decodeCropSource(uri)
            if (session != cropSession) {
                decoded?.recycle()
                return@launch
            }
            _cropLoading.value = false
            if (decoded == null) {
                _ocrError.value = "读取图片失败，请重新拍摄或选择"
            } else {
                _cropBitmap.value = decoded
            }
        }
    }

    /** Leave the crop step without recognizing; recycles the decoded source. */
    fun cancelOcrCrop() {
        cropSession++
        cropDecodeJob?.cancel()
        cropDecodeJob = null
        _cropLoading.value = false
        _cropBitmap.value?.recycle()
        _cropBitmap.value = null
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
        val source = _cropBitmap.value ?: return
        _cropBitmap.value = null
        viewModelScope.launch {
            if (!ocrGate.tryLock()) {
                source.recycle()
                return@launch
            }
            var owned: Bitmap? = source
            try {
                _ocrBusy.value = true
                _ocrError.value = null
                _ocrRetryable.value = false
                // BYOK: no config (or incomplete) → Chinese error with retry
                // hint; the user must supply their own key in 设置.
                if (ocrService.config() == null) {
                    _ocrError.value = "请先在设置中配置 OCR 服务（需自备 API Key）"
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
                    is OcrOutcome.Success -> {
                        _draft.value = outcome.linesText
                        _startIndex.value = 0
                        _displayMode.value = true
                        settings.setDraft(outcome.linesText)
                        _ocrOutcome.value = ocrSuccessMessage(outcome.linesText, lang)
                    }
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

    fun clearOcrError() {
        _ocrError.value = null
    }

    fun clearOcrOutcome() {
        _ocrOutcome.value = null
    }

    private fun launchOcrRun(lang: OcrLang, acquireDataUrl: suspend () -> String?) {
        viewModelScope.launch {
            // Re-entry guard: the gate is claimed synchronously BEFORE any
            // suspension (AGENTS.md) — a fast double-tap can only lose here.
            if (!ocrGate.tryLock()) return@launch
            try {
                _ocrBusy.value = true
                _ocrError.value = null
                _ocrRetryable.value = false
                // BYOK: no config (or incomplete) → Chinese error with retry
                // hint; the user must supply their own key in 设置.
                if (ocrService.config() == null) {
                    _ocrError.value = "请先在设置中配置 OCR 服务（需自备 API Key）"
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
                    is OcrOutcome.Success -> {
                        _draft.value = outcome.linesText
                        _startIndex.value = 0
                        _displayMode.value = true
                        settings.setDraft(outcome.linesText)
                        _ocrOutcome.value = ocrSuccessMessage(outcome.linesText, lang)
                    }
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

    /** Success toast text: upstream OCR_OUTCOME_MESSAGES (生字/词语 split for CJK). */
    private fun ocrSuccessMessage(linesText: String, lang: OcrLang): String {
        val entries = parseWordEntries(linesText)
        return if (lang == OcrLang.CHINESE) {
            val chars = entries.count { it.word.length == 1 && CJK_RE.containsMatchIn(it.word) }
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
        var jumpCategory: String? = null
        var jumpLabel: String? = null
        if (sourceLabel != null && sourceLabel.startsWith("default_")) {
            val parts = sourceLabel.removePrefix("default_").split("_", limit = 2)
            if (parts.size == 2) {
                jumpCategory = parts[0]
                // The resolved title (label) is what the library browser shows.
                jumpLabel = first.title
            }
        }
        WrongWordGroup(
            sourceId = sourceLabel,
            sourceTitle = first.title,
            marks = rows.map { it.mark },
            jumpCategory = jumpCategory,
            jumpLabel = jumpLabel,
        )
    }
}
