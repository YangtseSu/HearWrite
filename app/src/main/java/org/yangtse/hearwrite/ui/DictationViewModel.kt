package org.yangtse.hearwrite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.domain.DictationEngine
import org.yangtse.hearwrite.domain.MAX_INTERVAL_SEC
import org.yangtse.hearwrite.domain.MIN_INTERVAL_SEC
import org.yangtse.hearwrite.domain.PlayState
import org.yangtse.hearwrite.domain.entryToLine
import org.yangtse.hearwrite.domain.parseWordLine
import org.yangtse.hearwrite.domain.speakTextFromEntry

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
    val markedFlash: Boolean,
    val ready: Boolean,
) {
    val isActive: Boolean get() = state == PlayState.PLAYING || state == PlayState.PAUSED
}

/**
 * Owns the per-session [DictationEngine] (its own scope, disposed with the
 * ViewModel) plus the session settings. Wrong-word marking is in-memory for
 * Phase 4 (Room persistence and the review flow land in Phase 6).
 */
class DictationViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HearWriteApplication
    private val settings = app.settingsRepository

    /** Lines handed over by the launching screen (slice → shuffle applied). */
    val lines: List<String> = app.dictationSession.lines.ifEmpty { emptyList() }

    val engine = DictationEngine(app.systemSpeaker)

    private val _intervalSec = MutableStateFlow(MIN_INTERVAL_SEC)
    val intervalSec: StateFlow<Double> = _intervalSec.asStateFlow()

    private val _autoNext = MutableStateFlow(true)
    val autoNext: StateFlow<Boolean> = _autoNext.asStateFlow()

    private val _wrongWords = MutableStateFlow<List<String>>(emptyList())
    val wrongWords: StateFlow<List<String>> = _wrongWords.asStateFlow()

    private val _markedFlash = MutableStateFlow(false)
    val markedFlash: StateFlow<Boolean> = _markedFlash.asStateFlow()

    private val _ready = MutableStateFlow(false)
    /** False until the persisted settings snapshot has been applied. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

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
        val markedFlash: Boolean,
        val ready: Boolean,
    )

    val uiState: StateFlow<DictationUiState> = combine(
        combine(engine.state, engine.finished, engine.index, engine.remainingMs) { s, f, i, r ->
            EngineStateView(s, f, i, r)
        },
        combine(_intervalSec, _autoNext, _wrongWords, _markedFlash, _ready) { i, a, w, f, r ->
            SessionStateView(i, a, w, f, r)
        },
    ) { engineView, sessionView ->
        DictationUiState(
            state = engineView.state,
            finished = engineView.finished,
            index = engineView.index,
            total = lines.size,
            remainingMs = engineView.remainingMs,
            intervalSec = sessionView.intervalSec,
            autoNext = sessionView.autoNext,
            wrongWords = sessionView.wrongWords,
            markedFlash = sessionView.markedFlash,
            ready = sessionView.ready,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), snapshot())

    init {
        // Apply the persisted settings snapshot, then start the session.
        viewModelScope.launch {
            val snapshot = settings.snapshot()
            app.systemSpeaker.setSpeechRate(snapshot.speechRate)
            engine.setIntervalSec(snapshot.intervalSec)
            engine.setAutoNext(snapshot.autoNext)
            engine.setReadTranslation(snapshot.readTranslation)
            _intervalSec.value = snapshot.intervalSec
            _autoNext.value = snapshot.autoNext
            _ready.value = true
            engine.start(lines)
        }
    }

    // ------------------------------------------------------------- controls

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

    /** 标记错词 for the current word (speakable headword, in-memory set). */
    fun markCurrentWrong() {
        val ui = uiState.value
        if (!ui.isActive || ui.index >= ui.total) return
        val head = speakTextFromEntry(lines[ui.index])
        if (head.isEmpty() || _wrongWords.value.contains(head)) return
        _wrongWords.value = _wrongWords.value + head
        _markedFlash.value = true
        viewModelScope.launch {
            delay(MARKED_FLASH_MS)
            _markedFlash.value = false
        }
    }

    /** Display entry for the current line (headword + pos + meaning hints). */
    fun entryAt(index: Int) = lines.getOrNull(index)?.let(::parseWordLine)

    /** Canonical line export for a later re-dictation of the wrong set. */
    fun lineOf(entry: org.yangtse.hearwrite.domain.WordEntry): String = entryToLine(entry)

    private fun snapshot(): DictationUiState = DictationUiState(
        state = engine.state.value,
        finished = false,
        index = 0,
        total = lines.size,
        remainingMs = null,
        intervalSec = MIN_INTERVAL_SEC,
        autoNext = true,
        wrongWords = emptyList(),
        markedFlash = false,
        ready = false,
    )

    override fun onCleared() {
        engine.dispose() // leaving the screen stops playback
        super.onCleared()
    }

    companion object {
        private const val MARKED_FLASH_MS = 400L
    }
}
