package org.yangtse.hearwrite.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.yangtse.hearwrite.data.HistoryEntry
import org.yangtse.hearwrite.domain.parseWords
import org.yangtse.hearwrite.ui.theme.StarGold
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SHEET_LIST_MAX_HEIGHT = 440.dp

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun formatStamp(epochMs: Long): String {
    val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
    return DateTimeFormatter.ofPattern("MM-dd HH:mm").format(time)
}

private fun wordCount(text: String): Int = parseWords(text).size

/**
 * 历史记录 bottom sheet: rows of user-pasted lists (newest first) — tap to
 * load into the Home draft, star to favorite, trash to delete; 清空 with a
 * confirm dialog owned by the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    entries: List<HistoryEntry>,
    favoriteIds: Set<String>,
    onApply: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "历史记录（${entries.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text("清空", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (entries.isEmpty()) {
                Text(
                    "暂无历史记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 32.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = SHEET_LIST_MAX_HEIGHT)) {
                    items(entries, key = { it.id }) { entry ->
                        val text = entry.enrichedText ?: entry.text
                        val favorited = entry.id in favoriteIds
                        ListRow(
                            title = entry.text.lineSequence().first { it.isNotBlank() }.trim(),
                            subtitle = "${wordCount(entry.text)} 词 · ${formatStamp(entry.createdAt)}",
                            onClick = {
                                onApply(text)
                                toast(context, "已载入历史记录")
                            },
                            trailing = {
                                IconButton(
                                    onClick = { onToggleFavorite(entry.id) },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        if (favorited) Icons.Filled.Star else Icons.Filled.StarBorder,
                                        contentDescription = if (favorited) "取消收藏" else "收藏",
                                        tint = if (favorited) StarGold else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { onDelete(entry.id) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 收藏 bottom sheet: favorited library lists and history rows (library content
 * resolved from assets on demand). Tap to load into the Home draft; the star
 * removes the favorite.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesSheet(
    items: List<FavoriteUiItem>,
    onApply: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "收藏（${items.size}）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            )
            if (items.isEmpty()) {
                Text(
                    "暂无收藏",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 32.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = SHEET_LIST_MAX_HEIGHT)) {
                    items(items, key = { it.id }) { item ->
                        ListRow(
                            title = item.title,
                            subtitle = item.subtitle,
                            onClick = {
                                onApply(item.linesText)
                                toast(context, "已载入收藏")
                            },
                            trailing = {
                                IconButton(onClick = { onToggleFavorite(item.id) }) {
                                    Icon(
                                        Icons.Filled.Star,
                                        contentDescription = "取消收藏",
                                        tint = StarGold,
                                    )
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
