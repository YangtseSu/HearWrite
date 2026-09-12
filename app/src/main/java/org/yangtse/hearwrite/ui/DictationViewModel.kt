package org.yangtse.hearwrite.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.data.DictationSessionStore
import org.yangtse.hearwrite.data.Haptics
import org.yangtse.hearwrite.data.NormalizedRect
import org.yangtse.hearwrite.data.OCR_PROGRESS_COMPRESSING
import org.yangtse.hearwrite.data.OCR_PROGRESS_RECOGNIZING
import org.yangtse.hearwrite.data.OcrLang
import org.yangtse.hearwrite.data.OcrOutcome
import org.yangtse.hearwrite.domain.CompoundTables
import org.yangtse.hearwrite.domain.DictationEngine
import org.yangtse.hearwrite.domain.GradeResult
import org.yangtse.hearwrite.domain.MAX_INTERVAL_SEC
import org.yangtse.hearwrite.domain.MIN_INTERVAL_SEC
import org.yangtse.hearwrite.domain.PlayState
import org.yangtse.hearwrite.domain.Speaker
import org.yangtse.hearwrite.domain.SessionKind
import org.yangtse.hearwrite.domain.TtsSource
import org.yangtse.hearwrite.domain.cjkWordSpeech
import org.yangtse.hearwrite.domain.findLineByHeadword
import org.yangtse.hearwrite.domain.gradeAnswers
import org.yangtse.hearwrite.domain.isCjkEntry
import org.yangtse.hearwrite.domain.isCjkRun
import org.yangtse.hearwrite.domain.parseWordLine
import org.yangtse.hearwrite.domain.parseWords
import org.yangtse.hearwrite.domain.speakTextFromEntry
import org.yangtse.hearwrite.domain.speakableMeaning

/** Everything the dictation screen renders. */
data class DictationUiState(
    val state: PlayState,
    val finished: Boolean,
    val index: Int,
    val total: Int,
    val remainingMs: Long?,
    val intervalSec: Double,
    val autoNext: Boolean,
    val wrongWords: List<String>,
    /** Words marked wrong during the current run (the score uses this, not the book). */
    val runWrongCount: Int,
    val markedFlash: Boolean,
    val ready: Boolean,
    val elapsedSec: Long?,
) {
    val isActive: Boolean get() = state == PlayState.PLAYING || state == PlayState.PAUSED
}

/**
 * Owns the per-session [DictationEngine] (its own scope, disposed with the
 * ViewModel) plus the session settings. The 错词本 is the persisted global
 * book (AGENTS.md "Persistence"): seeded from Room before the run starts,
 * session marks append and persist immediately; the finish surface offers
 * 再听一遍 (replay this run's words in its own order), 复习错词 (re-run over
 * exactly the wrong set, marks restored to their original lines), 导出错词
 * (clipboard) and remove/clear book management.
 */
class DictationViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HearWriteApplication
    private val settings = app.settingsRepository
    private val wrongWordsRepository = app.wrongWordsRepository
    private val sessionRepository = app.sessionRepository
    private val wrongWordLines = app.wrongWordLineResolver

    /**
     * Re-entry gate for 复习错词: restoring the book's original lines reads
     * assets/Room, so the button's action awaits — claimed synchronously
     * before that first suspension (AGENTS.md), a fast double-tap can only
     * lose here and never start two review rounds.
     */
    private val reviewGate = Mutex()

    /**
     * Session handed over by the launching screen: the prepared lines (slice
     * → shuffle applied) plus the provenance [DictationSessionStore.Session.sourceLabel]
     * for wrong-word marks (a `default_*` built-in list id or a history row
     * id; null for bare-word runs). Consumed once — a ViewModel recreated
     * after an activity kill must not replay or restart the old session.
     */
    private val session: DictationSessionStore.Session = app.dictationSession.take()

    /** Lines of the staged session (initial run; 复习错词 restarts with fewer). */
    private val sessionLines: List<String> = session.lines

    /**
     * The 错词本 source of the current run: the staged session's provenance
     * for the initial run, or null once a 复习错词 round restarts over the
     * book — review marks must not double the original list's count with a
     * stale source (the round's bare words carry no provenance).
     */
    private var runSourceLabel: String? = session.sourceLabel

    /**
     * 组词 phrase pass routing: the active TTS chain (own cache + bounded
     * cold-start fetch) except under YOUDAO — the dict voice cannot serve
     * sentences, so with Youdao the phrase goes straight to the system zh-CN
     * voice with **no network attempt** (a Youdao-only special case; Edge and
     * custom providers speak phrases in their own voices, AGENTS.md).
     */
    private val phraseSpeaker = object : Speaker {
        override suspend fun speak(text: String, lang: String): Boolean =
            if (app.ttsChain.currentSource() == TtsSource.YOUDAO) {
                app.systemSpeaker.speak(text, lang)
            } else {
                app.ttsChain.speak(text, lang)
            }

        override fun stop() {
            // The engine also stops the chain via its word speaker; both
            // stops are idempotent.
            app.ttsChain.stop()
            app.systemSpeaker.stop()
        }
    }

    /** Word passes ride the TTS chain; the 组词 phrase routes via [phraseSpeaker]. */
    val engine = DictationEngine(app.ttsChain, phraseSpeaker = phraseSpeaker)

    private val _intervalSec = MutableStateFlow(MIN_INTERVAL_SEC)
    val intervalSec: StateFlow<Double> = _intervalSec.asStateFlow()

    private val _autoNext = MutableStateFlow(true)
    val autoNext: StateFlow<Boolean> = _autoNext.asStateFlow()

    private val _wrongWords = MutableStateFlow<List<String>>(emptyList())
    val wrongWords: StateFlow<List<String>> = _wrongWords.asStateFlow()

    /** Heads marked during the current run (repeat offenders included). */
    private val runWrongWords = mutableSetOf<String>()

    private val _runWrongCount = MutableStateFlow(0)
    val runWrongCount: StateFlow<Int> = _runWrongCount.asStateFlow()

    private val _markedFlash = MutableStateFlow(false)
    val markedFlash: StateFlow<Boolean> = _markedFlash.asStateFlow()

    private val _ready = MutableStateFlow(false)
    /** False until the persisted settings snapshot has been applied. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Word count of the run in progress (a 复习错词 round restarts with fewer). */
    private val _total = MutableStateFlow(sessionLines.size)
    val total: StateFlow<Int> = _total.asStateFlow()

    private val _elapsedSec = MutableStateFlow<Long?>(null)
    /** Whole seconds of the finished run (score summary); null mid-run. */
    val elapsedSec: StateFlow<Long?> = _elapsedSec.asStateFlow()

    /** Lines of the run in progress (initial session or a review round). */
    private val _activeLines = MutableStateFlow(sessionLines)
    val activeLines: StateFlow<List<String>> = _activeLines.asStateFlow()

    /** Wall-clock start of the current run (init session or a review round). */
    private var runStartedAtMs = 0L

    /** Kind of the run in progress: a fresh dictation or a 复习错词 round. */
    private var runKind = SessionKind.DICTATION

    /** 组词 candidate tables for phrase prefetch (set in init with the engine). */
    private var tables = CompoundTables.EMPTY

    /** 朗读释义 state for the session (dictation screen mirrors the setting). */
    @Volatile
    private var readTranslation = false

    /** Previous countdown emission, for the final-second tick edge. */
    private var prevRemainingMs: Long? = null

    private data class EngineStateView(
        val state: PlayState,
        val finished: Boolean,
        val index: Int,
        val remainingMs: Long?,
    )

    private data class SessionStateView(
        val intervalSec: Double,
        val autoNext: Boolean,
        val wrongWords: List<String>,
        val runWrongCount: Int,
        val markedFlash: Boolean,
        val ready: Boolean,
    )

    val uiState: StateFlow<DictationUiState> = combine(
        combine(engine.state, engine.finished, engine.index, engine.remainingMs) { s, f, i, r ->
            EngineStateView(s, f, i, r)
        },
        combine(
            combine(_intervalSec, _autoNext) { i, a -> i to a },
            combine(_wrongWords, _markedFlash, _ready) { w, f, r -> Triple(w, f, r) },
            _runWrongCount,
        ) { tempo, rest, count ->
            SessionStateView(tempo.first, tempo.second, rest.first, count, rest.second, rest.third)
        },
        _total,
        _elapsedSec,
    ) { engineView, sessionView, totalCount, elapsed ->
        DictationUiState(
            state = engineView.state,
            finished = engineView.finished,
            index = engineView.index,
            total = totalCount,
            remainingMs = engineView.remainingMs,
            intervalSec = sessionView.intervalSec,
            autoNext = sessionView.autoNext,
            wrongWords = sessionView.wrongWords,
            runWrongCount = sessionView.runWrongCount,
            markedFlash = sessionView.markedFlash,
            ready = sessionView.ready,
            elapsedSec = elapsed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), snapshot())

    init {
        // Apply the persisted settings snapshot, seed the 错词本 from Room,
        // then start the session.
        viewModelScope.launch {
            // A failed DataStore read must never block the session: fall back
            // to defaults (AGENTS.md: every async block catches).
            val snapshot = try {
                settings.snapshot()
            } catch (e: Exception) {
                Log.w(TAG, "settings snapshot failed; using defaults", e)
                settings.defaultSnapshot()
            }
            app.systemSpeaker.setSpeechRate(snapshot.speechRate)
            app.ttsChain.setSource(snapshot.ttsSource)
            app.soundEffects.enabled = snapshot.soundEnabled
            readTranslation = snapshot.readTranslation
            engine.setIntervalSec(snapshot.intervalSec)
            engine.setAutoNext(snapshot.autoNext)
            engine.setReadTranslation(snapshot.readTranslation)
            _intervalSec.value = snapshot.intervalSec
            _autoNext.value = snapshot.autoNext
            // 组词 tables load off the main thread before the session starts
            // (first lookup parses compounds.json, then it is cached forever).
            // Unavailable tables degrade to the meaning-column fallback.
            try {
                tables = app.compoundRepository.tables()
                engine.setCompoundTables(tables)
            } catch (e: Exception) {
                Log.w(TAG, "compound tables unavailable; 组词 falls back", e)
            }
            // The wrong-word book seeds before the run starts, so the first
            // possible mark (engine must be PLAYING) sees the full book.
            _wrongWords.value = try {
                wrongWordsRepository.observe().first()
            } catch (e: Exception) {
                emptyList()
            }
            _ready.value = true
            beginRun(sessionLines, runSourceLabel)
        }
        // Score summary + completion chime: capture the elapsed time once when
        // the run completes (a review round resets it via beginRun). The same
        // capture records the local stats row (Roadmap #3) — only a run that
        // reached the end of its list is a session; an abandoned run (stop /
        // back-exit) never completes and records nothing.
        viewModelScope.launch {
            engine.finished.collect { finished ->
                if (finished && _elapsedSec.value == null) {
                    val wall = System.currentTimeMillis() - runStartedAtMs
                    val elapsed = maxOf(1L, (wall + 500) / 1000)
                    _elapsedSec.value = elapsed
                    app.soundEffects.playChime()
                    try {
                        sessionRepository.record(
                            startedAt = runStartedAtMs,
                            sourceLabel = runSourceLabel,
                            totalWords = _total.value,
                            wrongCount = _runWrongCount.value,
                            durationSec = elapsed,
                            kind = runKind,
                        )
                    } catch (e: Exception) {
                        // Stats must never disturb the finish card; a failed
                        // write just loses one row.
                        Log.w(TAG, "session record failed", e)
                    }
                }
            }
        }
        // Watch tick on the final second of each countdown (upstream edge:
        // crossing from > 1000 ms left into ≤ 1000 ms).
        viewModelScope.launch {
            engine.remainingMs.collect { remaining ->
                val prev = prevRemainingMs
                prevRemainingMs = remaining
                // Tick on entering the final second: crossing from > 1000 ms
                // left into ≤ 1000 ms. prev == null covers the seed emission
                // of a 1.0 s countdown (the minimum interval) — its first
                // emission IS the final second, so no >1000 crossing exists.
                if (remaining != null && (prev == null || prev > 1000L) && remaining <= 1000L) {
                    app.soundEffects.playTick()
                }
            }
        }
        // Background audio prefetch: on every word boundary warm the current
        // word, the next word and the English gloss — the chain only plays
        // ready-cached clips, so by the next countdown the Youdao voice is in.
        viewModelScope.launch {
            combine(engine.index, engine.state) { index, state -> index to state }
                .distinctUntilChanged()
                .collect { (index, state) ->
                    if (state == PlayState.PLAYING && !engine.finished.value) {
                        prefetchAround(index)
                    }
                }
        }
    }

    // ------------------------------------------------------------- controls

    /**
     * Warm the audio cache around [index] (upstream `prefetchWordAudio` in
     * the speak phase): the current line, the next line, and the current
     * line's meaning pass — the 组词 phrase of a single CJK char (only under
     * EDGE/CUSTOM, where the phrase rides the chain; under YOUDAO the phrase
     * must never touch the network) or the English gloss when 朗读释义 is on.
     */
    private fun prefetchAround(index: Int) {
        val lines = _activeLines.value
        val current = lines.getOrNull(index) ?: return
        prefetchEntry(current)
        lines.getOrNull(index + 1)?.let(::prefetchEntry)
        if (isCjkEntry(current)) {
            val head = speakTextFromEntry(current)
            if (head.length == 1) {
                val phrase = cjkWordSpeech(current, tables, lines)
                if (phrase.isNotEmpty() && app.ttsChain.currentSource() != TtsSource.YOUDAO) {
                    prefetch(phrase, "zh-CN")
                }
            }
        } else if (readTranslation) {
            val gloss = speakableMeaning(parseWordLine(current).meaning)
            if (gloss.isNotEmpty()) prefetch(gloss, "zh-CN")
        }
    }

    /** Speakable headword of [line] (strips `= you are` suffixes) → prefetch. */
    private fun prefetchEntry(line: String) {
        val head = speakTextFromEntry(line)
        if (head.isNotEmpty()) {
            val lang = if (isCjkEntry(line)) "zh-CN" else "en-US"
            prefetch(head, lang)
        }
    }

    private fun prefetch(text: String, lang: String) {
        viewModelScope.launch {
            try {
                app.ttsChain.prefetch(text, lang)
            } catch (e: Exception) {
                // Prefetch must never disturb the session; the chain's own
                // cache-miss fallback covers speech.
            }
        }
    }

    /**
     * Start a run (initial session or 复习错词 round) and reset its stats.
     * [sourceLabel] is the run's wrong-word provenance (null for review
     * rounds — see [runSourceLabel]); [kind] labels the recorded stats row.
     */
    private fun beginRun(
        runLines: List<String>,
        sourceLabel: String?,
        kind: SessionKind = SessionKind.DICTATION,
    ) {
        runStartedAtMs = System.currentTimeMillis()
        runKind = kind
        _elapsedSec.value = null
        runWrongWords.clear()
        _runWrongCount.value = 0
        _total.value = runLines.size
        _activeLines.value = runLines
        runSourceLabel = sourceLabel
        // A new run (再听一遍 / 复习错词) invalidates any 拍照批改 pending from
        // the previous finish card — including its crop overlay.
        closeGradePane()
        engine.start(runLines)
    }

    fun togglePlay() {
        if (engine.state.value == PlayState.PLAYING) engine.pause()
        else if (engine.state.value == PlayState.PAUSED) engine.resume()
    }

    fun stop() = engine.stop()

    fun skipToNext() = engine.skipToNext()

    fun goToPrevious() = engine.goToPrevious()

    fun onIntervalChange(sec: Double) {
        val clamped = sec.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC)
        _intervalSec.value = clamped
        engine.setIntervalSec(clamped) // live mid-countdown
        viewModelScope.launch { settings.setIntervalSec(clamped) }
    }

    fun onAutoNextChange(on: Boolean) {
        _autoNext.value = on
        engine.setAutoNext(on) // live hold/restart
        viewModelScope.launch { settings.setAutoNext(on) }
    }

    // --------------------------------------------------------- wrong words

    /**
     * 标记错词 for the current word (speakable headword, deduped book). The
     * press always counts as a miss of the current run — a headword already
     * sitting in the book (marked in an earlier session) still shows up in
     * the score — but is added to the book once only.
     */
    fun markCurrentWrong() {
        val ui = uiState.value
        if (!ui.isActive || ui.index >= ui.total) return
        val head = speakTextFromEntry(_activeLines.value.getOrNull(ui.index) ?: return)
        if (head.isEmpty()) return
        val firstMarkThisRun = runWrongWords.add(head)
        if (firstMarkThisRun) {
            _runWrongCount.value = _runWrongCount.value + 1
            // The book row is written once per run even when the headword is
            // already booked — that write is what bumps its error count across
            // runs (a booked word must not short-circuit it). A 复习错词 round
            // is a re-check of words the book already holds, not a fresh run:
            // counting its presses would inflate every count on each pass, so
            // it writes nothing (its words are in the book by construction).
            if (runKind == SessionKind.DICTATION) {
                viewModelScope.launch {
                    try {
                        // The run's provenance feeds the book row's source.
                        wrongWordsRepository.add(head, runSourceLabel)
                    } catch (e: Exception) {
                        // Persistence must never break dictation; the session
                        // list still carries the mark for the finish/review
                        // flow.
                    }
                }
            }
        }
        if (head !in _wrongWords.value) {
            _wrongWords.value = _wrongWords.value + head
        }
        _markedFlash.value = true
        Haptics.notifyWarning(getApplication()) // alice notifyWarning parity
        viewModelScope.launch {
            delay(MARKED_FLASH_MS)
            _markedFlash.value = false
        }
    }

    /** Remove one word from the 错词本 (finish card chip tap). */
    fun removeWrongWord(word: String) {
        if (word !in _wrongWords.value) return
        _wrongWords.value = _wrongWords.value - word
        viewModelScope.launch {
            try {
                wrongWordsRepository.remove(word)
            } catch (e: Exception) {
                // Best effort; the book is reseeded on the next session.
            }
        }
    }

    /** Empty the 错词本. */
    fun clearWrongWords() {
        if (_wrongWords.value.isEmpty()) return
        _wrongWords.value = emptyList()
        viewModelScope.launch {
            try {
                wrongWordsRepository.clear()
            } catch (e: Exception) {
                // Best effort.
            }
        }
    }

    /**
     * Copy the 错词本 to the clipboard as one word per line — pasteable back
     * into the Home input to start a new dictation. Returns the copied count
     * (0 when the book is empty; nothing is written then).
     */
    fun exportWrongWords(): Int {
        val book = _wrongWords.value
        if (book.isEmpty()) return 0
        val clipboard =
            app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("错词", book.joinToString("\n")))
        return book.size
    }

    /**
     * 再听一遍 (Roadmap #7): replay the run that just finished. Its lines are
     * the prepared ones (起始序号 slice + 随机顺序 already applied), so the
     * replay keeps this run's words and order — nothing is re-prepared and no
     * history row is written. It is a fresh dictation run: marks count like
     * any other and the provenance of the run it replays is kept.
     */
    fun replayRun() {
        val lines = _activeLines.value
        if (lines.isEmpty()) return
        beginRun(lines, runSourceLabel)
    }

    // ----------------------------------------------------- 拍照批改 (grading)

    /**
     * Re-entry gate for the 拍照批改 vision call: claimed synchronously before
     * the first suspension (AGENTS.md) so a fast double-tap cannot start two
     * recognitions over one picture.
     */
    private val gradeGate = Mutex()

    private var gradeCropJob: Job? = null

    /** Crop session id: bumped on start/close so a stale decode dies. */
    private var gradeCropSession = 0

    /** dataUrl + lang of the last compressed sheet — the 重试 target. */
    private var lastGradeRun: Pair<String, OcrLang>? = null

    private val _gradePane = MutableStateFlow(false)
    /** True while the 拍照批改 pane replaces the score card. */
    val gradePane: StateFlow<Boolean> = _gradePane.asStateFlow()

    private val _cropBitmap = MutableStateFlow<Bitmap?>(null)
    /** Answer-sheet source shown in the crop overlay (decode in flight = null). */
    val cropBitmap: StateFlow<Bitmap?> = _cropBitmap.asStateFlow()

    private val _cropLoading = MutableStateFlow(false)
    /** True while the picked answer-sheet photo is being decoded. */
    val cropLoading: StateFlow<Boolean> = _cropLoading.asStateFlow()

    private val _gradeBusy = MutableStateFlow(false)
    /** True while the vision call recognizes the answer sheet. */
    val gradeBusy: StateFlow<Boolean> = _gradeBusy.asStateFlow()

    private val _gradePhase = MutableStateFlow("")
    /** In-flight progress copy ("处理图片中…" / "识别中…") for the 批改 pane. */
    val gradePhase: StateFlow<String> = _gradePhase.asStateFlow()

    private val _gradeError = MutableStateFlow<String?>(null)
    /** Terminal 批改 failure (Chinese); cleared by the next attempt or [closeGradePane]. */
    val gradeError: StateFlow<String?> = _gradeError.asStateFlow()

    private val _gradeResult = MutableStateFlow<GradeResult?>(null)
    /** The machine's judgement of the photographed sheet (null = nothing read yet). */
    val gradeResult: StateFlow<GradeResult?> = _gradeResult.asStateFlow()

    private val _gradeSelected = MutableStateFlow<Set<Int>>(emptySet())
    /** Indices into [GradeResult.items] ticked for the 错词本 — the confirmed subset. */
    val gradeSelected: StateFlow<Set<Int>> = _gradeSelected.asStateFlow()

    private val _gradeRetryable = MutableStateFlow(false)
    /** True when the last sheet reached the network — 重试 can re-run it. */
    val gradeRetryable: StateFlow<Boolean> = _gradeRetryable.asStateFlow()

    private val _gradeNotice = MutableStateFlow<String?>(null)
    /** One-shot confirmation text, consumed by the screen ([clearGradeNotice]). */
    val gradeNotice: StateFlow<String?> = _gradeNotice.asStateFlow()

    /**
     * Open the 拍照批改 pane. The recognition language follows this run's own
     * list ([isCjkRun]) — a 汉字/词语 run needs the Chinese prompt — so there
     * is no language picker here.
     */
    fun openGradePane() {
        _gradePane.value = true
    }

    /**
     * Leave the pane, dropping the picture and the reading with it: a stale
     * judgement must never sit under a later score card, so the next entry
     * always starts from a fresh photo.
     */
    fun closeGradePane() {
        _gradePane.value = false
        _gradeResult.value = null
        _gradeSelected.value = emptySet()
        _gradeError.value = null
        _gradePhase.value = ""
        _gradeRetryable.value = false
        lastGradeRun = null
        cancelCrop()
    }

    /** Drop the crop overlay without touching the reading behind it. */
    fun cancelCrop() {
        gradeCropSession++
        gradeCropJob?.cancel()
        gradeCropJob = null
        _cropLoading.value = false
        _cropBitmap.value?.recycle()
        _cropBitmap.value = null
    }

    fun clearGradeNotice() {
        _gradeNotice.value = null
    }

    /**
     * Decode a picked/captured answer sheet for the 选定识别区域 step — the
     * same crop overlay as 拍照识词, because a phone photo of a notebook needs
     * its region picked or the vision model reads the desk around it.
     */
    fun startCrop(uri: Uri) {
        cancelCrop()
        val session = ++gradeCropSession
        _gradeError.value = null
        _cropLoading.value = true
        gradeCropJob = viewModelScope.launch {
            val decoded = app.ocrService.decodeCropSource(uri)
            if (session != gradeCropSession) {
                decoded?.recycle()
                return@launch
            }
            _cropLoading.value = false
            if (decoded == null) {
                _gradeError.value = "读取图片失败，请重新拍摄或选择"
            } else {
                _cropBitmap.value = decoded
            }
        }
    }

    /**
     * Recognize the cropped answer sheet and judge it against this run's lines
     * ([gradeAnswers]). The verdicts are a proposal: the pane reviews them and
     * only [confirmGrade] writes to the 错词本.
     */
    fun confirmCrop(rect: NormalizedRect) {
        val source = _cropBitmap.value ?: return
        _cropBitmap.value = null
        viewModelScope.launch {
            if (!gradeGate.tryLock()) {
                source.recycle()
                return@launch
            }
            var owned: Bitmap? = source
            try {
                _gradeBusy.value = true
                _gradeError.value = null
                _gradeRetryable.value = false
                if (app.ocrService.config() == null) {
                    _gradeError.value = "请先在设置中配置 OCR 服务（需自备 API Key）"
                    return@launch
                }
                val lines = _activeLines.value
                if (lines.isEmpty()) {
                    _gradeError.value = "没有可批改的词表"
                    return@launch
                }
                _gradePhase.value = OCR_PROGRESS_COMPRESSING
                val dataUrl = app.ocrService.cropToDataUrl(source, rect)
                if (dataUrl == null) {
                    _gradeError.value = "读取图片失败，请重新拍摄或选择"
                    return@launch
                }
                // The crop consumed the source's pixels; the encode made its
                // own copy, so the decode can go now (owned tracks the recycle).
                owned = null
                source.recycle()
                val lang = if (isCjkRun(lines)) OcrLang.CHINESE else OcrLang.ENGLISH
                lastGradeRun = dataUrl to lang
                _gradeRetryable.value = true
                recognizeSheet(dataUrl, lang)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A recognition failure must never crash the finished screen.
                Log.w(TAG, "answer-sheet OCR failed", e)
                _gradeError.value = "识别失败，请重试"
            } finally {
                owned?.recycle()
                _gradeBusy.value = false
                _gradePhase.value = ""
                gradeGate.unlock()
            }
        }
    }

    /** Re-run the last recognition against the same picture (after an error). */
    fun retryGrade() {
        val last = lastGradeRun ?: return
        viewModelScope.launch {
            if (!gradeGate.tryLock()) return@launch
            try {
                _gradeBusy.value = true
                _gradeError.value = null
                _gradePhase.value = OCR_PROGRESS_RECOGNIZING
                recognizeSheet(last.first, last.second)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "answer-sheet OCR retry failed", e)
                _gradeError.value = "识别失败，请重试"
            } finally {
                _gradeBusy.value = false
                _gradePhase.value = ""
                gradeGate.unlock()
            }
        }
    }

    /** One vision call over an already-compressed sheet, then the judgement. */
    private suspend fun recognizeSheet(dataUrl: String, lang: OcrLang) {
        when (val outcome = app.ocrService.recognizeAnswers(dataUrl, lang)) {
            is OcrOutcome.Success -> {
                val result = gradeAnswers(_activeLines.value, parseWords(outcome.linesText))
                _gradeResult.value = result
                _gradeSelected.value = result.defaultSelection()
            }

            is OcrOutcome.Error -> _gradeError.value = outcome.message
        }
    }

    /** Tick/untick one judged answer (its index in [GradeResult.items]). */
    fun toggleGradeSelected(index: Int) {
        val result = _gradeResult.value ?: return
        if (index !in result.items.indices) return
        _gradeSelected.value = if (index in _gradeSelected.value) {
            _gradeSelected.value - index
        } else {
            _gradeSelected.value + index
        }
    }

    /**
     * Write the confirmed answers into the 错词本 — the only path from a photo
     * to the book, and the reason the machine's verdicts are a proposal. The
     * score card updates immediately (the confirmed words are misses of this
     * run, exactly as a manual 标记错词 would be); the write bumps each head
     * once with this run's provenance. A 复习错词 round writes nothing, since
     * it re-checks words the book already holds.
     */
    fun confirmGrade() {
        val result = _gradeResult.value ?: return
        val selected = _gradeSelected.value
        val words = result.items
            .filterIndexed { index, _ -> index in selected }
            .mapNotNull { it.expected }
            .distinct()
        if (words.isEmpty()) {
            _gradeNotice.value = "没有勾选任何错词"
            return
        }
        val fresh = words.filter { it !in runWrongWords }
        runWrongWords.addAll(words)
        if (fresh.isNotEmpty()) {
            _runWrongCount.value = _runWrongCount.value + fresh.size
            _wrongWords.value = _wrongWords.value + fresh.filter { it !in _wrongWords.value }
            if (runKind == SessionKind.DICTATION) {
                viewModelScope.launch {
                    try {
                        fresh.forEach { wrongWordsRepository.add(it, runSourceLabel) }
                    } catch (e: Exception) {
                        // Persistence must never break the finish card.
                    }
                }
            }
        }
        _gradeNotice.value = "已记入错词本 ${words.size} 个词"
        closeGradePane()
    }

    /**
     * 复习错词: re-run a dictation round over exactly the wrong set, each mark
     * restored to its original word line — this run's own lines first, else
     * the list the mark's `sourceLabel` points back at (built-in asset list or
     * the history row's text, Roadmap #7), else the bare headword for marks
     * whose source was never known or is gone. The review round is a re-check,
     * not a fresh source: its marks carry no provenance so the original list's
     * count does not double on every review pass.
     */
    fun reviewWrongWords() {
        if (!reviewGate.tryLock()) return
        viewModelScope.launch {
            try {
                // The run in progress is the best source for its own words; it
                // covers bare-word sessions whose lines exist nowhere else.
                val preferred = _activeLines.value
                val lines = try {
                    wrongWordLines.linesFor(wrongWordsRepository.observeMarks().first(), preferred)
                } catch (e: Exception) {
                    // Room unavailable: degrade to the book held in memory,
                    // resolved against this run's lines (the pre-sources
                    // behavior) rather than losing the button.
                    _wrongWords.value.map { word -> findLineByHeadword(preferred, word) ?: word }
                }
                if (lines.isEmpty()) return@launch
                beginRun(lines, null, kind = SessionKind.REVIEW)
            } finally {
                reviewGate.unlock()
            }
        }
    }

    private fun snapshot(): DictationUiState = DictationUiState(
        state = engine.state.value,
        finished = false,
        index = 0,
        total = sessionLines.size,
        remainingMs = null,
        intervalSec = MIN_INTERVAL_SEC,
        autoNext = true,
        wrongWords = emptyList(),
        runWrongCount = 0,
        markedFlash = false,
        ready = false,
        elapsedSec = null,
    )

    override fun onCleared() {
        cancelCrop() // reclaim the answer-sheet decode, if any
        engine.dispose() // leaving the screen stops playback
    }

    companion object {
        private const val TAG = "DictationViewModel"
        private const val MARKED_FLASH_MS = 400L
    }
}
