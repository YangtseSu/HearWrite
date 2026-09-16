package org.yangtse.hearwrite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CameraAlt
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
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.outlined.BarChart
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.yangtse.hearwrite.ui.theme.hearWriteSemantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.yangtse.hearwrite.domain.DialMetrics
import org.yangtse.hearwrite.domain.MAX_INTERVAL_SEC
import org.yangtse.hearwrite.domain.MIN_INTERVAL_SEC
import org.yangtse.hearwrite.domain.PlayState
import org.yangtse.hearwrite.domain.WordEntry
import org.yangtse.hearwrite.domain.dialBoxWidthDp
import org.yangtse.hearwrite.domain.dialFit
import org.yangtse.hearwrite.domain.isCjkEntry
import org.yangtse.hearwrite.domain.parseWordLine
import org.yangtse.hearwrite.domain.speakTextFromEntry
import kotlin.math.ceil

/**
 * Dial geometry. [DIAL_RING_SIZE] is the countdown ring; the word sits on
 * [DIAL_INNER_SIZE]'s disc, which clips every child to its circle — the reason
 * the contents are sized by [dialFit] instead of a hand-picked stack
 * (AUDIT C2: the 展开全部 button used to fall outside the disc and vanish).
 */
private val DIAL_RING_SIZE = 248.dp
private val DIAL_INNER_SIZE = 204.dp

/**
 * What the dial's content is measured against — disc diameter, font sizes and
 * line boxes of the styles it renders. Kept next to the composables that use
 * them so the two cannot drift, and read as data by the fit solver in
 * `domain/DialFit.kt`.
 */
private val DIAL_METRICS = DialMetrics(
    diameterDp = DIAL_INNER_SIZE.value.toDouble(),
    wordMaxSp = 40.0, // displayMedium
    wordMinSp = 22.0,
    wordLineRatio = 52.0 / 40.0, // displayMedium's leading ÷ its size
    hintFontSizeSp = 15.0, // bodyMedium
    hintLineHeightSp = 24.0,
    wordPosGapDp = 6.0,
    glossMaxLines = 2,
    glossGapDp = 2.0,
)

/** Height of the 展开全部 row under the dial — a Material text button. */
private val DIAL_DETAIL_ROW_HEIGHT = 40.dp

/** Chips the finish card composes before offering 查看全部. */
private const val WRONG_CHIP_CAP = 24

/** Width the countdown slot reserves: the widest readout it ever shows. */
private val COUNTDOWN_SLOT_TEXT = "${MAX_INTERVAL_SEC.toInt()} 秒"



