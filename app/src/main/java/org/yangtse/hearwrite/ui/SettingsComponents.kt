package org.yangtse.hearwrite.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Shared building blocks for the Android-settings-style 设置 hub and its
 * sub-pages: an inset rounded group card ([SettingsCard]) with section
 * headers ([SettingsSectionHeader]), list rows ([SettingsRow], [SettingsRadioRow])
 * and the sub-page scaffold ([SettingsSubPage]) with a back arrow.
 * Every user-facing string stays Chinese; row semantics follow the M3
 * settings pattern (the row text is the label and the row itself carries any
 * state, so the trailing indicator is decorative rather than a second focus
 * stop).
 */

/**
 * Small gray section header above a group card (e.g. 外观 / 听写). It is a real
 * heading in the semantics tree, so TalkBack can jump section to section
 * instead of reading one flat list of rows.
 */
@Composable
fun SettingsSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .semantics { heading() }
            .padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** One inset rounded group card; children are [SettingsRow]-style rows. */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(content = content)
    }
}

/**
 * One settings list row: leading icon, title + supporting text, optional
 * trailing composable (value / switch / chevron). [onClick] makes the whole
 * row tappable; pass [toggle] instead when the row's own state is what it
 * carries — the row then reports on/off and the trailing indicator is drawn
 * as its purely visual reflection (`onCheckedChange = null`). The divider is
 * inset to the text column when an icon is shown (Android settings convention).
 *
 * [onClickLabel] names the action for TalkBack when the row's own text does not
 * say what tapping does (a row that opens another list reads as its data, not
 * as its destination).
 */
@Composable
fun SettingsRow(
    title: String,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    toggle: RowToggle? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .let { base ->
                    when {
                        toggle != null -> base.toggleable(
                            value = toggle.checked,
                            role = toggle.role,
                            onValueChange = { toggle.onToggle() },
                        )
                        onClick != null -> base.clickable(
                            onClickLabel = onClickLabel,
                            onClick = onClick,
                        )
                        else -> base
                    }
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(16.dp))
                trailing()
            }
        }
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = if (leading != null) 56.dp else 16.dp),
            )
        }
    }
}

/**
 * One selectable radio row (Android settings choice list). The whole row is
 * the radio-button hit target via [selectable]; the trailing indicator is
 * purely visual.
 *
 * [expandedContent] renders inline under the row (same card, no rounded
 * break) when [expanded] is true — for per-option config that belongs to
 * this row so the ownership needs no guessing. The reveal animates its height
 * ([animateContentSize]), so the card grows into the extra config instead of
 * jumping. Scrolling the config into view is deliberately left to the page:
 * only the page owns the scroll container, so a requester here would have to
 * be plumbed through every call site for a shared widget's convenience.
 */
@Composable
fun SettingsRadioRow(
    title: String,
    supporting: String? = null,
    selected: Boolean,
    divider: Boolean = true,
    onClick: () -> Unit,
    expanded: Boolean = false,
    expandedContent: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .heightIn(min = 60.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            RadioButton(selected = selected, onClick = null)
        }
        // Always composed — even collapsed, at zero height — so this node
        // outlives the toggle. animateContentSize only animates a size change
        // it can see: a Box created fresh when `expanded` flips true starts
        // its animation data at the size it is first measured at and would
        // snap open instead. The padding sits on the inner Box, so a collapsed
        // row still measures 0 and an expanded one measures exactly as before;
        // a null [expandedContent] renders nothing at all, as it used to.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
        ) {
            if (expanded && expandedContent != null) {
                Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    expandedContent()
                }
            }
        }
        if (divider) {
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        }
    }
}

/** Standard row trailing for "opens a sub-page": secondary value + chevron. */
@Composable
fun RowScope.SettingsValueTrailing(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.width(2.dp))
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Standard row trailing for "opens a sub-page": chevron only. */
@Composable
fun RowScope.SettingsChevronTrailing() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Scaffold + top app bar shared by every settings sub-page (back → hub).
 * Content is a vertically scrollable column with the standard side padding.
 *
 * The shell is self-contained on feedback: it installs its own message channel
 * and renders that channel's [MessageHost] in its Scaffold. A descendant that
 * runs a 保存并启用 or a test round-trip therefore reports the result with
 * `LocalMessages.current.show("已保存发音配置")` and the Snackbar lands in this
 * page's window — no wiring, no controller passed down by the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSubPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val messages = rememberMessageController()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { MessageHost(messages) },
    ) { innerPadding ->
        CompositionLocalProvider(LocalMessages provides messages) {
            // The sub-page body is capped to a reading measure and centred,
            // so the form does not stretch edge to edge on a tablet, foldable
            // or desktop window.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .contentWidth()
                        .padding(innerPadding)
                        // Edge-to-edge and the window is not resized by the IME, so a
                        // sub-page with text fields (both provider forms) must yield
                        // the keyboard space itself — otherwise 保存并启用 and the
                        // lower fields sit behind the keyboard and scroll-to-focus
                        // cannot lift them clear. imePadding goes BEFORE
                        // verticalScroll (the Compose ordering contract: padding
                        // must be applied before scrolling offsets content).
                        .imePadding()
                        .padding(bottom = 32.dp)
                        .verticalScroll(rememberScrollState()),
                    content = content,
                )
            }
        }
    }
}

/**
 * Theme preview card for the 外观 setting: a miniature paper/ink swatch over
 * the label. 所见即所得 — the swatch shows the actual background + primary
 * of each mode instead of a text-only chip.
 *
 * [dynamicColor] is 动态取色: with it on, the 浅色/深色 modes render the
 * wallpaper-derived scheme, so their swatches resolve the same way instead of
 * promising the curated palette the app no longer uses.
 */
@Composable
fun ThemePreviewCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dynamicColor: Boolean = false,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(
                2.dp,
                MaterialTheme.colorScheme.primary,
            )
        } else {
            null
        },
        // The mode is also a choice, not just a button: selectable() puts
        // selected + Role.RadioButton into the semantics tree (the 2dp border
        // alone told TalkBack nothing), matching SettingsRadioRow.
        modifier = modifier.selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = onClick,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ThemeSwatch(label = label, dynamicColor = dynamicColor)
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ThemeSwatch(label: String, dynamicColor: Boolean) {
    val context = LocalContext.current
    val light = if (dynamicColor) dynamicLightColorScheme(context) else null
    val dark = if (dynamicColor) dynamicDarkColorScheme(context) else null
    val (background, primary) = when (label) {
        "深色" -> dark?.let { it.background to it.primary }
            ?: (org.yangtse.hearwrite.ui.theme.BackgroundDark to
                org.yangtse.hearwrite.ui.theme.PrimaryDark)
        "跟随系统" -> MaterialTheme.colorScheme.background to
            MaterialTheme.colorScheme.primary
        else -> light?.let { it.background to it.primary }
            ?: (org.yangtse.hearwrite.ui.theme.BackgroundLight to
                org.yangtse.hearwrite.ui.theme.PrimaryLight)
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp)
                    .background(primary, androidx.compose.foundation.shape.CircleShape),
            )
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth(0.6f)
                    .heightIn(min = 8.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        androidx.compose.foundation.shape.CircleShape,
                    ),
            )
        }
    }
}

// ---- Display helpers for settings values (top-level, package-visible) ----

/** Speech rate with one decimal ("0.9"). */
internal fun formatRate(value: Float): String =
    String.format(Locale.ROOT, "%.1f", value)

/** Human cache size ("3.2 MB" / "640 KB" / "0 B"). */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
