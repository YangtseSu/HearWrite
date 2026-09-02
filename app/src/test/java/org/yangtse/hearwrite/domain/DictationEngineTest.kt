package org.yangtse.hearwrite.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Real 组词 tables (read-only fixture from `data/`, parsed once per class). */
private val REAL_COMPOUNDS: CompoundTables by lazy {
    parseCompoundTables(File("../data/compounds/compounds.json").readText())
}

/** Records every speak attempt (success or failure) on the virtual clock. */
class FakeSpeaker(
    private val clock: () -> Long,
    private val durationMs: Long = 0,
    private val failWhen: (String) -> Boolean = { false },
) : Speaker {
    data class Utterance(val text: String, val lang: String, val atMs: Long, val spoken: Boolean)

    val utterances = mutableListOf<Utterance>()
    var stopCalls = 0

    override suspend fun speak(text: String, lang: String): Boolean {
        // Record at attempt start: atMs is when the utterance began.
        if (failWhen(text)) {
            utterances += Utterance(text, lang, clock(), spoken = false)
            return false
        }
        utterances += Utterance(text, lang, clock(), spoken = true)
        if (durationMs > 0) delay(durationMs)
        return true
    }

    override fun stop() {
        stopCalls++
    }
}

private val EN_WORD = "apple | n. | 苹果"
private val BARE_EN = "banana"
private val CJK_CHAR = "月 | yuè | 月亮"
private val CJK_WORD = "学校"