/**
 * The dictation surface: countdown ring with the current word hidden by
 * default (revealed with the 显示词语 button below the dial), POS/meaning
 * hints, 标记错词, prev/pause/next/stop, and the live interval stepper +
 * auto-next toggle. Leaving mid-session asks for confirmation; a finished
 * session shows the score card with the 错词本 (复习错词 / 导出错词 / 移除 /
 * 清空) plus 听写统计 — the page holding the row this run just wrote — and
 * exits directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictationScreen(
    onClose: () -> Unit,
    onOpenStats: () -> Unit,
    viewModel: DictationViewModel = viewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val runLines by viewModel.activeLines.collectAsStateWithLifecycle()
    var showWord by remember { mutableStateOf(false) }
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
    }
    // … and when a new run starts (复习错词 round) even if the index is unchanged.
    LaunchedEffect(ui.finished) {
        if (!ui.finished) {
            showWord = false
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

    // The screen-level confirmation channel (replacing the floating system
    // pop-ups app-wide): Scaffold hosts it above the bottom content and applies
    // navigation-bar/IME insets, so a message lands where the tap happened
    // instead of floating over the window.
    val messages = rememberMessageController()

    CompositionLocalProvider(LocalMessages provides messages) {
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
            snackbarHost = { MessageHost(messages) },
        ) { innerPadding ->
            when {
                !ui.ready -> Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    // Same register as Home's busy start button: the session
                    // reads settings, the 组词 tables and the 错词本 before it
                    // can speak, and a bare spinner gave no reason for the
                    // wait.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            "准备听写…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }

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
                    onRequestStop = { requestStop() },
                    onClose = onClose,
                    onOpenStats = onOpenStats,
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )
            }
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
    onRequestStop: () -> Unit,
    onClose: () -> Unit,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages = LocalMessages.current
    // ---- 拍照批改 (Roadmap #11) --------------------------------------------
    val gradePane by viewModel.gradePane.collectAsStateWithLifecycle()
    val gradeResult by viewModel.gradeResult.collectAsStateWithLifecycle()
    val gradeSelected by viewModel.gradeSelected.collectAsStateWithLifecycle()
    val gradeBusy by viewModel.gradeBusy.collectAsStateWithLifecycle()
    val gradePhase by viewModel.gradePhase.collectAsStateWithLifecycle()
    val gradeError by viewModel.gradeError.collectAsStateWithLifecycle()
    val gradeRetryable by viewModel.gradeRetryable.collectAsStateWithLifecycle()
    val gradeNotice by viewModel.gradeNotice.collectAsStateWithLifecycle()
    val cropBitmap by viewModel.cropBitmap.collectAsStateWithLifecycle()
    val cropLoading by viewModel.cropLoading.collectAsStateWithLifecycle()
    val gradePicker = rememberOcrImagePicker(
        onPicked = { uri -> viewModel.startCrop(uri) },
        onError = { message -> messages.show(message) },
    )
    // The crop overlay owns system back while it is up (back leaves the region
    // selection, not the dictation): this handler is registered after the
    // screen's requestStop one, so it wins for as long as it is enabled.
    BackHandler(enabled = cropBitmap != null || cropLoading) { viewModel.cancelCrop() }
    // 拍照批改 pane: back returns to the score card. Registered after the crop
    // handler so the crop step still wins while it is up; without this the
    // screen's requestStop() sees finished+inactive and exits the whole run,
    // dropping the grade result and the ticked rows.
    BackHandler(enabled = gradePane && cropBitmap == null && !cropLoading) {
        viewModel.closeGradePane()
    }
    // One-shot confirmation of a 拍照批改 write (记入错词本 N 个词).
    LaunchedEffect(gradeNotice) {
        gradeNotice?.let { message ->
            messages.show(message)
            viewModel.clearGradeNotice()
        }
    }

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
            if (ui.runWrongCount > 0) {
                Spacer(Modifier.width(12.dp))
                // This run's marks, not the book's length: the pill sits next
                // to the progress counter, and the score card's 错词 number
                // must be the same number the student watched grow. The
                // global book already has its own place (成绩卡 错词本（N）
                // + chips, AUDIT C2 "同屏两义").
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = CircleShape) {
                    Text(
                        "本场错词 ${ui.runWrongCount} 词",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
        }

        // ---- audio failure banner -----------------------------------------
        // A failed pass never retries (by design), so a run whose audio never
        // came through used to look exactly like a normal one. The banner
        // appears the moment the first pass fails and clears never — the
        // student must know the silence is the app, not the phone's volume.
        if (ui.speechFailures > 0 && !ui.finished) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.VolumeOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "本场有 ${ui.speechFailures} 次发音失败，请检查音量与发音来源设置",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
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
                if (gradePane) {
                    DictationGradePane(
                        result = gradeResult,
                        selected = gradeSelected,
                        busy = gradeBusy,
                        phase = gradePhase,
                        error = gradeError,
                        pickerBusy = gradePicker.busy,
                        retryable = gradeRetryable,
                        onCamera = gradePicker.launchCamera,
                        onGallery = gradePicker.launchGallery,
                        onRetry = viewModel::retryGrade,
                        onToggle = viewModel::toggleGradeSelected,
                        onConfirm = viewModel::confirmGrade,
                        onBack = viewModel::closeGradePane,
                        onCancel = viewModel::cancelGrade,
                    )
                    // 选定识别区域 step: the same crop overlay as 拍照识词.
                    if (cropBitmap != null || cropLoading) {
                        OcrCropOverlay(
                            bitmap = cropBitmap,
                            onConfirm = { rect -> viewModel.confirmCrop(rect) },
                            onDismiss = { viewModel.cancelCrop() },
                        )
                    }
                } else {
                    FinishCard(
                        ui = ui,
                        onReplay = viewModel::replayRun,
                        onReviewWrong = viewModel::reviewWrongWords,
                        onExportWrong = viewModel::exportWrongWords,
                        onClearWrong = viewModel::clearWrongWords,
                        onRemoveWrong = viewModel::removeWrongWord,
                        onGrade = viewModel::openGradePane,
                        onOpenStats = onOpenStats,
                        onClose = onClose,
                    )
                }
            } else {
                val headword = remember(runLines, ui.index) {
                    runLines.getOrNull(ui.index)?.let(::speakTextFromEntry).orEmpty()
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    WatchDial(
                        ui = ui,
                        line = runLines.getOrNull(ui.index),
                        showWord = showWord,
                        onToggleWord = onToggleWord,
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
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .padding(horizontal = 4.dp),
                        ) {
                            Icon(
                                if (showWord) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (showWord) "隐藏词语" else "显示词语")
                        }
                        // Marking the current word is a toggle: the same button
                        // takes the mark back, so a mis-tap mid-run no longer
                        // costs the score (AUDIT C2). The label says which way
                        // it will go, and 取消标记 — an undo of a mark *this* run
                        // made — is tonally quiet, not the error container the
                        // destructive 标记 keeps.
                        val marked = headword in ui.runMarks
                        Button(
                            onClick = viewModel::toggleCurrentWrong,
                            enabled = ui.isActive,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .padding(horizontal = 4.dp),
                            colors = if (marked) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            },
                        ) {
                            Text(if (marked) "取消标记" else "标记错词")
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
            onStop = onRequestStop,
            onPrevious = viewModel::goToPrevious,
            onNext = viewModel::skipToNext,
        )
    }
}

/**
 * Score card of a finished run: 词数 / 正确数 / 用时, then the 错词本 actions —
 * 再听一遍 (primary) replays this run's words in its own order, 复习错词
 * (tonal = secondary, only shown when the book is non-empty) re-runs a
 * dictation over exactly the wrong set (each mark restored to its original
 * line) — a scope line under the pair spells out that difference. 拍照批改
 * grades the student's photographed answer sheet against this run's words
 * (pre-checked against the stored OCR config, see
 * [DictationViewModel.openGradePane]), 导出错词 copies the words to the
 * clipboard (pasteable back into the Home input), chips remove single words
 * and 清空错词本 empties the book.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FinishCard(
    ui: DictationUiState,
    onReplay: () -> Unit,
    onReviewWrong: () -> Unit,
    onExportWrong: () -> Int,
    onClearWrong: () -> Unit,
    onRemoveWrong: (String) -> Unit,
    onGrade: () -> Unit,
    onOpenStats: () -> Unit,
    onClose: () -> Unit,
) {
    val messages = LocalMessages.current
    val wrong = ui.wrongWords
    var clearWrongConfirm by remember { mutableStateOf(false) }
    var showAllWrong by remember { mutableStateOf(false) }
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
            "共 ${ui.total} 词 · 用时 ${ui.elapsedSec?.let(::formatDuration) ?: "—"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        // Score = this run's marks (repeat offenders included), not the size
        // of the persisted book, which may hold words from earlier sessions.
        // Rendered even at zero: the card used to drop the line on a perfect
        // run, which read exactly like a card that had failed to compute it —
        // 满分 and "no score shown" were the same picture (AUDIT C2).
        val correct = (ui.total - ui.runWrongCount).coerceAtLeast(0)
        Text(
            if (ui.runWrongCount == 0) {
                "正确 $correct 词 · 错词 0 · 满分"
            } else {
                "正确 $correct 词 · 错词 ${ui.runWrongCount}（本场）"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        // A run whose audio failed must say so on the card too: a silent run
        // produces no marks, and the parent reading 正确 N 词 would otherwise
        // take the score at face value.
        if (ui.speechFailures > 0) {
            Text(
                "本场有 ${ui.speechFailures} 次发音失败，成绩可能不准",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 再听一遍: the same words in the same order (起始序号 slice +
            // 随机顺序 already baked into the run's lines) — no re-preparation.
            // It leads the row (solid Button) because it is the plain repeat;
            // 复习错词 is the narrower follow-up (tonal = secondary).
            Button(onClick = onReplay, modifier = Modifier.weight(1f)) {
                Text("再听一遍")
            }
            if (wrong.isNotEmpty()) {
                FilledTonalButton(onClick = onReviewWrong, modifier = Modifier.weight(1f)) {
                    Text("复习错词")
                }
            }
        }
        // Scope line (AUDIT C1c): the two buttons looked equally weighted with
        // no hint of what each one replays — 再听一遍 = this run's words in its
        // order, 复习错词 = only the wrong words the 错词本 holds. Both counts
        // are spelled out because the two are different sets: the book is
        // persisted across sessions, the run is not.
        if (wrong.isNotEmpty()) {
            Text(
                "再听一遍按本场顺序重播本场 ${ui.total} 词；" +
                    "复习错词只练错词本里的 ${wrong.size} 个词",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // 拍照批改: photograph the student's answer sheet and grade it against
        // this run's words (the marks become 错词本 entries after confirmation).
        OutlinedButton(
            onClick = onGrade,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("拍照批改", modifier = Modifier.padding(start = 8.dp))
        }

        if (wrong.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    val n = onExportWrong()
                    if (n > 0) messages.show("已复制 $n 个错词到剪贴板")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text("导出错词")
            }
        }

        if (wrong.isNotEmpty()) {
            Text(
                "错词本（${wrong.size}）· 点按移除",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            // The book is persisted and unbounded, so the card composes the
            // worst few and offers the rest: a FlowRow inside a verticalScroll
            // has no lazy behavior of its own, and a 500-word book would
            // compose — and measure — every chip on the finish card
            // (AUDIT C2). The list is already most-wrong-first, so the cap keeps
            // the words worth acting on.
            val shown = if (showAllWrong) wrong else wrong.take(WRONG_CHIP_CAP)
            FlowRow(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                shown.forEach { word ->
                    AssistChip(
                        onClick = {
                            onRemoveWrong(word)
                            messages.show("已移除 $word")
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
            if (wrong.size > WRONG_CHIP_CAP) {
                TextButton(onClick = { showAllWrong = !showAllWrong }) {
                    Text(
                        if (showAllWrong) {
                            "收起"
                        } else {
                            "查看全部 ${wrong.size} 个错词"
                        },
                    )
                }
            }
            TextButton(onClick = { clearWrongConfirm = true }) {
                Text("清空错词本", color = MaterialTheme.colorScheme.error)
            }
            // The book is persisted across sessions: same confirm as the Home
            // drawer's 清空 (a mis-tap on the card would wipe it silently).
            if (clearWrongConfirm) {
                AlertDialog(
                    onDismissRequest = { clearWrongConfirm = false },
                    title = { Text("清空错词本？") },
                    text = { Text("将删除全部 ${wrong.size} 个错词。") },
                    confirmButton = {
                        TextButton(onClick = {
                            clearWrongConfirm = false
                            onClearWrong()
                            messages.show("已清空错词本")
                        }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { clearWrongConfirm = false }) { Text("取消") }
                    },
                )
            }
        }

        // The run just wrote its record (Roadmap #3), so the card that
        // announces the score is also the natural way into the page that
        // shows it: without this the only path was 首页 → 更多 → 听写统计,
        // two taps deep and away from the moment the record was made
        // (AUDIT C5). Home's header cannot grow a fourth icon — at 360dp the
        // wordmark plus three 48dp icons already fill the row — so the depth
        // is reduced where there is room instead. The label is the same string
        // Home's menu uses (no second name for one destination) and claims
        // nothing about the write: the row lands asynchronously and a failed
        // write is tolerated by design.
        TextButton(
            onClick = onOpenStats,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Icon(
                Icons.Outlined.BarChart,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text("听写统计", modifier = Modifier.padding(start = 8.dp))
        }

        Button(onClick = onClose, modifier = Modifier.padding(top = 12.dp)) { Text("返回") }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The ring + reveal zone. Word hidden by default: a hearing icon and a hint;
 * the 显示词语 button below the dial reveals word, pinyin/POS and meaning.
 * The border flashes red while a wrong-word mark is in flight.
 *
 * Every child is laid out inside a box solved by [dialFit] for the stack's own
 * height, so the disc's clip can never reach it; a stack that outgrows the disc
 * says so with an 展开全部 button under the dial — outside the clip, where no
 * geometry can swallow it — rather than being silently cut in half (AUDIT C2).
 */
