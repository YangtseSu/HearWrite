package org.yangtse.hearwrite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Row-level toggle for the rows whose visible Switch/Checkbox is the control.
 *
 * The Android-settings pattern: **the row is the hit target and carries the
 * state**, the indicator is purely visual (`onCheckedChange = null`). Splitting
 * them — a clickable row plus a stateful Switch — makes one setting into two
 * focus stops, so TalkBack announces "按钮" and "开关" and the row itself never
 * reports whether the setting is on. The repo's `RadioButton(onClick = null)`
 * in [SettingsRadioRow] is the same pattern for the exclusive-choice case.
 */
@Immutable
class RowToggle(
    val checked: Boolean,
    val role: Role,
    val onToggle: () -> Unit,
)

/**
 * Shared list-row anatomy for the 收藏 / 历史记录 sheets and the 词库 screens:
 * bodyLarge title over an optional labelMedium subtitle, leading-aligned at
 * 20dp with trailing action slots; callers own the row key and the divider.
 *
 * [toggle] turns the whole row into a `toggleable` (state + Role in the
 * semantics tree); pass it *instead of* `onClick` when the row's own trailing
 * control drives the state.
 */
@Composable
fun ListRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    toggle: RowToggle? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { base ->
                when {
                    toggle != null -> base.toggleable(
                        value = toggle.checked,
                        role = toggle.role,
                        onValueChange = { toggle.onToggle() },
                    )
                    onClick != null -> base.clickable(onClick = onClick)
                    else -> base
                }
            }
            .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing()
    }
}

/** Chevron for tappable rows that open a detail surface (词库 rows). */
@Composable
fun RowChevron() {
    Icon(
        Icons.AutoMirrored.Filled.NavigateNext,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Empty-state hint shared by the sheets and the library screens. */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 32.dp),
    )
}
