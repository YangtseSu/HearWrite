package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.yangtse.hearwrite.data.LibraryCategory

/**
 * 词库 browse screen: the 10 textbook categories, or — while a query is
 * active — full-library search results grouped into list-name hits and
 * word hits. Category names open the category list; every hit opens the
 * word preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenCategory: (String) -> Unit,
    onOpenList: (category: String, label: String) -> Unit,
    onBack: () -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val categories by viewModel.categories.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val queryText by viewModel.queryText.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("词库") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
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
                state is LibrarySearchState.Idle -> CategoryList(
                    categories = categories,
                    onOpenCategory = onOpenCategory,
                )
                state is LibrarySearchState.Loading -> CenterProgress()
                state is LibrarySearchState.Done -> SearchResults(
                    state = state,
                    onOpenList = onOpenList,
                )
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
        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(categories, key = { it.name }) { category ->
                CategoryRow(category = category, onClick = { onOpenCategory(category.name) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CategoryRow(category: LibraryCategory, onClick: () -> Unit) {
    ListRow(
        title = category.name,
        subtitle = "${category.listCount} 个词表",
        onClick = onClick,
        trailing = { RowChevron() },
    )
}

@Composable
private fun SearchResults(
    state: LibrarySearchState.Done,
    onOpenList: (category: String, label: String) -> Unit,
) {
    val result = state.result
    if (result.labelHits.isEmpty() && result.wordHits.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyHint("未找到匹配的词表")
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (result.labelHits.isNotEmpty()) {
            item { SectionHeader("词表") }
            items(result.labelHits, key = { "l_${it.id}" }) { list ->
                SearchListRow(
                    category = list.category,
                    label = list.label,
                    subtitle = "词表名匹配",
                    onClick = { onOpenList(list.category, list.label) },
                )
                HorizontalDivider()
            }
        }
        if (result.wordHits.isNotEmpty()) {
            item { SectionHeader("词条") }
            items(result.wordHits, key = { "w_${it.list.id}" }) { hit ->
                SearchListRow(
                    category = hit.list.category,
                    label = hit.list.label,
                    subtitle = hit.words.joinToString("、"),
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
    onClick: () -> Unit,
) {
    ListRow(
        title = label,
        subtitle = "$category · $subtitle",
        onClick = onClick,
        trailing = { RowChevron() },
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
