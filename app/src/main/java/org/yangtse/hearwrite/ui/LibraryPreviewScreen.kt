package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.yangtse.hearwrite.domain.WordEntry
import org.yangtse.hearwrite.domain.entryToLine

/**
 * Word preview of one built-in list: headword + pos/pinyin + meaning per row,
 * plus a one-tap 听写 action that starts dictation over this list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryPreviewScreen(
    onBack: () -> Unit,
    onStartDictation: (List<String>) -> Unit,
    viewModel: LibraryPreviewViewModel = viewModel(),
) {
    val entries by viewModel.entries.collectAsState()
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
                Button(
                    onClick = {
                        onStartDictation(current.map(::entryToLine))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text("听写本词表（共 ${current.size} 词）")
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
                items(current) { entry ->
                    EntryRow(entry)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: WordEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            entry.word,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f, fill = false),
        )
        Column(
            modifier = Modifier.weight(2f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            entry.pos?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    }
}
