package org.yangtse.hearwrite.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// One home for the numbers the app shows as text. These used to be private
// per-screen helpers with silently different rules: the 间隔 readout printed
// `7.0s` on a screen whose countdown announces `剩余 8 秒` and whose score card
// reads `用时 3 分 20 秒`; a 75-minute run rendered `75 分 30 秒` on the finish
// card and `1 小时 15 分` on 听写统计; and two copies of the record timestamp
// drifted apart. The rules are now single-sourced here.

/** Trend-axis day label: compact, no year — the window is 14 days. */
private val DAY_LABEL = DateTimeFormatter.ofPattern("M/d")

/** Record timestamps within the current year; the year returns when it differs. */
private val STAMP_LABEL = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val STAMP_LABEL_WITH_YEAR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * The 听写间隔 stepper readout: `7 秒` / `7.5 秒` — the same unit the countdown
 * and the score card speak, so one screen never mixes `7.0s` with `剩余 8 秒`.
 * The step is 0.5 s, so the fraction is only shown when it is non-zero.
 */
fun formatInterval(sec: Double): String =
    if (sec % 1.0 == 0.0) "${sec.toInt()} 秒" else "${String.format(Locale.ROOT, "%.1f", sec)} 秒"

/**
 * Elapsed-time readout, used by both the finish card (用时) and 听写统计
 * (累计用时 / each run): `45 秒`, `3 分 20 秒`, `1 小时 15 分`. Seconds drop
 * once hours appear — a long run is never reported to the second.
 */
fun formatDuration(sec: Long): String = when {
    sec >= 3600 -> "${sec / 3600} 小时 ${sec % 3600 / 60} 分"
    sec >= 60 -> "${sec / 60} 分 ${sec % 60} 秒"
    else -> "$sec 秒"
}

/** Trend-axis tick. */
fun formatDay(date: LocalDate): String = DAY_LABEL.format(date)

/**
 * Record-row timestamp (`错 2 次 · 最近 09-11 20:10`, `09-11 20:10 · 听写`).
 * 错词本 and 听写记录 are both unbounded, so the year is restored as soon as
 * the stamp is not from the current year — otherwise a two-year-old run is
 * indistinguishable from yesterday's.
 */
fun formatStamp(epochMs: Long, today: LocalDate = LocalDate.now()): String {
    val time = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
    val label = if (time.toLocalDate().year == today.year) STAMP_LABEL else STAMP_LABEL_WITH_YEAR
    return label.format(time)
}

/** Percentage for the 正确率 figures: `86%`. */
fun formatPercent(rate: Double): String = String.format(Locale.US, "%.0f%%", rate * 100)
