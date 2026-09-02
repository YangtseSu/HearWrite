package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.yangtse.hearwrite.domain.parseWords

/** 示例 content: English words with gloss columns (朗读释义 demo-able). */
private const val SAMPLE_EN = "apple | n. | 苹果\nbanana | n. | 香蕉\nschool | n. | 学校\nbook | n. | 书\ncar | n. | 汽车"

/** 示例 content: bare 汉字词语 (zh-CN dictation). */
private const val SAMPLE_CJK = "香蕉\n学校\n苹果\n月亮\n生日"

/**
 * Home: paste/type the word list (draft persisted with a 500 ms debounce and
 * flushed on dispose), pick 起始序号 / 随机顺序 (session-local), then start
 * dictation — or open 词库 / 设置 / 历史 / 收藏 (bottom sheets). Starting
 * enriches the list with offline ECDICT meta and records it in history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartDictation: (List<String>) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val wordCount by viewModel.wordCount.collectAsStateWithLifecycle()
    val startIndex by viewModel.startIndex.collectAsStateWithLifecycle()
    val shuffle by viewModel.shuffle.collectAsStateWithLifecycle()
    val starting by viewModel.starting.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val favoriteItems by viewModel.favoriteItems.collectAsStateWithLifecycle()

    var showHistory by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
    var clearHistoryConfirm by remember { mutableStateOf(false) }

    // Flush a pending debounced draft when the screen goes away — the
    // upstream bug: the timer was cleared without saving, dropping the last
    // keystrokes on exit.
    DisposableEffect(Unit) {
        onDispose { viewModel.flushDraft() }
    }

    if (clearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { clearHistoryConfirm = false },
            title = { Text("清空历史记录？") },
            text = { Text("将删除全部 ${history.size} 条历史记录，收藏的条目不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    clearHistoryConfirm = false
                    viewModel.clearHistory()
                    android.widget.Toast.makeText(context, "已清空历史记录", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { clearHistoryConfirm = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HearWrite 听写") },
                actions = {
                    IconButton(onClick = { showFavorites = true }) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "收藏",
                            tint = if (favorites.isEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                androidx.compose.ui.graphics.Color(0xFFF9A825)
                            },
                        )
                    }
                    IconButton(onClick = { showHistory = true }) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = "历史记录",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = viewModel::onDraftChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(180.dp),
                placeholder = {
                    Text("在此粘贴或输入词表，每行一个词\n支持：词 | 词性 | 释义")
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "共 $wordCount 词",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.fillSample(SAMPLE_EN) }) { Text("英文示例") }
                TextButton(onClick = { viewModel.fillSample(SAMPLE_CJK) }) { Text("汉字示例") }
                TextButton(onClick = viewModel::clearDraft) { Text("清空") }
            }

            HorizontalDivider()

            // ---- 起始序号 (clamped when the list shrinks) ----------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("起始序号", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { viewModel.adjustStartIndex(-1) },
                    enabled = wordCount > 0 && startIndex > 0,
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = "起始序号减一")
                }
                Text(
                    if (wordCount == 0) "—" else "${startIndex + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { contentDescription = "起始序号" },
                )
                IconButton(
                    onClick = { viewModel.adjustStartIndex(1) },
                    enabled = wordCount > 0 && startIndex < wordCount - 1,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "起始序号加一")
                }
            }
            if (wordCount > 0) {
                val firstWord = parseWords(draft)[startIndex]
                Text(
                    "从第 ${startIndex + 1} 个词开始：$firstWord",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 随机顺序 (session-local, not persisted) -------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("随机顺序", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = shuffle,
                    onCheckedChange = viewModel::onShuffleChange,
                    modifier = Modifier.semantics { contentDescription = "随机打乱词序" },
                )
            }

            // Start: enrich (first run parses the ECDICT map on IO), record
            // the list in history, then hand the prepared lines over.
            Button(
                onClick = {
                    scope.launch {
                        viewModel.prepareAndRecord()?.let(onStartDictation)
                    }
                },
                enabled = wordCount > 0 && !starting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(top = 2.dp),
            ) {
                if (starting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("整理词表…")
                } else {
                    Text("开始听写" + if (wordCount > 0) "（$wordCount 词）" else "")
                }
            }
            if (wordCount == 0) {
                Text(
                    "输入词表或从词库选择后开始",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) {
                Text("词库")
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("设置")
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showHistory) {
        HistorySheet(
            entries = history,
            favoriteIds = favorites,
            onApply = viewModel::applyEntry,
            onToggleFavorite = viewModel::toggleFavorite,
            onDelete = { id ->
                viewModel.deleteHistory(id)
                android.widget.Toast.makeText(context, "已删除", android.widget.Toast.LENGTH_SHORT).show()
            },
            onClear = { clearHistoryConfirm = true },
            onDismiss = { showHistory = false },
        )
    }

    if (showFavorites) {
        FavoritesSheet(
            items = favoriteItems,
            onApply = viewModel::applyEntry,
            onToggleFavorite = viewModel::toggleFavorite,
            onDismiss = { showFavorites = false },
        )
    }
}
