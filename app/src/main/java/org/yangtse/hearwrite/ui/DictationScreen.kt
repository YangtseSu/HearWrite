package org.yangtse.hearwrite.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.yangtse.hearwrite.ui.theme.hearWriteSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.yangtse.hearwrite.domain.MAX_INTERVAL_SEC
import org.yangtse.hearwrite.domain.MIN_INTERVAL_SEC
import org.yangtse.hearwrite.domain.PlayState
import org.yangtse.hearwrite.domain.WordEntry
import org.yangtse.hearwrite.domain.isCjkEntry
import org.yangtse.hearwrite.domain.parseWordLine
import java.util.Locale
import kotlin.math.ceil


private fun formatElapsed(sec: Long): String =
    if (sec >= 60) "${sec / 60} 分 ${sec % 60} 秒" else "$sec 秒"

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/**
 * The dictation surface: countdown ring with the current word hidden by
 * default (tap to reveal), POS/meaning hints, 标记错词, prev/pause/next/stop,
 * and the live interval slider + auto-next toggle. Leaving mid-session asks
 * for confirmation; a finished session shows the score card with the 错词本
 * (复习错词 / 导出错词 / 移除 / 清空) and exits directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictationScreen(
    onClose: () -> Unit,
    viewModel: DictationViewModel = viewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val runLines by viewModel.activeLines.collectAsStateWithLifecycle()
    var showWord by remember { mutableStateOf(false) }
    var metaExpanded by remember { mutableStateOf(false) }
    var exitDialogVisible by remember { mutableStateOf(false) }

    // A dictation session runs for minutes with nothing to touch — keep the
    // screen awake while it is up (active, paused or the finish card). Leaving
    // the screen clears the flag, so the screen timeout applies again.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // The word re-hides on every word change (reveal must not leak across words).
    LaunchedEffect(ui.index) {
        showWord = false
        metaExpanded = false
    }
    // … and when a new run starts (复习错词 round) even if the index is unchanged.
    LaunchedEffect(ui.finished) {
        if (!ui.finished) {
            showWord = false
            metaExpanded = false
        }
    }

    val requestStop: () -> Unit = {
        if (ui.isActive && !ui.finished) exitDialogVisible = true else onClose()
    }
    // System back during dictation asks for confirmation, never exits
    // silently — and a finished/idle run still funnels through onClose so
    // every exit lands Home (requestStop dispatches: active → dialog,
    // otherwise → onClose).
    BackHandler { requestStop() }

    if (exitDialogVisible) {
        AlertDialog(
            onDismissRequest = { exitDialogVisible = false },
            title = { Text("结束听写？") },
            text = { Text("本次听写尚未完成，退出后进度将丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    exitDialogVisible = false
                    viewModel.stop()
                    onClose()
                }) { Text("结束") }
            },
            dismissButton = {
                TextButton(onClick = { exitDialogVisible = false }) { Text("继续听写") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("听写") },
                navigationIcon = {
                    IconButton(onClick = { requestStop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出听写")
                    }
                },
                actions = { StatusPill(ui) },
            )
        },
    ) { innerPadding ->
        when {
            !ui.ready -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            ui.total == 0 -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("没有可听写的词表")
                    TextButton(onClick = onClose) { Text("返回") }
                }
            }

            else -> DictationContent(
                ui = ui,
                runLines = runLines,
                viewModel = viewModel,
                showWord = showWord,
                onToggleWord = { showWord = !showWord },
                metaExpanded = metaExpanded,
                onToggleMeta = { metaExpanded = !metaExpanded },
                onRequestStop = { requestStop() },
                onClose = onClose,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
        }
    }
}

@Composable
private fun StatusPill(ui: DictationUiState) {
    val doneColor = hearWriteSemantics.success
    val (label, color) = when {
        ui.finished -> "已完成" to doneColor
        ui.state == PlayState.PLAYING -> "听写中" to doneColor
        ui.state == PlayState.PAUSED -> "已暂停" to MaterialTheme.colorScheme.tertiary
        else -> "未开始" to MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier
            .padding(end = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DictationContent(
    ui: DictationUiState,
    runLines: List<String>,
    viewModel: DictationViewModel,
    showWord: Boolean,
    onToggleWord: () -> Unit,
    metaExpanded: Boolean,
    onToggleMeta: () -> Unit,
    onRequestStop: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Position counter: the word being dictated (index + 1) while
        // active; at completion the engine index parks on the last word, so
        // the finished run shows total / total (the bar matches the count).
        val shown = if (ui.finished) ui.total else (ui.index + 1).coerceAtMost(ui.total)
        val progress = if (ui.total > 0) shown.toFloat() / ui.total else 0f
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "听写进度" },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$shown / ${ui.total}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.wrongWords.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = CircleShape) {
                    Text(
                        "错词本 ${ui.wrongWords.size} 词",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
        }

        // ---- stage -------------------------------------------------------
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (ui.finished) {
                FinishCard(
                    ui = ui,
                    onReviewWrong = viewModel::reviewWrongWords,
                    onExportWrong = viewModel::exportWrongWords,
                    onClearWrong = viewModel::clearWrongWords,
                    onRemoveWrong = viewModel::removeWrongWord,
                    onClose = onClose,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    WatchDial(
                        ui = ui,
                        line = runLines.getOrNull(ui.index),
                        showWord = showWord,
                        metaExpanded = metaExpanded,
                        onToggleMeta = onToggleMeta,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = onToggleWord,
                            enabled = ui.isActive,
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) {
                            Icon(
                                if (showWord) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (showWord) "隐藏词语" else "显示词语")
                        }
                        FilledTonalButton(
                            onClick = viewModel::markCurrentWrong,
                            enabled = ui.isActive,
                            modifier = Modifier.weight(1f).height(52.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Text("标记错词")
                        }
                    }
                }
            }
        }

        // ---- playback controls -------------------------------------------
        PlaybackPanel(
            ui = ui,
            onIntervalChange = { viewModel.onIntervalChange(it.toDouble()) },
            onAutoNextChange = viewModel::onAutoNextChange,
            onPlayToggle = viewModel::togglePlay,
            onPrevious = viewModel::goToPrevious,
            onNext = viewModel::skipToNext,
        )
    }
}

/**
 * Score card of a finished run: 词数 / 正确数 / 用时, then the 错词本 actions —
 * 复习错词 re-runs a dictation over exactly the wrong set, 导出错词 copies the
 * words to the clipboard (pasteable back into the Home input), chips remove
 * single words and 清空错词本 empties the book.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FinishCard(
    ui: DictationUiState,
    onReviewWrong: () -> Unit,
    onExportWrong: () -> Int,
    onClearWrong: () -> Unit,
    onRemoveWrong: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val wrong = ui.wrongWords
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val doneColor = hearWriteSemantics.success
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = doneColor,
            modifier = Modifier.size(64.dp),
        )
        Text(
            "听写完成",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "共 ${ui.total} 词 · 用时 ${ui.elapsedSec?.let(::formatElapsed) ?: "—"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        // Score = this run's marks (repeat offenders included), not the size
        // of the persisted book, which may hold words from earlier sessions.
        if (ui.runWrongCount > 0) {
            val correct = (ui.total - ui.runWrongCount).coerceAtLeast(0)
            Text(
                "正确 $correct 词 · 错词 ${ui.runWrongCount}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (wrong.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onReviewWrong, modifier = Modifier.weight(1f)) {
                    Text("复习错词")
                }
                OutlinedButton(
                    onClick = {
                        val n = onExportWrong()
                        if (n > 0) toast(context, "已复制 $n 个错词到剪贴板")
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("导出错词")
                }
            }
        }

        if (wrong.isNotEmpty()) {
            Text(
                "错词本（${wrong.size}）· 点按移除",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            FlowRow(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                wrong.forEach { word ->
                    AssistChip(
                        onClick = {
                            onRemoveWrong(word)
                            toast(context, "已移除 $word")
                        },
                        label = { Text(word) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "移除 $word",
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
            TextButton(onClick = {
                onClearWrong()
                toast(context, "已清空错词本")
            }) {
                Text("清空错词本", color = MaterialTheme.colorScheme.error)
            }
        }

        Button(onClick = onClose, modifier = Modifier.padding(top = 12.dp)) { Text("返回") }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The ring + reveal zone. Word hidden by default: dots and a hint; tapping
 * the dial (or the eye button) reveals word, pinyin/POS and meaning. The
 * border flashes red while a wrong-word mark is in flight.
 */
