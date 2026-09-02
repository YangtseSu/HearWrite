package org.yangtse.hearwrite.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** Dictation session states. IDLE = not started or ended. */
enum class PlayState { IDLE, PLAYING, PAUSED }

/** One word's schedule inside the playback loop. */
private enum class WordPhase { SPEAK1, MEANING, SPEAK2, INTERVAL }

/** Pause between the word passes (word → meaning → word), from upstream. */
const val REPEAT_GAP_MS = 700L

/** Countdown tick; short enough that the ring depletes smoothly. */
const val COUNTDOWN_TICK_MS = 50L

private const val LANG_ZH = "zh-CN"
private const val LANG_EN = "en-US"

/**
 * The dictation playback state machine (AGENTS.md "Playback engine").
 *
 * Per word: `speak1` → 700 ms → `speakMeaning` → `speak2` → interval countdown
 * → next word. speakMeaning is the 组词 pass for single CJK chars (Phase 5
 * supplies `cjkWordSpeech`; until then the char itself is spoken — the
 * upstream fallback) and the 中文释义 gloss for English entries only when
 * 朗读释义 is on; multi-char CJK words get no meaning pass.
 *
 * Cancellation contract (each line mirrors a real upstream bug — do not
 * regress, AGENTS.md):
 * - Cancel via [Job.cancel] **plus** a generation counter bumped on every
 *   start/pause/resume/stop/skip/prev; re-check the generation after every
 *   suspension. Never boolean flags.
 * - speak1 failure → no retry and no gap: advance straight to the next phase
 *   (speak2 is the natural second attempt).
 * - Auto-next off: after speak2 the run parks with the session still
 *   PLAYING; re-enabling restarts the interval countdown for the current word.
 * - The interval deadline lives in a mutable field re-read every tick, and
 *   [setIntervalSec] rewrites it mid-countdown, so live interval changes
 *   apply immediately (upstream captured the deadline in a closure and live
 *   changes silently did nothing).
 *
 * The engine runs on its own [SupervisorJob] scope (injected for tests) and
 * speaks through a [Speaker]; it knows nothing about screens or settings
 * storage.
 */
