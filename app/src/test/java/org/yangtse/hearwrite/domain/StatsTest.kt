package org.yangtse.hearwrite.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Locks the 听写统计 aggregation (Roadmap #3): day bucketing, streak rules
 *  and the summary totals, all on a fixed zone + fixed "today". */
class StatsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 9, 10)

    /** A run starting at [time] on [date] in the test zone. */
    private fun session(
        date: LocalDate,
        hour: Int = 20,
        words: Int = 10,
        wrong: Int = 2,
        durationSec: Long = 120,
        kind: SessionKind = SessionKind.DICTATION,
        sourceLabel: String? = null,
    ): DictationSession = DictationSession(
        startedAt = LocalDateTime.of(date, java.time.LocalTime.of(hour, 0))
            .atZone(zone).toInstant().toEpochMilli(),
        sourceLabel = sourceLabel,
        totalWords = words,
        wrongCount = wrong,
        durationSec = durationSec,
        kind = kind,
    )

    @Test
    fun `summarize totals, kinds and wrong rate`() {
        val sessions = listOf(
            session(today, words = 10, wrong = 2, durationSec = 100),
            session(today, words = 20, wrong = 8, durationSec = 200, kind = SessionKind.REVIEW),
            session(today.minusDays(1), words = 10, wrong = 0, durationSec = 60),
        )
        val summary = summarize(sessions, zone, today)

        assertEquals(3, summary.runs)
        assertEquals(2, summary.dictationRuns)
        assertEquals(1, summary.reviewRuns)
        assertEquals(40, summary.words)
        assertEquals(10, summary.wrong)
        assertEquals(360L, summary.durationSec)
        assertEquals(0.25, summary.wrongRate, 1e-9)
        assertEquals(2, summary.studyDays)
        assertEquals(2, summary.streakDays)
    }

    @Test
    fun `empty record summarizes to zeros`() {
        val summary = summarize(emptyList(), zone, today)
        assertEquals(0, summary.runs)
        assertEquals(0, summary.words)
        assertEquals(0.0, summary.wrongRate, 0.0)
        assertEquals(0, summary.streakDays)
        assertEquals(0, summary.studyDays)
    }

    @Test
    fun `streak counts back from today`() {
        val days = setOf(today, today.minusDays(1), today.minusDays(2))
        assertEquals(3, streakDays(days, today))
    }

    @Test
    fun `an empty today keeps the streak alive until the day ends`() {
        val days = setOf(today.minusDays(1), today.minusDays(2))
        assertEquals(2, streakDays(days, today))
    }

    @Test
    fun `a gap of two empty days breaks the streak`() {
        val days = setOf(today.minusDays(2), today.minusDays(3), today.minusDays(4))
        assertEquals(0, streakDays(days, today))
    }

    @Test
    fun `streak ignores future days and double runs`() {
        val days = setOf(today.plusDays(1), today, today.minusDays(1))
        assertEquals(2, streakDays(days, today))
    }

    @Test
    fun `dailyStats zero-fills the window oldest first`() {
        val sessions = listOf(
            session(today, words = 10, wrong = 1),
            session(today, words = 5, wrong = 1, kind = SessionKind.REVIEW),
            session(today.minusDays(3), words = 20, wrong = 0),
            // Outside the window on both ends.
            session(today.minusDays(20), words = 99, wrong = 99),
            session(today.plusDays(1), words = 99, wrong = 99),
        )
        val window = dailyStats(sessions, zone, endDay = today, days = 5)

        assertEquals(5, window.size)
        assertEquals(today.minusDays(4), window.first().date)
        assertEquals(today, window.last().date)

        val last = window.last()
        assertEquals(2, last.runs)
        assertEquals(15, last.words)
        assertEquals(2, last.wrong)

        val threeDaysAgo = window[1]
        assertEquals(today.minusDays(3), threeDaysAgo.date)
        assertEquals(1, threeDaysAgo.runs)
        assertEquals(20, threeDaysAgo.words)
        assertEquals(0, threeDaysAgo.wrong)

        // The three days between (and before) the two run days are empty.
        val empty = window.filter { it.runs == 0 }
        assertEquals(3, empty.size)
        assertTrue(empty.all { it.words == 0 && it.wrong == 0 })
    }

    @Test
    fun `dailyStats buckets by the local day, not the UTC day`() {
        // 2026-09-10T00:30 +08:00 is still 2026-09-09T16:30 UTC.
        val lateNight = DictationSession(
            startedAt = LocalDateTime.of(2026, 9, 10, 0, 30)
                .atZone(zone).toInstant().toEpochMilli(),
            sourceLabel = null,
            totalWords = 3,
            wrongCount = 0,
            durationSec = 30,
        )
        val window = dailyStats(listOf(lateNight), zone, endDay = today, days = 2)
        assertEquals(listOf(today.minusDays(1), today), window.map { it.date })
        assertEquals(1, window.last().runs)
    }

    @Test
    fun `wrongRate never exceeds the recorded words`() {
        val summary = summarize(
            listOf(session(today, words = 4, wrong = 4)),
            zone,
            today,
        )
        assertEquals(1.0, summary.wrongRate, 1e-9)
        assertEquals(4, summary.wrong)
        assertEquals(4, summary.words)
    }
}