@Composable
private fun WatchDial(
    ui: DictationUiState,
    line: String?,
    showWord: Boolean,
    metaExpanded: Boolean,
    onToggleMeta: () -> Unit,
) {
    val entry = remember(line) { line?.let(::parseWordLine) }
    val isCjk = remember(line) { line?.let(::isCjkEntry) ?: false }

    val fraction = ui.remainingMs?.let {
        val totalMs = (ui.intervalSec * 1000).coerceAtLeast(1.0)
        (it / totalMs).coerceIn(0.0, 1.0).toFloat()
    }
    val ringColor = MaterialTheme.colorScheme.primary
    val trackColor = hearWriteSemantics.ringTrack

    val seconds = ui.remainingMs?.let { ceil(it / 1000.0).toInt() }
    val counting = ui.isActive && seconds != null

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CountdownRing(
            progressFraction = fraction,
            modifier = Modifier.size(248.dp),
            color = ringColor,
            trackColor = trackColor,
        ) {
            DialCenter(
                entry = entry,
                isCjk = isCjk,
                markedFlash = ui.markedFlash,
                showWord = showWord,
                metaExpanded = metaExpanded,
                onToggleMeta = onToggleMeta,
            )
        }

        // Seconds readout; clearAndSetSemantics re-announces on each whole
        // second so TalkBack reports the shrinking countdown.
        if (counting) {
            Text(
                "$seconds 秒",
                style = MaterialTheme.typography.displaySmall,
                color = ringColor,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "剩余 $seconds 秒"
                    liveRegion = LiveRegionMode.Polite
                },
            )
        } else {
            Text(
                "—",
                style = MaterialTheme.typography.headlineSmall,
                color = trackColor,
            )
        }
    }
}

