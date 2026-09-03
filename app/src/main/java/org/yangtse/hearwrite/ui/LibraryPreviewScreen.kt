package org.yangtse.hearwrite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.yangtse.hearwrite.domain.WordEntry
import org.yangtse.hearwrite.domain.entryToLine

/**
 * Word preview of one built-in list: numbered rows (headword + pos/pinyin +
 * meaning) where tapping a word selects it as the 起始词 — the Home display-row
 * anatomy (primary leading bar + tint on the cursor row). The bottom options
 * strip carries 随机顺序 and the live 从第 N 词开始 indicator (✕ resets to the
 * whole list); actions below: 载入草稿 (loads the full list into the Home
 * draft) and 听写本词表 (starts dictation over the slice from the tapped word,
 * shuffled on demand — same ordering Home applies).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryPreviewScreen(
    onBack: () -> Unit,
    onLoadToDraft: (List<String>) -> Unit,
    onStartDictation: (List<String>) -> Unit,
    viewModel: LibraryPreviewViewModel = viewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    val shuffle by viewModel.shuffle.collectAsState()
    val startIndex by viewModel.startIndex.collectAsState()
    val current = entries

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.label) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            if (current != null && current.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "随机顺序",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Switch(
                            checked = shuffle,
                            onCheckedChange = viewModel::onShuffleChange,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .semantics { contentDescription = "随机打乱词序" },
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (startIndex == 0) {
                                "从第 1 词开始"
                            } else {
                                "从第 ${startIndex + 1} 词开始"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (startIndex == 0) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        if (startIndex > 0) {
                            IconButton(
                                onClick = viewModel::resetStart,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = "重置为从第 1 词开始",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = {
                            onLoadToDraft(current.map(::entryToLine))
                        }) {
                            Text("载入草稿")
                        }
                        Button(
                            onClick = { onStartDictation(viewModel.startLines()) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("听写本词表（共 ${current.size} 词）")
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        when (val current = entries) {
            null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                item {
                    Text(
                        "${viewModel.category} · 共 ${current.size} 词",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                itemsIndexed(current) { index, entry ->
                    EntryRow(
                        entry = entry,
                        index = index,
                        selected = index == startIndex,
                        onSelect = viewModel::selectStart,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: WordEntry,
    index: Int,
    selected: Boolean,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 52.dp)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                } else {
                    Color.Transparent
                },
            )
            .clickable { onSelect(index) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                ),
        )
        Text(
            (index + 1).toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .padding(start = 9.dp)
                .width(28.dp),
        )
        Text(
            entry.word,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(vertical = 8.dp),
        )
        Column(
            modifier = Modifier
                .weight(2f)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            entry.pos?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            entry.meaning?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
    }
}
