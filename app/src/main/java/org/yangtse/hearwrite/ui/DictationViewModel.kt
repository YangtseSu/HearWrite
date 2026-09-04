package org.yangtse.hearwrite.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.data.Haptics
import org.yangtse.hearwrite.domain.CompoundTables
import org.yangtse.hearwrite.domain.DictationEngine
import org.yangtse.hearwrite.domain.MAX_INTERVAL_SEC
import org.yangtse.hearwrite.domain.MIN_INTERVAL_SEC
import org.yangtse.hearwrite.domain.PlayState
import org.yangtse.hearwrite.domain.Speaker
import org.yangtse.hearwrite.domain.TtsSource
import org.yangtse.hearwrite.domain.cjkWordSpeech
import org.yangtse.hearwrite.domain.isCjkEntry
import org.yangtse.hearwrite.domain.parseWordLine
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
 * 复习错词 (re-run over exactly the wrong set), 导出错词 (clipboard) and
 * remove/clear book management.
 */
class DictationViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HearWriteApplication
    private val settings = app.settingsRepository
    private val wrongWordsRepository = app.wrongWordsRepository

    /** Lines handed over by the launching screen (slice → shuffle applied).
     *  Consumed once — a ViewModel recreated after an activity kill must not
     *  replay or restart the old session. */
    val lines: List<String> = app.dictationSession.take()

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
    private val _total = MutableStateFlow(lines.size)
    val total: StateFlow<Int> = _total.asStateFlow()

    private val _elapsedSec = MutableStateFlow<Long?>(null)
    /** Whole seconds of the finished run (score summary); null mid-run. */
    val elapsedSec: StateFlow<Long?> = _elapsedSec.asStateFlow()

    /** Lines of the run in progress (initial session or a review round). */
    private val _activeLines = MutableStateFlow(lines)
    val activeLines: StateFlow<List<String>> = _activeLines.asStateFlow()

    /** Wall-clock start of the current run (init session or a review round). */
    private var runStartedAtMs = 0L

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
            beginRun(lines)
        }
        // Score summary + completion chime: capture the elapsed time once when
        // the run completes (a review round resets it via beginRun).
        viewModelScope.launch {
            engine.finished.collect { finished ->
                if (finished && _elapsedSec.value == null) {
                    val wall = System.currentTimeMillis() - runStartedAtMs
                    _elapsedSec.value = maxOf(1L, (wall + 500) / 1000)
                    app.soundEffects.playChime()
                }
            }
        }
        // Watch tick on the final second of each countdown (upstream edge:
        // crossing from > 1000 ms left into ≤ 1000 ms).
        viewModelScope.launch {
            engine.remainingMs.collect { remaining ->
                val prev = prevRemainingMs
                prevRemainingMs = remaining
                if (prev != null && remaining != null && prev > 1000L && remaining <= 1000L) {
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

    /** Start a run (initial session or 复习错词 round) and reset its stats. */
    private fun beginRun(runLines: List<String>) {
        runStartedAtMs = System.currentTimeMillis()
        _elapsedSec.value = null
        runWrongWords.clear()
        _runWrongCount.value = 0
        _total.value = runLines.size
        _activeLines.value = runLines
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
        if (runWrongWords.add(head)) {
            _runWrongCount.value = _runWrongCount.value + 1
        }
        if (head in _wrongWords.value) return
        _wrongWords.value = _wrongWords.value + head
        _markedFlash.value = true
        Haptics.notifyWarning(getApplication()) // alice notifyWarning parity
        viewModelScope.launch {
            try {
                wrongWordsRepository.add(head)
            } catch (e: Exception) {
                // Persistence must never break dictation; the session list
                // still carries the mark for the finish/review flow.
            }
        }
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
     * 复习错词: re-run a dictation round over exactly the wrong set, restoring
     * the enriched session line for each headword when it is still present in
     * the current word list (upstream `handleRetryWrong`).
     */
    fun reviewWrongWords() {
        val book = _wrongWords.value
        if (book.isEmpty()) return
        val reviewLines = book.map { word ->
            lines.firstOrNull { speakTextFromEntry(it) == word } ?: word
        }
        beginRun(reviewLines)
    }

    private fun snapshot(): DictationUiState = DictationUiState(
        state = engine.state.value,
        finished = false,
        index = 0,
        total = lines.size,
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
        engine.dispose() // leaving the screen stops playback
    }

    companion object {
        private const val TAG = "DictationViewModel"
        private const val MARKED_FLASH_MS = 400L
    }
}
