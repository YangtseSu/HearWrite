package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/**
 * Window-size seam for the app's adaptive layouts (AUDIT C6).
 *
 * `WindowSizeClass` breakpoints are the contract; screens ask for the property
 * they act on rather than reading a raw width, so "wide enough for two columns"
 * and "cap the reading measure" are decided in one place. The class comes from
 * the official adaptive artifact, measured from the current window — not from
 * `LocalConfiguration`, which reports the *display* and misses a split-screen
 * or freeform window.
 */
@Composable
fun windowSizeClass(): WindowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass

/** True when the window is at least [minWidthDp] wide (600 = medium, 840 = expanded). */
@Composable
fun isWindowAtLeast(minWidthDp: Int): Boolean =
    windowSizeClass().isWidthAtLeastBreakpoint(minWidthDp)

/** Medium width class lower bound: the first width that affords two columns. */
const val WIDTH_DP_MEDIUM = WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND

/**
 * Widest reading measure for a text surface. Chinese body copy sets at 27sp line
 * height; past ~40 characters per line the eye loses the return sweep, so the
 * scrolling lists, the editor and the bottom bars stop growing here and centre
 * themselves inside the window instead (AUDIT C6: the Home editor used to
 * stretch edge to edge, which on an unfolded foldable is a very long line).
 */
val ContentMaxWidth: Dp = 720.dp

/**
 * Cap a child to [max] and let the parent centre it: `widthIn` first narrows the
 * incoming constraint, `fillMaxWidth` then fills exactly that narrowed width, so
 * the surface is `min(parentWidth, max)` wide on any window — full width on a
 * phone, [max] centred on a tablet. The parent must align it horizontally
 * (`Column(horizontalAlignment = CenterHorizontally)` / `Box(contentAlignment = TopCenter)`).
 */
fun Modifier.contentWidth(max: Dp = ContentMaxWidth): Modifier =
    this.widthIn(max = max).fillMaxWidth()
