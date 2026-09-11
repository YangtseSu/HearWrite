package org.yangtse.hearwrite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * 抽词听写 (Roadmap #9): the 多选词库 pool → a random X-word dictation. Shows
 * the ticked lists and the merged 词数, takes X (default = the whole pool, so
 * starting straight away is a full random run), and hands the drawn lines to
 * the standard session staging — the playback engine is untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryDrawScreen(
    onStartDictation: (lines: List<String>, sourceLabel: String) -> Unit,
    onBack: () -> Unit,
    viewModel: LibraryDrawViewModel = viewModel(),
) {
    val pool by viewModel.pool.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var countText by rememberSaveable { mutableStateOf("") }
    val size = pool.poolSize
    val requested = countText.toIntOrNull() ?: 0
    // The default is the whole pool (X ≥ 总词数 = 全量随机); the user narrows
    // it. Re-seeds whenever the pool changes (a list count arrives late).
    LaunchedEffect(size) { if (size > 0) countText = size.toString() }
    // One entry point for both the field and the quick chips, so the shown
    // number can never drift from the number actually drawn.
    fun setCount(value: Int) {
        countText = value.toString()
        viewModel.onCountChange(value)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("抽词听写") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            if (size > 0) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The activity is edge-to-edge and the window is not
                        // resized by the IME, so the X field and the start
                        // button must yield to the keyboard themselves.
                        .navigationBarsPadding()
                        .imePadding(),
                ) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = countText,
                            onValueChange = { text ->
                                val digits = text.filter { it.isDigit() }.take(5)
                                countText = digits
                                viewModel.onCountChange(digits.toIntOrNull() ?: 0)
                            },
                            label = { Text("抽词数") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = {
                                Text(if (requested > size) "最多 $size 词" else "共 $size 词")
                            },
                            modifier = Modifier.width(140.dp),
                        )
                        if (size > 10) {
                            SuggestionChip(
                                onClick = { setCount(10) },
                                label = { Text("10") },
                            )
                        }
                        if (size > 20) {
                            SuggestionChip(
                                onClick = { setCount(20) },
                                label = { Text("20") },
                            )
                        }
                        SuggestionChip(
                            onClick = { setCount(size) },
                            label = { Text("全部") },
                        )
                    }
                    Button(
                        onClick = {
                            // prepareSession claims its re-entry gate before the
                            // first suspension — a double tap only loses.
                            scope.launch {
                                viewModel.prepareSession()?.let { session ->
                                    onStartDictation(session.lines, session.sourceLabel)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                    ) {
                        Text(
                            if (count >= size) "随机听写全部 $size 词" else "随机抽 $count 词听写",
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        when {
            pool.loading -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            pool.lists.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyHint("未选择词表")
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(
                            "已选 ${pool.lists.size} 个词表 · 合计 $size 词",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (pool.mergedCount > 0) {
                            Text(
                                "已合并 ${pool.mergedCount} 个跨表重复词",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                items(pool.lists, key = { it.id }) { list ->
                    ListRow(
                        title = list.label,
                        subtitle = "${list.category} · ${list.wordCount} 词",
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
