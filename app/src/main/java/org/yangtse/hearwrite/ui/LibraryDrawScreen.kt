package org.yangtse.hearwrite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.yangtse.hearwrite.domain.WordRow
import org.yangtse.hearwrite.ui.theme.wordHead

/**
 * 抽词听写 (Roadmap #9): the 多选词表 pool → a random X-word dictation. Shows
 * the ticked lists and the merged 词数, takes X (default = the whole pool, so
 * starting straight away is a full random run), and hands the drawn lines to
 * the standard session staging — the playback engine is untouched.
 *
 * Two AUDIT C4 corrections live here. The X field and its quick chips sit in a
 * [FlowRow]: a single non-wrapping Row of a 140 dp field plus three chips has an
 * intrinsic width of ~380 dp, wider than a 320 dp screen. And the pool summary
 * (已选 N 个词表 · 合计 M 词) moved **into the bottom bar** with the button it
 * justifies — as a scrolling item it was off-screen exactly when the user was
 * deciding whether to press 随机听写. The 逐词预览 block is the other half of
 * that: the words are drawn up front and shown, and [LibraryDrawViewModel]
 * replays the same list at start, so the page never dictates something other
 * than what it displayed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryDrawScreen(
    onStartDictation: (rows: List<WordRow>, sourceLabel: String) -> Unit,
    onBack: () -> Unit,
    viewModel: LibraryDrawViewModel = viewModel(),
) {
    val pool by viewModel.pool.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
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
    // The preview IS the run: it is drawn as soon as the pool is ready and
    // re-drawn whenever X changes, and prepareSession replays it.
    LaunchedEffect(size, count) { viewModel.ensurePreview() }

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
                    // The bar's surface stays full width (its divider reads as
                    // the screen's edge); its content is capped and centred so
                    // the summary, the field and the start button keep one
                    // measure on a wide window (AUDIT C6).
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    HorizontalDivider()
                    Column(
                        modifier = Modifier
                            .contentWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 10.dp),
                    ) {
                        Text(
                            "已选 ${pool.lists.size} 个词表 · 合计 $size 词",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (pool.mergedCount > 0) {
                            Text(
                                "已自动合并 ${pool.mergedCount} 个跨表重复词",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        if (pool.failedLabels.isNotEmpty()) {
                            Text(
                                "以下词表未能读取，已排除：${pool.failedLabels.joinToString("、")}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    // A non-wrapping Row of field + chips is ~380 dp wide and
                    // overflows every phone; wrapping keeps all four reachable.
                    FlowRow(
                        modifier = Modifier
                            .contentWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
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
                        // A pool of exactly 10/20 still offers that round
                        // number; chips larger than the pool stay hidden.
                        if (size >= 10) {
                            SuggestionChip(
                                onClick = { setCount(10) },
                                label = { Text("10") },
                            )
                        }
                        if (size >= 20) {
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
                                    onStartDictation(session.rows, session.sourceLabel)
                                }
                            }
                        },
                        modifier = Modifier
                            .contentWidth()
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
        // The Scaffold content slot places a narrower child at its start, so the
        // capped column is centred by this Box rather than hugging the left edge
        // (AUDIT C6).
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        when {
            pool.loading -> Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .contentWidth()
                    .padding(innerPadding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            pool.lists.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .contentWidth()
                    .padding(innerPadding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Every tick failed to load: 未选择词表 would be a lie — name
                // the lists that could not be read instead.
                if (pool.failedLabels.isEmpty()) {
                    EmptyHint("未选择词表")
                } else {
                    EmptyHint("选中的词表无法读取：${pool.failedLabels.joinToString("、")}")
                }
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .contentWidth()
                    .padding(innerPadding),
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "本次将听写 ${preview.size} 词（按顺序）",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.reshufflePreview() }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            Text("换一批")
                        }
                    }
                }
                itemsIndexed(preview, key = { index, _ -> "p$index" }) { index, row ->
                    DrawPreviewRow(index = index + 1, row = row)
                    HorizontalDivider()
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "参与抽词的词表（${pool.lists.size}）",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
                items(pool.lists, key = { it.id }) { list ->
                    ListRow(
                        title = list.label,
                        subtitle = "${list.category} · ${list.wordCount} 词",
                        // Same list labels as the 词库 rows.
                        titleMaxLines = 2,
                    )
                    HorizontalDivider()
                }
            }
        }
        }
    }
}

/**
 * One drawn word: its position in the run and the entry it will dictate, with
 * the columns (词性/释义, 拼音/组词) shown the way the dictation hints will use
 * them. Kept to one row per word so a full 100-word draw stays scannable.
 */
@Composable
private fun DrawPreviewRow(index: Int, row: WordRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$index",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.display,
                style = MaterialTheme.typography.wordHead,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val hint = listOfNotNull(row.pos, row.gloss).joinToString(" · ")
            if (hint.isNotEmpty()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
