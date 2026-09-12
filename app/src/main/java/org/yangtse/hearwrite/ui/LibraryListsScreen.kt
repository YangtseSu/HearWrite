package org.yangtse.hearwrite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.ui.theme.hearWriteSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel


/** One category: its lists in the upstream label order; a tap opens the
 *  preview, the star toggles the list's favorite state (收藏 drawer on Home).
 *  While 多选词库 is on (Roadmap #9) rows tick instead — the selection spans
 *  categories, so the bar and the state are shared with the browse screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryListsScreen(
    onOpenList: (label: String) -> Unit,
    onOpenDraw: () -> Unit,
    onBack: () -> Unit,
    viewModel: LibraryListsViewModel = viewModel(),
) {
    val lists by viewModel.lists.collectAsState()
    val wordCounts by viewModel.wordCounts.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val selection = (LocalContext.current.applicationContext as HearWriteApplication).librarySelection
    val selecting by selection.active.collectAsState()
    val selectedIds by selection.selectedIds.collectAsState()

    // Mirrors the browse screen: one guarded exit behind system back, the
    // app-bar arrow and 退出多选 (a ticked selection is never dropped
    // silently, and the three affordances cannot disagree).
    val exitSelection = rememberSelectionExitGuard(selectedIds.size) { selection.setActive(false) }
    val onBackOrExit: () -> Unit = { if (selecting) exitSelection() else onBack() }

    BackHandler(enabled = selecting) { exitSelection() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.category) },
                navigationIcon = {
                    IconButton(onClick = onBackOrExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (selecting) {
                        TextButton(onClick = exitSelection) { Text("退出多选") }
                    } else {
                        IconButton(onClick = { selection.setActive(true) }) {
                            Icon(Icons.Filled.Checklist, contentDescription = "多选词表")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selecting) {
                LibrarySelectionBar(
                    selectedCount = selectedIds.size,
                    onStartDraw = onOpenDraw,
                    onExit = exitSelection,
                )
            }
        },
    ) { innerPadding ->
        when (val current = lists) {
            null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(current, key = { it.id }) { list ->
                    val favorited = list.id in favoriteIds
                    val ticked = list.id in selectedIds
                    ListRow(
                        title = list.label,
                        subtitle = wordCounts[list.id]?.let { "$it 词" },
                        onClick = {
                            if (selecting) selection.toggle(list.id) else onOpenList(list.label)
                        },
                        trailing = {
                            if (selecting) {
                                Checkbox(
                                    checked = ticked,
                                    onCheckedChange = { selection.toggle(list.id) },
                                    modifier = Modifier.semantics {
                                        contentDescription = "选择词表 ${list.label}"
                                    },
                                )
                            } else {
                                IconButton(onClick = { viewModel.toggleFavorite(list.id) }) {
                                    Icon(
                                        if (favorited) Icons.Filled.Star else Icons.Filled.StarBorder,
                                        contentDescription = if (favorited) "取消收藏" else "收藏",
                                        tint = if (favorited) {
                                            hearWriteSemantics.star
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                RowChevron()
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
