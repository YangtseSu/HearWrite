package org.yangtse.hearwrite.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Trend window of the 听写统计 page (days, including today). */
const val TREND_DAYS = 14

/** Kind of a recorded dictation run. */
enum class SessionKind(val wire: String) {
    /** A normal run over a word list (Home draft / 词库 preview). */
    DICTATION("dictation"),

    /** A 复习错词 round re-run over exactly the wrong-word book. */
    REVIEW("review");

    companion object {
        /** Unknown wire values degrade to [DICTATION] — a stored row from a
         *  future/older build is still a real run, never dropped. */
        fun fromWire(value: String): SessionKind =
            entries.firstOrNull { it.wire == value } ?: DICTATION
    }
}

/**
 * One completed dictation run (Roadmap #3 听写统计). [startedAt] is wall-clock
 * ms of the run start; [durationSec] is the wall-clock length the finish card
 * shows (pauses included). [wrongCount] counts the run's own marks — not the
 * size of the 错词本, which also holds words from earlier sessions.
 *
 * A run is only recorded when the engine reached the end of its list; an
 * abandoned (stopped/back-exited) session leaves no row.
 */
data class DictationSession(
    val id: Long = 0L,
    val startedAt: Long,
    val sourceLabel: String?,
    val totalWords: Int,
    val wrongCount: Int,
    val durationSec: Long,
    val kind: SessionKind = SessionKind.DICTATION,
) {
    /** Words that were not marked wrong (never negative). */
    val correctCount: Int get() = (totalWords - wrongCount).coerceAtLeast(0)

    /** Local calendar day the run started on. */
    fun day(zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()
}

/** One calendar day of the trend window; days without a run are zero rows. */
data class DayStat(
    val date: LocalDate,
    val runs: Int,
    val words: Int,
    val wrong: Int,
)

/** Headline numbers over the whole record. */
data class StatsSummary(
    val runs: Int,
    val dictationRuns: Int,
    val reviewRuns: Int,
    val words: Int,
    val wrong: Int,
    val durationSec: Long,
    /** Distinct local calendar days with at least one run (打卡天数). */
    val studyDays: Int,
    /** Current consecutive-day streak ending today (or yesterday). */
    val streakDays: Int,
) {
    /** Share of words marked wrong across all runs, 0.0 when nothing recorded. */
    val wrongRate: Double get() = if (words == 0) 0.0 else wrong.toDouble() / words
}

/**
 * Aggregate [sessions] into the page's headline numbers. [today] is passed in
 * rather than read from the clock so the result is a pure function of its
 * inputs; [zone] decides which local day each run belongs to.
 */
fun summarize(
    sessions: List<DictationSession>,
    zone: ZoneId,
    today: LocalDate,
): StatsSummary {
    val days = sessions.mapTo(mutableSetOf()) { it.day(zone) }
    return StatsSummary(
        runs = sessions.size,
        dictationRuns = sessions.count { it.kind == SessionKind.DICTATION },
        reviewRuns = sessions.count { it.kind == SessionKind.REVIEW },
        words = sessions.sumOf { it.totalWords },
        wrong = sessions.sumOf { it.wrongCount },
        durationSec = sessions.sumOf { it.durationSec },
        studyDays = days.size,
        streakDays = streakDays(days, today),
    )
}

/**
 * Current study streak: consecutive calendar days carrying at least one run,
 * counted back from [today]. A day that has not been studied *yet* does not
 * break the streak — with no run today the count starts at yesterday (the
 * session for today may simply be later), so the streak only breaks once a
 * whole day passes empty.
 */
fun streakDays(days: Set<LocalDate>, today: LocalDate): Int {
    var cursor = if (today in days) today else today.minusDays(1)
    var count = 0
    while (cursor in days) {
        count++
        cursor = cursor.minusDays(1)
    }
    return count
}

/**
 * [days] zero-filled day rows ending at [endDay] (oldest first) — the trend
 * bars. Runs bucket by the local day they started on; days outside the window
 * are ignored. [days] <= 0 yields an empty window.
 */
fun dailyStats(
    sessions: List<DictationSession>,
    zone: ZoneId,
    endDay: LocalDate,
    days: Int,
): List<DayStat> {
    if (days <= 0) return emptyList()
    val start = endDay.minusDays((days - 1).toLong())
    val buckets = HashMap<LocalDate, MutableList<DictationSession>>()
    sessions.forEach { session ->
        val day = session.day(zone)
        if (!day.isBefore(start) && !day.isAfter(endDay)) {
            buckets.getOrPut(day) { mutableListOf() }.add(session)
        }
    }
    return (0 until days).map { offset ->
        val date = start.plusDays(offset.toLong())
        val rows = buckets[date].orEmpty()
        DayStat(
            date = date,
            runs = rows.size,
            words = rows.sumOf { it.totalWords },
            wrong = rows.sumOf { it.wrongCount },
        )
    }
}
