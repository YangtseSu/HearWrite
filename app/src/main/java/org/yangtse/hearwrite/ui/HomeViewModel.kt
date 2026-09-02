package org.yangtse.hearwrite.ui

import android.app.Application
import android.net.Uri
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
import kotlinx.coroutines.sync.Mutex
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.data.HistoryEntry
import org.yangtse.hearwrite.data.LibraryList
import org.yangtse.hearwrite.data.OCR_PROGRESS_COMPRESSING
import org.yangtse.hearwrite.data.OCR_PROGRESS_RECOGNIZING
import org.yangtse.hearwrite.data.OcrLang
import org.yangtse.hearwrite.data.OcrOutcome
import org.yangtse.hearwrite.domain.CJK_RE
import org.yangtse.hearwrite.domain.entryToLine
import org.yangtse.hearwrite.domain.parseWordEntries
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

    // ---- 拍照识词 (OCR import) state --------------------------------------

    /**
     * OCR re-entry gate: tryLock() is claimed synchronously at the top of
     * every OCR run — before the first suspension point — and released in a
     * finally. Upstream guarded after an await and double-fired requests
     * (there it double-charged credits); here it would double network calls.
     */
    private val ocrGate = Mutex()

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

    // ------------------------------------------------------- 拍照识词 (OCR)

    /**
     * Run OCR on a picked/captured image: compress to a ≤1600 px JPEG data
     * URL, then the vision call; success replaces the draft with the parsed
     * lines for manual correction (upstream behavior).
     */
    fun recognizeImage(uri: Uri, lang: OcrLang) = launchOcrRun(lang) {
        ocrService.compressToDataUrl(uri)
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
                        settings.setDraft(outcome.linesText)
                        _ocrOutcome.value = ocrSuccessMessage(outcome.linesText, lang)
                    }
                    is OcrOutcome.Error -> _ocrError.value = outcome.message
                }
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