/** Inner tap zone of the dial: word/dots + hints (reveal state) + flash border. */
@Composable
private fun DialCenter(
    entry: WordEntry?,
    isCjk: Boolean,
    markedFlash: Boolean,
    showWord: Boolean,
    metaExpanded: Boolean,
    onToggleMeta: () -> Unit,
) {
    val flashColor by animateColorAsState(
        targetValue = if (markedFlash) MaterialTheme.colorScheme.error else Color.Transparent,
        label = "markFlash",
    )
    val borderColor = if (markedFlash) flashColor else MaterialTheme.colorScheme.outlineVariant
    val meaning = entry?.meaning
    val meaningLong = (meaning?.length ?: 0) > 26

    Surface(
        modifier = Modifier
            .size(204.dp)
            .border(
                width = 2.dp,
                color = if (markedFlash) borderColor else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (showWord && entry != null) {
                Text(
                    entry.word,
                    style = MaterialTheme.typography.displayMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.pos?.let { pos ->
                    Text(
                        pos,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (!meaning.isNullOrEmpty()) {
                    Text(
                        meaning,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = if (metaExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (meaningLong) {
                        TextButton(onClick = onToggleMeta) {
                            Text(if (metaExpanded) "收起" else "展开全部")
                        }
                    }
                }
            } else {
                Icon(
                    Icons.Filled.Hearing,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    "听写中",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "用下方按钮显示词语",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Bottom panel: live interval slider + auto-next + transport buttons. */
@Composable
private fun PlaybackPanel(
    ui: DictationUiState,
    onIntervalChange: (Float) -> Unit,
    onAutoNextChange: (Boolean) -> Unit,
    onPlayToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            // Interval stepper — live: the engine restarts the countdown on
            // change, so the pace changes immediately (★ acceptance). A
            // stepper replaces the slider here to keep the panel one row tall.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("间隔", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val stepped = ((ui.intervalSec - 0.5).coerceIn(
                            MIN_INTERVAL_SEC.toDouble(),
                            MAX_INTERVAL_SEC.toDouble(),
                        ))
                        onIntervalChange(stepped.toFloat())
                    },
                    enabled = ui.intervalSec > MIN_INTERVAL_SEC,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = "减少间隔")
                }
                Text(
                    String.format(Locale.ROOT, "%.1fs", ui.intervalSec),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { contentDescription = "听写间隔秒数" },
                )
                IconButton(
                    onClick = {
                        val stepped = ((ui.intervalSec + 0.5).coerceIn(
                            MIN_INTERVAL_SEC.toDouble(),
                            MAX_INTERVAL_SEC.toDouble(),
                        ))
                        onIntervalChange(stepped.toFloat())
                    },
                    enabled = ui.intervalSec < MAX_INTERVAL_SEC,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "增加间隔")
                }
                Spacer(Modifier.weight(1f))
                Text("自动播放", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(4.dp))
                Switch(
                    checked = ui.autoNext,
                    onCheckedChange = onAutoNextChange,
                    modifier = Modifier.semantics { contentDescription = "自动播放下一个词" },
                )
            }

            // ---- transport ----------------------------------------------------
            val playing = ui.state == PlayState.PLAYING
            val paused = ui.state == PlayState.PAUSED
            val active = ui.isActive
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious, enabled = active && ui.index > 0) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一个")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = onPlayToggle,
                        enabled = active,
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "暂停" else "继续",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Text(
                        if (playing) "暂停" else if (paused) "继续" else "播放",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                IconButton(onClick = onNext, enabled = active) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一个")
                }
            }
        }
    }
}