/** Bounds an expected virtual time (tick-quantized: deadline ± one tick). */
private fun assertAt(expected: Long, actual: Long, tick: Long = 100) {
    assertTrue(
        "expected utterance near $expected ms, was $actual ms",
        actual in expected until expected + tick,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class DictationEngineTest {

    private fun TestScope.engine(
        speaker: FakeSpeaker,
        phraseSpeaker: FakeSpeaker = speaker,
        intervalSec: Double = 7.0,
        autoNext: Boolean = true,
        readTranslation: Boolean = false,
        tables: CompoundTables = REAL_COMPOUNDS,
    ): DictationEngine {
        val engine = DictationEngine(speaker, phraseSpeaker, backgroundScope) {
            testScheduler.currentTime
        }
        engine.setIntervalSec(intervalSec)
        engine.setAutoNext(autoNext)
        engine.setReadTranslation(readTranslation)
        engine.setCompoundTables(tables)
        return engine
    }

    private fun TestScope.utterances(speaker: FakeSpeaker): List<FakeSpeaker.Utterance> =
        speaker.utterances.toList()

    // ------------------------------------------------------------ phase order

    @Test
    fun `word meaning word then interval for english with gloss toggle on`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker, readTranslation = true)

        engine.start(listOf(EN_WORD))
        assertEquals(PlayState.PLAYING, engine.state.value)
        assertEquals(0, engine.index.value)

        advanceTimeBy(2000)
        assertEquals(
            listOf(
                FakeSpeaker.Utterance("apple", "en-US", 0, spoken = true),
                FakeSpeaker.Utterance("苹果", "zh-CN", 700, spoken = true),
                FakeSpeaker.Utterance("apple", "en-US", 1400, spoken = true),
            ),
            utterances(speaker),
        )
        assertFalse(engine.finished.value)

        // Interval deadline 1400 + 7 s = 8400; nothing more until it expires.
        advanceTimeBy(6390)
        assertEquals(3, speaker.utterances.size)
        advanceTimeBy(20)
        assertTrue(engine.finished.value)
        assertEquals(PlayState.IDLE, engine.state.value)
        assertEquals(null, engine.remainingMs.value)
        advanceTimeBy(100_000)
        assertEquals(3, speaker.utterances.size)
    }

    @Test
    fun `gloss toggle off skips the meaning pass`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker)

        engine.start(listOf(EN_WORD))
        advanceTimeBy(2000)
        assertEquals(
            listOf(
                FakeSpeaker.Utterance("apple", "en-US", 0, spoken = true),
                FakeSpeaker.Utterance("apple", "en-US", 700, spoken = true),
            ),
            utterances(speaker),
        )
    }

    @Test
    fun `english word without meaning column never gets a gloss pass`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker, readTranslation = true)

        engine.start(listOf(BARE_EN))
        advanceTimeBy(2000)
        assertEquals(2, speaker.utterances.size)
        assertEquals(BARE_EN, speaker.utterances[0].text)
        assertEquals(0, speaker.utterances[0].atMs)
        assertEquals(700, speaker.utterances[1].atMs)
    }

    @Test
    fun `cjk single char speaks its compound phrase on the phrase speaker`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val phrase = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker, phraseSpeaker = phrase) // 朗读释义 irrelevant for CJK

        engine.start(listOf(CJK_CHAR)) // 月 | yuè | 月亮
        advanceTimeBy(2000)
        // 生字 → 组词 ("月亮的月", zh-CN, phrase speaker) → 生字.
        assertEquals(
            listOf(
                FakeSpeaker.Utterance("月", "zh-CN", 0, spoken = true),
                FakeSpeaker.Utterance("月", "zh-CN", 1400, spoken = true),
            ),
            utterances(speaker),
        )
        assertEquals(
            listOf(FakeSpeaker.Utterance("月亮的月", "zh-CN", 700, spoken = true)),
            phrase.utterances,
        )
    }

    @Test
    fun `function word without a compound gets no phrase pass`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val phrase = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker, phraseSpeaker = phrase)

        engine.start(listOf("的 | de | 好的"))
        advanceTimeBy(2000)
        // NO_COMPOUND_HEADS → cjkWordSpeech "" → the word speaks twice bare.
        assertEquals(2, speaker.utterances.size)
        assertTrue(speaker.utterances.all { it.text == "的" })
        assertEquals(0, phrase.utterances.size)
    }

    @Test
    fun `multi-char cjk word is spoken as-is twice without a meaning pass`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker)

        engine.start(listOf(CJK_WORD))
        advanceTimeBy(2000)
        assertEquals(2, speaker.utterances.size)
        assertTrue(speaker.utterances.all { it.lang == "zh-CN" && it.text == CJK_WORD })
    }

    @Test
    fun `speak timings hold with non-instant speech`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime }, durationMs = 250)
        val engine = engine(speaker)

        engine.start(listOf(BARE_EN))
        // speak1 ends at 250; the 700 ms gap runs from then on.
        advanceTimeBy(4000)
        assertEquals(2, speaker.utterances.size)
        assertEquals(0, speaker.utterances[0].atMs)
        assertAt(950, speaker.utterances[1].atMs) // 250 + 700
    }

    // ------------------------------------------------------------- failures

    @Test
    fun `speak1 failure skips the gap and does not retry`() = runTest {
        // Only the first apple attempt fails; speak2 is the second chance.
        var appleFailuresLeft = 1
        val speaker = FakeSpeaker(
            clock = { testScheduler.currentTime },
            failWhen = {
                if (it != "apple") false
                else {
                    val fail = appleFailuresLeft > 0
                    appleFailuresLeft--
                    fail
                }
            },
        )
        val engine = engine(speaker, readTranslation = true)

        engine.start(listOf(EN_WORD))
        advanceTimeBy(100_000)
        // speak1 fails at 0 → no gap → meaning at 0 → gap → speak2 at 700.
        assertEquals(
            listOf(
                FakeSpeaker.Utterance("apple", "en-US", 0, spoken = false),
                FakeSpeaker.Utterance("苹果", "zh-CN", 0, spoken = true),
                FakeSpeaker.Utterance("apple", "en-US", 700, spoken = true),
            ),
            utterances(speaker),
        )
        assertTrue(engine.finished.value)
    }

    @Test
    fun `meaning pass failure does not block the second word pass`() = runTest {
        val speaker = FakeSpeaker(
            clock = { testScheduler.currentTime },
            failWhen = { it == "苹果" },
        )
        val engine = engine(speaker, readTranslation = true)

        engine.start(listOf(EN_WORD))
        advanceTimeBy(100_000)
        val spoken = speaker.utterances.filter { it.spoken }.map { it.text }
        assertEquals(listOf("apple", "apple"), spoken)
        assertTrue(engine.finished.value)
    }

    // ------------------------------------------------- cancellation / races

    @Test
    fun `stop leaves no stray speech behind`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker)

        engine.start(listOf("a", "b", "c"))
        advanceTimeBy(1000) // a spoken twice, countdown running
        engine.stop()
        assertEquals(PlayState.IDLE, engine.state.value)
        assertFalse(engine.finished.value)

        val countAtStop = speaker.utterances.size
        advanceTimeBy(100_000)
        assertEquals(countAtStop, speaker.utterances.size)
    }

    @Test
    fun `double start - stale run never pollutes the new session`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker)

        engine.start(listOf("A", "A2"))
        advanceTimeBy(1500) // A@0, A2@700, A's countdown running
        engine.start(listOf("B", "B2", "B3"))
        advanceTimeBy(100_000)

        val texts = speaker.utterances.map { it.text }
        // A spoke twice (speak1 @0, speak2 @700) before the restart at 1500.
        assertEquals(listOf("A", "A"), texts.filter { it.startsWith("A") })
        val bTimes = speaker.utterances.filter { it.text.startsWith("B") }.map { it.atMs }
        assertTrue("first B at 1500, was ${bTimes.firstOrNull()}", bTimes.first() == 1500L)
        // 2 (A) + B/B2/B3 each spoken twice = 8; nothing else ever speaks.
        assertEquals(8, speaker.utterances.size)
        assertTrue(engine.finished.value)
    }

    @Test
    fun `pause cancels countdown and resume replays current word from speak1`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker)

        engine.start(listOf("a", "b"))
        advanceTimeBy(1000) // a@0, a@700, countdown running
        engine.pause()
        assertEquals(PlayState.PAUSED, engine.state.value)
        assertEquals(null, engine.remainingMs.value)

        advanceTimeBy(60_000)
        assertEquals(2, speaker.utterances.size)

        val resumedAt = testScheduler.currentTime
        engine.resume()
        assertEquals(PlayState.PLAYING, engine.state.value)
        assertEquals(0, engine.index.value)
        advanceTimeBy(100)
        assertEquals("a", speaker.utterances[2].text)
        assertAt(resumedAt, speaker.utterances[2].atMs)
    }

    @Test
    fun `pause during speak hold still parks and resumes cleanly`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker, autoNext = false)

        engine.start(listOf("a", "b"))
        advanceTimeBy(10_000) // a@0, a@700, then parked after speak2
        assertEquals(PlayState.PLAYING, engine.state.value)
        assertEquals(2, speaker.utterances.size)

        engine.pause()
        assertEquals(PlayState.PAUSED, engine.state.value)
        engine.resume()
        advanceTimeBy(100)
        assertEquals("a", speaker.utterances[2].text)
    }

    // ------------------------------------------------------------- auto-next

    @Test
    fun `auto-next off parks after speak2 and re-enable resumes at interval`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker, autoNext = false)

        engine.start(listOf("a", "b"))
        advanceTimeBy(60_000)
        assertEquals(PlayState.PLAYING, engine.state.value) // parked, not idle
        assertEquals(0, engine.index.value)
        assertEquals(2, speaker.utterances.size)
        assertEquals(null, engine.remainingMs.value)

        val reEnabledAt = testScheduler.currentTime
        engine.setAutoNext(true)
        // Parked since 700; the interval restarts from the re-enable moment.
        advanceTimeBy(6990)
        assertEquals(2, speaker.utterances.size)
        advanceTimeBy(200)
        assertEquals("b", speaker.utterances[2].text)
        assertAt(reEnabledAt + 7000, speaker.utterances[2].atMs)
    }

    @Test
    fun `disabling auto-next mid-countdown cancels it and re-enable restarts full interval`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker, autoNext = true)

        engine.start(listOf("a", "b"))
        advanceTimeBy(1700) // a@0, a@700; countdown to 7700, 6000 ms left
        engine.setAutoNext(false)
        assertEquals(null, engine.remainingMs.value)
        advanceTimeBy(100_000)
        assertEquals(2, speaker.utterances.size) // countdown was cancelled

        // Restart from the re-enable moment, not at the old deadline 7700.
        val reEnabledAt = testScheduler.currentTime
        engine.setAutoNext(true)
        advanceTimeBy(6990)
        assertEquals(2, speaker.utterances.size)
        advanceTimeBy(200)
        assertEquals("b", speaker.utterances[2].text)
        assertAt(reEnabledAt + 7000, speaker.utterances[2].atMs)
    }

    // -------------------------------------------------------- skip and prev

    @Test
    fun `skip advances and previous replays the prior word from speak1`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker)

        engine.start(listOf("a", "b", "c"))
        advanceTimeBy(1000)
        engine.skipToNext()
        advanceTimeBy(50)
        assertEquals(1, engine.index.value)
        assertEquals("b", speaker.utterances.last().text)

        advanceTimeBy(1000) // b spoken twice, interval running
        engine.goToPrevious()
        advanceTimeBy(50)
        assertEquals(0, engine.index.value)
        assertEquals("a", speaker.utterances.last().text)
    }

    @Test
    fun `skip from pause resumes playing`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker)

        engine.start(listOf("a", "b"))
        advanceTimeBy(1000)
        engine.pause()
        engine.skipToNext()
        assertEquals(PlayState.PLAYING, engine.state.value)
        advanceTimeBy(50)
        assertEquals("b", speaker.utterances.last().text)
        assertEquals(1, engine.index.value)
    }

    @Test
    fun `skip past the last word finishes the session`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker)

        engine.start(listOf("a"))
        advanceTimeBy(1000)
        engine.skipToNext()
        assertTrue(engine.finished.value)
        assertEquals(PlayState.IDLE, engine.state.value)
        advanceTimeBy(100_000)
        assertEquals(2, speaker.utterances.size)
    }

    @Test
    fun `starting with an empty list stays idle without finishing`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker)
        engine.start(emptyList())
        assertEquals(PlayState.IDLE, engine.state.value)
        assertFalse(engine.finished.value)
        advanceTimeBy(100_000)
        assertEquals(0, speaker.utterances.size)
    }

    // ---------------------------------------------------- ★ live interval change

    @Test
    fun `interval change mid-countdown restarts the countdown at the new value`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker, intervalSec = 7.0)

        engine.start(listOf("a", "b"))
        advanceTimeBy(1700) // countdown to 7700 started at 700
        assertAt(7700, engine.remainingMs.value!! + testScheduler.currentTime)

        engine.setIntervalSec(2.0)
        // Full restart: new deadline 1700 + 2000 = 3700 (not the old 7700).
        assertAt(3700, engine.remainingMs.value!! + testScheduler.currentTime)

        advanceTimeBy(1990)
        assertEquals(2, speaker.utterances.size) // still the old countdown would end at 7700
        advanceTimeBy(20)
        assertEquals("b", speaker.utterances[2].text)
        assertAt(3700, speaker.utterances[2].atMs)

        // The new interval also governs the rest of the session: b's interval
        // (2 s from 4400) finishes the list at 6400.
        advanceTimeBy(10_000)
        assertTrue(engine.finished.value)
    }

    @Test
    fun `interval change outside the countdown applies to the next one`() = runTest {
        val speaker = FakeSpeaker(clock = { testScheduler.currentTime })
        val engine = engine(speaker, intervalSec = 7.0)

        engine.setIntervalSec(1.0) // before starting
        engine.start(listOf("a", "b"))
        advanceTimeBy(100_000)
        // a@0, a@700; countdown 700 + 1 s → b@1700, 2400; then finish at 3400.
        assertEquals(4, speaker.utterances.size)
        assertAt(1700, speaker.utterances[2].atMs)
        assertAt(2400, speaker.utterances[3].atMs)
        assertTrue(engine.finished.value)
    }
}