class DictationEngine(
    private val speaker: Speaker,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val scope = scope
    private val _state = MutableStateFlow(PlayState.IDLE)
    val state: StateFlow<PlayState> = _state.asStateFlow()

    private val _index = MutableStateFlow(0)
    /** Index of the word being dictated (never advances past the interval). */
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _remainingMs = MutableStateFlow<Long?>(null)
    /** Milliseconds left in the interval countdown; null when not counting. */
    val remainingMs: StateFlow<Long?> = _remainingMs.asStateFlow()

    private val _finished = MutableStateFlow(false)
    /** True when the end of the list was reached (as opposed to a stop). */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    /** Lines of the active session (raw list lines, parsed per word). */
    private var words: List<String> = emptyList()

    @Volatile
    private var gen = 0
    @Volatile
    private var intervalMs: Long = (DEFAULT_INTERVAL_SEC * 1000).roundToLong()
    @Volatile
    private var autoNext = true
    @Volatile
    private var readTranslation = false

    /** Absolute deadline of the running countdown (see class doc). */
    private val deadline = MutableStateFlow<Long?>(null)
    private var job: Job? = null

    // ------------------------------------------------------------------ API

    /** Start dictating [lines] from the first word. Stops any active run. */
    fun start(lines: List<String>) {
        gen++
        cancelRun()
        speaker.stop()
        words = lines
        _finished.value = false
        _index.value = 0
        clearCountdown()
        if (lines.isEmpty()) {
            _state.value = PlayState.IDLE
            return
        }
        _state.value = PlayState.PLAYING
        startRun(0, WordPhase.SPEAK1)
    }

    /** Pause the current word (resume replays it from speak1, like upstream). */
    fun pause() {
        if (_state.value == PlayState.IDLE) return
        gen++
        cancelRun()
        speaker.stop()
        clearCountdown()
        _state.value = PlayState.PAUSED
    }

    /** Resume a paused session, replaying the current word from speak1. */
    fun resume() {
        if (_state.value != PlayState.PAUSED) return
        gen++
        _state.value = PlayState.PLAYING
        startRun(_index.value, WordPhase.SPEAK1)
    }

    /** Stop playback without finishing; the session is abandoned. */
    fun stop() {
        gen++
        cancelRun()
        speaker.stop()
        clearCountdown()
        _finished.value = false
        _state.value = PlayState.IDLE
    }

    /** Skip to the next word (from paused too — a skip resumes playing). */
    fun skipToNext() {
        if (_state.value == PlayState.IDLE) return
        gen++
        cancelRun()
        speaker.stop()
        clearCountdown()
        val next = _index.value + 1
        if (next >= words.size) {
            // Skipping past the last word ends the session, like upstream.
            _state.value = PlayState.IDLE
            _finished.value = true
            return
        }
        _index.value = next
        _state.value = PlayState.PLAYING
        startRun(next, WordPhase.SPEAK1)
    }

    /** Replay the previous word from speak1. */
    fun goToPrevious() {
        if (_state.value == PlayState.IDLE) return
        gen++
        cancelRun()
        speaker.stop()
        clearCountdown()
        val prev = maxOf(0, _index.value - 1)
        _index.value = prev
        _state.value = PlayState.PLAYING
        startRun(prev, WordPhase.SPEAK1)
    }

    /**
     * Live interval change (AGENTS.md: deadline must be re-read every tick).
     * Outside a countdown it only takes effect at the next one.
     */
    fun setIntervalSec(sec: Double) {
        intervalMs = (sec * 1000).roundToLong()
        if (_state.value == PlayState.PLAYING && deadline.value != null) {
            // Restart the running countdown at the new interval (upstream
            // rewrites the deadline; the tick loop picks it up next tick).
            deadline.value = now() + intervalMs
            _remainingMs.value = intervalMs
        }
    }

    /**
     * Auto-next toggle, live. Disabling mid-countdown cancels it and parks on
     * the current word; re-enabling restarts the interval from scratch.
     */
    fun setAutoNext(on: Boolean) {
        if (autoNext == on) return
        autoNext = on
        if (_state.value != PlayState.PLAYING) return
        if (on) {
            // Post-speak2 hold (or a cancelled countdown): no active run.
            if (job == null || !job!!.isActive) {
                startRun(_index.value, WordPhase.INTERVAL)
            }
        } else if (deadline.value != null) {
            gen++
            cancelRun()
            clearCountdown()
        }
    }

    /** 朗读释义 toggle; read live per word (English gloss pass only). */
    fun setReadTranslation(on: Boolean) {
        readTranslation = on
    }

    /** Release the speaker and stop the run. Call when leaving dictation. */
    fun dispose() {
        gen++
        cancelRun()
        speaker.stop()
        clearCountdown()
        _state.value = PlayState.IDLE
    }

    // ------------------------------------------------------------ internals

    private fun currentRun(myGen: Int): Boolean =
        myGen == gen && _state.value == PlayState.PLAYING

    private fun cancelRun() {
        job?.cancel()
        job = null
    }

    private fun clearCountdown() {
        deadline.value = null
        _remainingMs.value = null
    }

    private fun startRun(fromIndex: Int, fromPhase: WordPhase) {
        val myGen = gen
        cancelRun()
        job = scope.launch {
            try {
                runLoop(myGen, fromIndex, fromPhase)
            } catch (e: CancellationException) {
                // The run was cancelled deliberately (pause/stop/skip/…).
                // Nothing to finish; the controller owns the state.
                throw e
            }
        }
    }

    /** speakMeaning content and voice for a line; null when there is no pass. */
    private fun meaningSpeech(line: String): Pair<String, String>? {
        if (!isCjkEntry(line)) {
            if (!readTranslation) return null
            val gloss = speakableMeaning(parseWordLine(line).meaning)
            return if (gloss.isEmpty()) null else gloss to LANG_ZH
        }
        val head = speakTextFromEntry(line)
        // CJK single char always gets its compound pass (组词, zh-CN). Phase 5
        // plugs in cjkWordSpeech; speaking the char itself is the upstream
        // fallback for chars without a matching compound.
        return if (head.length == 1) head to LANG_ZH else null
    }

    private suspend fun runLoop(myGen: Int, fromIndex: Int, fromPhase: WordPhase) {
        var i = fromIndex
        var phase = fromPhase
        while (true) {
            if (!currentRun(myGen)) return
            if (i >= words.size) {
                _state.value = PlayState.IDLE
                _finished.value = true
                clearCountdown()
                return
            }
            val line = words[i]
            when (phase) {
                WordPhase.SPEAK1, WordPhase.SPEAK2 -> {
                    val first = phase == WordPhase.SPEAK1
                    _index.value = i
                    if (first) clearCountdown()
                    val ok = speaker.speak(speakTextFromEntry(line), wordLang(line))
                    if (!currentRun(myGen)) return
                    if (first) {
                        // speak1 failure: no retry, skip the gap, advance —
                        // speak2 is the natural second attempt.
                        if (ok) {
                            delay(REPEAT_GAP_MS)
                            if (!currentRun(myGen)) return
                        }
                        phase =
                            if (meaningSpeech(line) != null) WordPhase.MEANING
                            else WordPhase.SPEAK2
                    } else {
                        // speak2 done. Auto-next off parks here (session stays
                        // PLAYING); re-enabling restarts at the interval.
                        if (!autoNext) return
                        phase = WordPhase.INTERVAL
                    }
                }

                WordPhase.MEANING -> {
                    val meaning = meaningSpeech(line)
                    if (meaning != null) {
                        speaker.speak(meaning.first, meaning.second)
                        if (!currentRun(myGen)) return
                    }
                    delay(REPEAT_GAP_MS)
                    if (!currentRun(myGen)) return
                    phase = WordPhase.SPEAK2
                }

                WordPhase.INTERVAL -> {
                    if (!countdown(myGen)) return
                    i++
                    phase = WordPhase.SPEAK1
                }
            }
        }
    }

    /** Interval countdown; false when the run was cancelled meanwhile. */
    private suspend fun countdown(myGen: Int): Boolean {
        deadline.value = now() + intervalMs
        _remainingMs.value = intervalMs
        while (true) {
            delay(COUNTDOWN_TICK_MS)
            if (!currentRun(myGen)) return false
            // Re-read the deadline every tick: setIntervalSec rewrites it
            // mid-countdown and the change must take effect immediately.
            val target = deadline.value ?: return false
            val left = target - now()
            if (left <= 0) {
                deadline.value = null
                _remainingMs.value = null
                return true
            }
            _remainingMs.value = left
        }
    }

    /** Voice language of the word itself, by entry kind. */
    private fun wordLang(line: String): String =
        if (isCjkEntry(line)) LANG_ZH else LANG_EN
}
