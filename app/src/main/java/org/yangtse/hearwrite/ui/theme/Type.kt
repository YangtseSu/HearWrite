package org.yangtse.hearwrite.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Type scale for a dictation trainer: the dictated word and the countdown
// seconds sit two levels above everything else; Chinese body text gets a
// 1.6 line height for comfortable reading; section titles separate by
// tracking + weight, never by size.
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
    // Chinese body copy (settings rows, hints, meanings).
    bodyLarge = TextStyle(
        fontSize = 17.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    // Section titles (单词列表, 外观, 发音来源…): small, semibold, tracked.
    titleSmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    ),
)
