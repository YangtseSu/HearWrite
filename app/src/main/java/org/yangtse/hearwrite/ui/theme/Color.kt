package org.yangtse.hearwrite.ui.theme

import androidx.compose.ui.graphics.Color

// Light scheme ("paper") — warm paper background, ink text, deep藏青 primary
// anchored on the app-icon blue #1E88E5 (pressed darker for contrast on paper).
val PrimaryLight = Color(0xFF1B5FAA)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFDCE9FA)
val OnPrimaryContainerLight = Color(0xFF0B2E55)
val SecondaryLight = Color(0xFF55606F)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFDDE4F0)
val OnSecondaryContainerLight = Color(0xFF1A2330)
val TertiaryLight = Color(0xFF00696D)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFF9CF1F5)
val OnTertiaryContainerLight = Color(0xFF002021)
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)
val BackgroundLight = Color(0xFFFAF7F1)
val OnBackgroundLight = Color(0xFF1A1D21)
val SurfaceLight = Color(0xFFFAF7F1)
val OnSurfaceLight = Color(0xFF1A1D21)
// Raised "card" tone above the background (settings groups, tiles).
val SurfaceLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFFFFFFF)
val SurfaceContainerLight = Color(0xFFF1ECE1)
val SurfaceContainerHighLight = Color(0xFFE9E2D4)
val SurfaceContainerHighestLight = Color(0xFFDFD7C5)
val SurfaceDimLight = Color(0xFFDCD6C8)
val SurfaceBrightLight = Color(0xFFFAF7F1)
val SurfaceVariantLight = Color(0xFFE4E1D9)
val OnSurfaceVariantLight = Color(0xFF4C463D)
val OutlineLight = Color(0xFF7A766D)
val OutlineVariantLight = Color(0xFFD9D2C3)
val InverseSurfaceLight = Color(0xFF2F2B25)
val InverseOnSurfaceLight = Color(0xFFF5F0E6)
val InversePrimaryLight = Color(0xFFA9CBF5)

// Dark scheme ("ink night") — blue-black background, moon-white text, the
// primary de-saturated a notch so the countdown ring doesn't glare at night.
val PrimaryDark = Color(0xFF8FC3F5)
val OnPrimaryDark = Color(0xFF062F52)
val PrimaryContainerDark = Color(0xFF0E3A5D)
val OnPrimaryContainerDark = Color(0xFFDCE9FA)
val SecondaryDark = Color(0xFFBFC6DC)
val OnSecondaryDark = Color(0xFF293042)
val SecondaryContainerDark = Color(0xFF3F4759)
val OnSecondaryContainerDark = Color(0xFFDAE2F9)
val TertiaryDark = Color(0xFF7FCDD2)
val OnTertiaryDark = Color(0xFF003739)
val TertiaryContainerDark = Color(0xFF004F53)
val OnTertiaryContainerDark = Color(0xFF9CF1F5)
val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
val BackgroundDark = Color(0xFF10151B)
val OnBackgroundDark = Color(0xFFE6E9EF)
val SurfaceDark = Color(0xFF10151B)
val OnSurfaceDark = Color(0xFFE6E9EF)
// Raised "card" tones above the background — hue steps, never shadows.
val SurfaceLowestDark = Color(0xFF0B0F14)
val SurfaceContainerLowDark = Color(0xFF1A212B)
val SurfaceContainerDark = Color(0xFF202932)
val SurfaceContainerHighDark = Color(0xFF2A3440)
val SurfaceContainerHighestDark = Color(0xFF354052)
val SurfaceDimDark = Color(0xFF0B0F14)
val SurfaceBrightDark = Color(0xFF39424D)
val SurfaceVariantDark = Color(0xFF3A4450)
val OnSurfaceVariantDark = Color(0xFFC4C9D1)
val OutlineDark = Color(0xFF8F96A1)
val OutlineVariantDark = Color(0xFF39424D)
// Countdown-ring track on dark surfaces — deliberately darker than
// outlineVariant so the lit arc reads as a "desk lamp circle".
val RingTrackDark = Color(0xFF2A3340)
val InverseSurfaceDark = Color(0xFFE6E9EF)
val InverseOnSurfaceDark = Color(0xFF2E3036)
val InversePrimaryDark = Color(0xFF1B5FAA)

// Semantic accents. These live in HearWriteSemantics (see Theme.kt) so screens
// never branch on isSystemInDarkTheme() themselves.
/** Success/active states: 听写中 pill, 听写完成 check. */
val SuccessLight = Color(0xFF2E7D32)
val SuccessContainerLight = Color(0xFFD9EDDA)
val OnSuccessContainerLight = Color(0xFF0A2E0E)

/** The same states on dark surfaces — #2E7D32 is ~1.9:1 there (invisible). */
val SuccessDark = Color(0xFF81C784)
val SuccessContainerDark = Color(0xFF1E4A24)
val OnSuccessContainerDark = Color(0xFFD7EDD8)

/**
 * CJK vermilion (朱砂): marks Chinese-script identity only — the 汉字 tag, the
 * 组词 hint line, single-char CJK accents. Never used for errors or for
 * English/POS content.
 */
val CjkAccentLight = Color(0xFFB23A2A)
val CjkContainerLight = Color(0xFFF6DAD3)
val OnCjkContainerLight = Color(0xFF48150E)

val CjkAccentDark = Color(0xFFE08A7A)
val CjkContainerDark = Color(0xFF5A231C)
val OnCjkContainerDark = Color(0xFFF8D9D2)

/**
 * Favorited star (收藏). Deepened to keep 3:1+ contrast on paper/white
 * cards (non-text glyph target); brightened on dark for the same reason.
 */
val StarLight = Color(0xFF9A6200)
val StarDark = Color(0xFFFFC046)