@Composable
private fun WatchDial(
    ui: DictationUiState,
    line: String?,
    showWord: Boolean,
    onToggleWord: () -> Unit,
) {
    val entry = remember(line) { line?.let(::parseWordLine) }
    val isCjk = remember(line) { line?.let(::isCjkEntry) ?: false }
    var detailOpen by remember(entry?.word) { mutableStateOf(false) }

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
            modifier = Modifier
                .size(DIAL_RING_SIZE)
                // Tap-to-reveal (AGENTS.md:94 "tap to reveal, the core
                // interaction"): the whole dial is the target, so a student
                // glancing up from paper hits it anywhere — the 显示词语
                // button below stays as the visible label. Gated on isActive
                // like that button, and merged into one semantics node so
                // TalkBack reads the same action the screen shows.
                .clickable(
                    enabled = ui.isActive,
                    role = Role.Button,
                    onClickLabel = if (showWord) "隐藏词语" else "显示词语",
                    onClick = onToggleWord,
                ),
            color = ringColor,
            trackColor = trackColor,
        ) {
            DialCenter(
                entry = entry,
                isCjk = isCjk,
                markedFlash = ui.markedFlash,
                showWord = showWord,
                playing = ui.state == PlayState.PLAYING,
            )
        }

        // The 展开全部 entry lives under the dial, not inside it: the disc's
        // clip is the reason the old one was invisible (AUDIT C2). The row is
        // always laid out, whether or not this word needs it — same reasoning
        // as the countdown slot below: revealing a word must not shift the dial
        // and the seconds readout the student is watching, and a long word only
        // changes what the row contains.
        Box(
            modifier = Modifier.height(DIAL_DETAIL_ROW_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            if (showWord && entry != null) {
                val metrics = DIAL_METRICS.copy(
                    fontScale = LocalDensity.current.fontScale.toDouble(),
                )
                if (dialFit(entry.word, entry.meaning, entry.pos != null, metrics).needsDetail) {
                    TextButton(onClick = { detailOpen = true }) {
                        Text("展开全部")
                    }
                }
            }
        }

        // Seconds readout; clearAndSetSemantics re-announces on each whole
        // second so TalkBack reports the shrinking countdown. An invisible
        // sizer reserves the slot: same style AND same script mix (digit +
        // CJK) as the live text, so the same fallback fonts measure and the
        // row never changes size when the countdown appears/disappears. No
        // fixed dp: the slot grows with the system font scale instead of
        // clipping (a min-height cannot cover every scale; a fixed height
        // clips the text once the scale outgrows it). The sizer is cleared
        // from semantics — alpha(0f) hides it visually but not from TalkBack.
        // It reserves the widest readout ("10 秒", [COUNTDOWN_SLOT_TEXT]), which
        // is what makes the claim hold: an "8 秒" placeholder was a glyph
        // narrower than the 10 s countdown it stood in for (AUDIT C2).
        Box(
            contentAlignment = Alignment.Center,
        ) {
            Text(
                COUNTDOWN_SLOT_TEXT,
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier
                    .alpha(0f)
                    .clearAndSetSemantics {},
            )
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
                    style = MaterialTheme.typography.displaySmall,
                    color = trackColor,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }

    if (detailOpen && entry != null) {
        DialDetailDialog(
            entry = entry,
            isCjk = isCjk,
            onDismiss = { detailOpen = false },
        )
    }
}

