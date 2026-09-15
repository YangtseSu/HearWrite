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

// One date style for the whole app. The 听写统计 screen used to mix an `M/d`
// axis with `MM-dd HH:mm` records — two spellings of the same day side by side.
// Both labels read `9月14日` now, and timestamps append the clock time.
/** Day label (trend axis, bar descriptions, chart readout). */
private val DAY_LABEL = DateTimeFormatter.ofPattern("M月d日")

/** Record timestamps within the current year; the year returns when it differs. */
private val STAMP_LABEL = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val STAMP_LABEL_WITH_YEAR = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")

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
 * Trend-axis tick with its weekday (`9月2日 周三`). The 听写统计 window is two
 * whole weeks, so without the weekday the axis carries no weekly rhythm at all
 * — the reader can see that a gap exists but not that it was the weekend.
 */
private val DAY_WEEKDAY_LABEL =
    DateTimeFormatter.ofPattern("M月d日 EEE", Locale.SIMPLIFIED_CHINESE)

fun formatDayWithWeekday(date: LocalDate): String = DAY_WEEKDAY_LABEL.format(date)

/** Index order of [formatWeekday]: `DayOfWeek.value` is 1 (Monday) … 7 (Sunday). */
private val WEEKDAY_CHARS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * One-character weekday (`一`…`日`) — the trend chart's per-bar axis label. A
 * single character is what fits one of fourteen columns; the full date rides
 * the bar's own description and the tap readout instead.
 */
fun formatWeekday(date: LocalDate): String = WEEKDAY_CHARS[date.dayOfWeek.value - 1]

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
