package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.data.LibraryCategory

/**
 * 词库 browse screen: every library category (textbook sets plus the 课标
 * 字表), or — while a query is active — full-library search results grouped
 * into list-name hits and word hits. Category names open the category list;
 * every hit opens the word preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenCategory: (String) -> Unit,
    onOpenList: (category: String, label: String) -> Unit,
    onOpenDraw: () -> Unit,
    onBack: () -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val categories by viewModel.categories.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val queryText by viewModel.queryText.collectAsState()
    val selection = (LocalContext.current.applicationContext as HearWriteApplication).librarySelection
    val selecting by selection.active.collectAsState()
    val selectedIds by selection.selectedIds.collectAsState()

    // One guarded exit for every affordance in selection mode — system back,
    // the app-bar arrow and 退出多选词表 all funnel through it, so they cannot
    // mean three different things (and a non-empty tick set is never dropped
    // silently). The arrow leaves the 词库 when not selecting.
    val exitSelection = rememberSelectionExitGuard(selectedIds.size) { selection.setActive(false) }
    val onBackOrExit: () -> Unit = { if (selecting) exitSelection() else onBack() }

    BackHandler(enabled = selecting) { exitSelection() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("词库") },
                navigationIcon = {
                    IconButton(onClick = onBackOrExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (selecting) {
                        // 完成 read as "confirm", but it discards the tick set;
                        // name it what it does — one name for the mode, the
                        // same one the entry icon and the bar carry.
                        TextButton(onClick = exitSelection) { Text("退出多选词表") }
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
        // The search field has to line up with the rows below it, so field,
        // hint and results share one capped, centred column instead of every
        // element stretching to the window edges (AUDIT C6).
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .contentWidth()
                    .padding(innerPadding)
                    // Scaffold's default contentWindowInsets does NOT contain
                    // the IME (systemBarsForVisualComponents only), so the
                    // search field's results must yield the keyboard here.
                    // consumeWindowInsets first, so the system-bar part of
                    // innerPadding is not counted twice once the IME opens.
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
            ) {
                OutlinedTextField(
                    value = queryText,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("搜索词表或单词") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (queryText.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onQueryChange("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "清空搜索")
                            }
                        }
                    },
                    singleLine = true,
                )
                val state = searchState
                when {
                    state is LibrarySearchState.Idle -> Column {
                        if (selecting) {
                            Text(
                                "多选词表：进入分类勾选（可跨分类）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        CategoryList(
                            categories = categories,
                            onOpenCategory = onOpenCategory,
                        )
                    }
                    state is LibrarySearchState.Loading -> CenterProgress()
                    state is LibrarySearchState.Done -> SearchResults(
                        state = state,
                        selecting = selecting,
                        selectedIds = selectedIds,
                        onToggle = selection::toggle,
                        onOpenList = onOpenList,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryList(
    categories: List<LibraryCategory>?,
    onOpenCategory: (String) -> Unit,
) {
    when {
        categories == null -> CenterProgress()
        else -> LazyVerticalGrid(
            // A hard-coded two columns drew two very wide cards on a tablet,
            // so the count follows the window instead. 180.dp is the floor a
            // card needs: the 分类名 (up to 8 CJK characters) has to keep two
            // readable lines above the `N 个词表` pill, and the pill plus the
            // card's own 32.dp of side padding is what sets that floor.
            // Compose derives the count from the card's cross-axis room,
            // floor((available + 12dp gap) / (180dp + 12dp gap)): a 411dp phone
            // window − 32dp grid padding = 379dp ⇒ 2 columns, unchanged from
            // the old Fixed(2); 600dp ⇒ 3. The raw arithmetic would reach 4 at
            // 840dp, but the 720dp reading cap below binds first (720 − 32 =
            // 688dp ⇒ 3 columns of ~221dp), so nothing wider than a 3-up grid
            // is ever drawn.
            columns = GridCells.Adaptive(minSize = 180.dp),
            modifier = Modifier.fillMaxHeight().contentWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(categories, key = { it.name }) { category ->
                CategoryCard(category = category, onClick = { onOpenCategory(category.name) })
            }
        }
    }
}

@Composable
private fun CategoryCard(category: LibraryCategory, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                category.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    "${category.listCount} 个词表",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    state: LibrarySearchState.Done,
    selecting: Boolean,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onOpenList: (category: String, label: String) -> Unit,
) {
    val result = state.result
    // A list whose label matched is listed under 词表 only: one id shown in
    // both sections would render two checkboxes driving the same tick.
    val labelIds = result.labelHits.mapTo(mutableSetOf<String>()) { it.id }
    val wordHits = result.wordHits.filterNot { it.list.id in labelIds }
    if (result.labelHits.isEmpty() && wordHits.isEmpty()) {
        // The hint is centred in the reading measure rather than the window
        // (AUDIT C6): on a tablet the message would otherwise float in the
        // middle of a very wide empty band.
        Column(
            modifier = Modifier.fillMaxHeight().contentWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyHint("未找到匹配的词表")
        }
        return
    }
    // Same cap as the browse grid above it, so a hit row and a category card
    // share one left edge on a wide window.
    LazyColumn(modifier = Modifier.fillMaxHeight().contentWidth()) {
        if (result.labelHits.isNotEmpty()) {
            item { SectionHeader("词表") }
            items(result.labelHits, key = { "l_${it.id}" }) { list ->
                SearchListRow(
                    category = list.category,
                    label = list.label,
                    subtitle = "词表名匹配",
                    selecting = selecting,
                    selected = list.id in selectedIds,
                    onToggle = { onToggle(list.id) },
                    onClick = { onOpenList(list.category, list.label) },
                )
                HorizontalDivider()
            }
        }
        if (wordHits.isNotEmpty()) {
            item { SectionHeader("词条") }
            items(wordHits, key = { "w_${it.list.id}" }) { hit ->
                SearchListRow(
                    category = hit.list.category,
                    label = hit.list.label,
                    subtitle = hit.words.joinToString("、"),
                    selecting = selecting,
                    selected = hit.list.id in selectedIds,
                    onToggle = { onToggle(hit.list.id) },
                    onClick = { onOpenList(hit.list.category, hit.list.label) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SearchListRow(
    category: String,
    label: String,
    subtitle: String,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    ListRow(
        title = label,
        subtitle = "$category · $subtitle",
        // Same long labels as the category list rows: two lines.
        titleMaxLines = 2,
        // 多选 flips the row's meaning: it ticks instead of opening. The
        // toggle carries the state and the hit target, the Checkbox below is
        // decorative — see [RowToggle].
        onClick = if (selecting) null else onClick,
        toggle = if (selecting) {
            RowToggle(checked = selected, role = Role.Checkbox, onToggle = onToggle)
        } else {
            null
        },
        trailing = {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = null)
            } else {
                RowChevron()
            }
        },
    )
}

@Composable
private fun CenterProgress() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
    }
}
