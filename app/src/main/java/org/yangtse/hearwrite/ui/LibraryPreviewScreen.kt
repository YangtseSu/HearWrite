package org.yangtse.hearwrite.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.yangtse.hearwrite.domain.WordEntry
import org.yangtse.hearwrite.domain.entryToLine
import org.yangtse.hearwrite.domain.glossNeedsExpansion
import org.yangtse.hearwrite.domain.isCjkEntry
import org.yangtse.hearwrite.ui.theme.wordHead

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
    val starting by viewModel.starting.collectAsState()
    val current = entries
    // startLines awaits the lazy ECDICT enrich — launch off the click handler.
    val startScope = rememberCoroutineScope()
    // 载入草稿 hands the list to Home's draft and returns to this category
    // (B8: Home is four taps away, the browsing position is not). Confirming
    // here is what keeps that from being a silent, deferred action.
    val messages = rememberMessageController()

    Scaffold(
        // The confirmation appears in this screen's own window, just above the
        // action strip that raised it.
        snackbarHost = { MessageHost(messages) },
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Scaffold does not inset its bottomBar, and the
                        // activity is edge-to-edge: without this the two
                        // action buttons sit under the system nav bar (the
                        // sibling bars in LibrarySelectionBar / LibraryDraw
                        // already own theirs). No imePadding here — the screen
                        // has no text input.
                        .navigationBarsPadding(),
                ) {
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
                            onCheckedChange = null,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .toggleable(
                                    value = shuffle,
                                    role = Role.Switch,
                                    onValueChange = viewModel::onShuffleChange,
                                ),
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
                        // The slot is always laid out (`Spacer` when there is
                        // nothing to reset) so 从第 N 词开始 never shifts
                        // sideways, and the real button is a full 48dp touch
                        // target instead of the 40dp it used to be.
                        if (startIndex > 0) {
                            IconButton(
                                onClick = viewModel::resetStart,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = "重置为从第 1 词开始",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            Spacer(Modifier.size(48.dp))
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
                            // Names the destination: this no longer navigates
                            // away, so the message is the only thing that
                            // tells the user where the list went.
                            messages.show("已载入草稿，返回首页即可听写")
                        }) {
                            Text("载入草稿")
                        }
                        Button(
                            onClick = {
                                // startLines awaits the lazy ECDICT enrich; run it
                                // off the click so the button never blocks the UI.
                                startScope.launch {
                                    viewModel.startLines()?.let(onStartDictation)
                                }
                            },
                            enabled = !starting,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (starting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("整理词表…")
                            } else {
                                Text("听写本词表（共 ${current.size} 词）")
                            }
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
            else -> {
                // Expansion lives at the list level so it survives rows
                // scrolling out of the LazyColumn cache window (Home parity);
                // resets with the entries (the parsed → enriched swap).
                var expanded by remember(current) { mutableStateOf(setOf<Int>()) }
                LazyColumn(
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
                            expanded = index in expanded,
                            onSelect = viewModel::selectStart,
                            onToggleExpand = {
                                // Non-expandable rows (short single-sense glosses)
                                // only select the 起始词 — same gate as Home.
                                if (glossNeedsExpansion(entry.meaning)) {
                                    expanded = if (index in expanded) {
                                        expanded - index
                                    } else {
                                        expanded + index
                                    }
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

@Composable
private fun EntryRow(
    entry: WordEntry,
    index: Int,
    selected: Boolean,
    expanded: Boolean,
    onSelect: (Int) -> Unit,
    onToggleExpand: () -> Unit,
) {
    // Same anatomy as Home's 展示态 rows: word over a single meta line
    // (`pos meaning`), 2-line clamp with expansion for long glosses.
    val meta = listOfNotNull(entry.pos, entry.meaning).joinToString(" ")
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
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = {
                    onSelect(index)
                    onToggleExpand()
                },
            ),
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
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        ) {
            Text(
                entry.word,
                style = MaterialTheme.typography.wordHead,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else if (!isCjkEntry(entry.word)) {
                // English headwords without ECDICT meta yet — a placeholder
                // keeps the row height stable while the offline enrichment
                // fills in (Chinese bare words are spoken as-is, no meta).
                Text(
                    "——",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
    }
}