/**
 * Inner display zone of the dial. Revealed: the word (font size solved by
 * [dialFit] so it holds its lines inside the disc) over its POS/拼音 and
 * meaning/组词 hints; hidden: the hearing icon and the state copy. Everything
 * is laid out inside the box [dialFit] computed for this very stack, so the
 * disc's clip cannot reach any child and there is nothing to scroll
 * (AUDIT C2). The border flashes red while a wrong-word mark is in flight.
 */
@Composable
private fun DialCenter(
    entry: WordEntry?,
    isCjk: Boolean,
    markedFlash: Boolean,
    showWord: Boolean,
    playing: Boolean,
) {
    val flashColor by animateColorAsState(
        targetValue = if (markedFlash) MaterialTheme.colorScheme.error else Color.Transparent,
        label = "markFlash",
    )
    // The vermilion marks Chinese-script identity only (Color.kt): a Chinese
    // single char's `pos` line is its pinyin and its `meaning` line is the
    // 组词, so both carry the accent; an English entry's POS/释义 stay
    // onSurfaceVariant — the accent is never a part-of-speech label.
    val hintColor = if (isCjk) {
        hearWriteSemantics.cjkAccent
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val metrics = DIAL_METRICS.copy(fontScale = LocalDensity.current.fontScale.toDouble())
    val fit = dialFit(entry?.word, entry?.meaning, entry?.pos != null, metrics)

    Surface(
        modifier = Modifier
            .size(DIAL_INNER_SIZE)
            .border(
                width = 2.dp,
                color = if (markedFlash) flashColor else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (showWord && entry != null) {
                Column(
                    modifier = Modifier.size(
                        width = fit.contentWidthDp.dp,
                        height = fit.contentHeightDp.dp,
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // The word is solved, not clamped: a long headword shrinks
                    // until it holds its lines inside the box (the two-line
                    // 40 sp stack overflowed the disc and lost its first line,
                    // AUDIT C2). Line height comes from the fit so the box the
                    // geometry assumed is the box the text occupies.
                    Text(
                        entry.word,
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontSize = fit.wordFontSizeSp.sp,
                            lineHeight = fit.wordLineHeightSp.sp,
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = fit.wordLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    entry.pos?.let { pos ->
                        Text(
                            pos,
                            style = MaterialTheme.typography.bodyMedium,
                            color = hintColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = DIAL_METRICS.wordPosGapDp.dp),
                        )
                    }
                    if (!entry.meaning.isNullOrEmpty() && fit.glossLines > 0) {
                        Text(
                            entry.meaning,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            maxLines = fit.glossLines,
                            overflow = TextOverflow.Ellipsis,
                            color = hintColor,
                            modifier = Modifier.padding(top = DIAL_METRICS.glossGapDp.dp),
                        )
                    }
                }
            } else {
                // The hidden stack (icon + state word + tap hint) is fixed
                // length, so its box needs no solving: the natural height feeds
                // dialBoxWidthDp, and the column is width-constrained only —
                // its height IS that stack, so the width computed for it is
                // inside the disc by construction.
                val hiddenHeightDp = DIAL_ICON_SIZE_DP +
                    DIAL_ICON_GAP_DP +
                    DIAL_STATE_LINE_SP * metrics.fontScale +
                    DIAL_STATE_GAP_DP +
                    DIAL_HINT_LINE_SP * metrics.fontScale
                val boxWidth = dialBoxWidthDp(hiddenHeightDp, metrics)
                Column(
                    modifier = Modifier.width(boxWidth.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.Hearing,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        // The dial must not claim 听写中 while the pill says
                        // 已暂停 (AUDIT C2): the state word follows playback.
                        if (playing) "听写中" else "已暂停",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "点按显示词语",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** The hidden dial's stack: hearing icon size, and the gaps under it (dp). */
private const val DIAL_ICON_SIZE_DP = 40.0
private const val DIAL_ICON_GAP_DP = 8.0
private const val DIAL_STATE_GAP_DP = 4.0
/** `labelMedium`'s line height, for the 点按显示词语 hint. */
private const val DIAL_HINT_LINE_SP = 19.0
/** `titleMedium`'s line height, for the 听写中 state word. */
private const val DIAL_STATE_LINE_SP = 24.0

/**
 * Full text of the current word — the dial's escape hatch for a headword or
 * gloss the disc cannot hold (AUDIT C2). A dialog is the one container on this
 * screen that is not the circle, so the text is readable at the style's own
 * size instead of the fit's floor.
 */
@Composable
private fun DialDetailDialog(
    entry: WordEntry,
    isCjk: Boolean,
    onDismiss: () -> Unit,
) {
    val hintColor = if (isCjk) {
        hearWriteSemantics.cjkAccent
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                entry.word,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                entry.pos?.let { pos ->
                    Text(
                        pos,
                        style = MaterialTheme.typography.bodyMedium,
                        color = hintColor,
                    )
                }
                if (!entry.meaning.isNullOrEmpty()) {
                    Text(
                        entry.meaning,
                        style = MaterialTheme.typography.bodyLarge,
                        color = hintColor,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * Bottom panel: live interval stepper + auto-next + transport buttons. Each
 * transport control (结束 / 上一个 / play / 下一个) carries a `labelMedium`
 * caption under its icon, so the destructive 结束 does not read as just
 * another icon next to the two skips; the caption text is the icon's
 * `contentDescription` verbatim.
 */
@Composable
private fun PlaybackPanel(
    ui: DictationUiState,
    onIntervalChange: (Float) -> Unit,
    onAutoNextChange: (Boolean) -> Unit,
    onPlayToggle: () -> Unit,
    onStop: () -> Unit,
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
            // Both buttons carry the 48dp touch target rather than 40dp: a
            // change mid-countdown restarts the wait (by design), so a stray
            // tap next to the transport row costs the student up to a full
            // interval — a smaller hit box is a cost with no upside
            // (AUDIT C2).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("间隔", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val stepped = (ui.intervalSec - 0.5).coerceIn(
                            MIN_INTERVAL_SEC,
                            MAX_INTERVAL_SEC,
                        )
                        onIntervalChange(stepped.toFloat())
                    },
                    enabled = ui.intervalSec > MIN_INTERVAL_SEC,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = "减少间隔")
                }
                Text(
                    formatInterval(ui.intervalSec),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(
                    onClick = {
                        val stepped = (ui.intervalSec + 0.5).coerceIn(
                            MIN_INTERVAL_SEC,
                            MAX_INTERVAL_SEC,
                        )
                        onIntervalChange(stepped.toFloat())
                    },
                    enabled = ui.intervalSec < MAX_INTERVAL_SEC,
                    modifier = Modifier.size(48.dp),
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
            // Single source of truth for the play control: the visible label
            // under the button and the icon's contentDescription must match
            // (A3 — TalkBack reads the same state the screen shows).
            val playLabel = if (playing) "暂停" else if (paused) "继续" else "播放"
            // Every transport control carries a visible label (C1c): the row
            // used to caption only the play button, which left the destructive
            // 结束 reading as just another icon next to the two skips. Each
            // label doubles as the icon's contentDescription, so the screen
            // reader can never hear something the visible text does not say.
            val stopLabel = "结束"
            val prevLabel = "上一个"
            val nextLabel = "下一个"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onStop, enabled = active) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = stopLabel,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        stopLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onPrevious, enabled = active && ui.index > 0) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = prevLabel)
                    }
                    Text(
                        prevLabel,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = onPlayToggle,
                        enabled = active,
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = playLabel,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Text(
                        playLabel,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onNext, enabled = active) {
                        Icon(Icons.Filled.SkipNext, contentDescription = nextLabel)
                    }
                    Text(
                        nextLabel,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

