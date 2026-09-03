package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.yangtse.hearwrite.ui.theme.StarGold
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel


/** One category: its lists in the upstream label order; a tap opens the
 *  preview, the star toggles the list's favorite state (收藏 drawer on Home). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryListsScreen(
    onOpenList: (label: String) -> Unit,
    onBack: () -> Unit,
    viewModel: LibraryListsViewModel = viewModel(),
) {
    val lists by viewModel.lists.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.category) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
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
                    ListRow(
                        title = list.label,
                        subtitle = null,
                        onClick = { onOpenList(list.label) },
                        trailing = {
                            IconButton(onClick = { viewModel.toggleFavorite(list.id) }) {
                                Icon(
                                    if (favorited) Icons.Filled.Star else Icons.Filled.StarBorder,
                                    contentDescription = if (favorited) "取消收藏" else "收藏",
                                    tint = if (favorited) {
                                        StarGold
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            RowChevron()
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
