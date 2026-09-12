package org.yangtse.hearwrite.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Hoisted so the list-row word head can be derived without re-allocating it. */
private val BodyLargeTextStyle = TextStyle(
    fontSize = 17.sp,
    lineHeight = 27.sp,
    fontWeight = FontWeight.Normal,
    letterSpacing = 0.sp,
)

// Type scale for a dictation trainer: the dictated word and the countdown
// seconds sit two levels above everything else; Chinese body text gets a
// ~1.6 line height for comfortable reading; section titles separate by
// tracking + weight, never by size.
//
// Every level the screens actually use is defined here. Material's defaults
// are metric-tuned for Latin text: their 1.2–1.4 line heights crowd stacked
// 汉字, and their labelSmall floor (11sp) is below the comfortable size for
// CJK annotations. Overriding only a handful of levels (as this file used to)
// left 85% of the app's text on those defaults, so the custom scale was
// invisible on exactly the dense Chinese screens it was written for.
val AppTypography = Typography(
    // The dictated word on the dial.
    displayMedium = TextStyle(
        fontSize = 40.sp,
        lineHeight = 52.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    // Countdown seconds readout under the dial.
    displaySmall = TextStyle(
        fontSize = 34.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    // Page and stat-figure headings.
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    // Panel titles, the interval readout, record titles.
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
    // Section titles (单词列表, 外观, 发音来源…): small, semibold, tracked.
    titleSmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    ),
    // Chinese body copy (settings rows, meanings, list rows).
    bodyLarge = BodyLargeTextStyle,
    // Secondary body copy: hints, subtitles, sheet rows.
    bodyMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    // Tertiary copy: supporting lines, the 批改 answer evidence.
    bodySmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    // Verdict labels and other emphatic short labels.
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    ),
    // Pills and badges (识别中…, 听写中, slot counters).
    labelMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    ),
    // Dense annotations: trend-axis ticks, 存疑 notes, word-count badges.
    labelSmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    ),
)

/**
 * List-row word head — the headword in 首页 展示态, the 词库 preview and the
 * 批改页 expected-word column. Three call sites hand-rolled `bodyLarge` plus a
 * weight; this is that style, once. Allocated once, not per read.
 */
val Typography.wordHead: TextStyle
    get() = WordHeadTextStyle

private val WordHeadTextStyle = BodyLargeTextStyle.copy(fontWeight = FontWeight.SemiBold)
