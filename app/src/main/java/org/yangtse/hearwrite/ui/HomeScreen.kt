package org.yangtse.hearwrite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.yangtse.hearwrite.HearWriteApplication

/**
 * Home (alice layout, Material 3 tokens): a brand header with the OCR
 * progress strip and a 更多 menu sheet (收藏 / 历史记录 / 错词本 / 听写统计),
 * the 单词列表 section ([WordListSection] — 编辑/展示 two-state word list where
 * tapping a display row moves the 起始词 and a trailing chevron expands a
 * truncated gloss), a 拍照识词 TextButton and the bottom playback panel
 * ([HomePlaybackPanel] — 间隔 / 自动播放 / 随机顺序 / 开始听写). The draft
 * persists with a 500 ms debounce flushed on dispose; IME padding is applied
 * to the content area only, so the playback panel stays pinned to the screen
 * bottom (covered by the keyboard while typing) and its slot is never freed to
 * the editor — dismissing the IME causes no "fill then pop back" jump.
 *
 * The panel's start button is pressable at 0 words on purpose (its caption
 * reads 请先输入词表 and the tap answers 请先输入单词列表) and is held busy
 * while the list is not settled. Loads from 历史记录 / 收藏 close their sheet
 * first and then confirm on this screen's host, so the message is never left
 * behind the sheet's scrim; the screen-level host also carries the OCR,
 * 清空错词本 / 清空历史记录 confirmations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartDictation: (lines: List<String>, sourceLabel: String?) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenLibraryPreview: (category: String, label: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenOcrSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // One message channel per screen window, hosted at the bottom of the root
    // Box below: confirmations (已删除 / 已载入草稿 / OCR 结果…) used to be
    // fire-and-forget system messages; now they anchor in the app's own surface
    // (just above the playback panel) and land in the accessibility tree.
    val messages = rememberMessageController()
    val colors = MaterialTheme.colorScheme
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val draftLoaded by viewModel.draftLoaded.collectAsStateWithLifecycle()
    val wordCount by viewModel.wordCount.collectAsStateWithLifecycle()
    val startIndex by viewModel.startIndex.collectAsStateWithLifecycle()
    val displayMode by viewModel.displayMode.collectAsStateWithLifecycle()
    val shuffle by viewModel.shuffle.collectAsStateWithLifecycle()
    val intervalSec by viewModel.intervalSec.collectAsStateWithLifecycle()
    val autoNext by viewModel.autoNext.collectAsStateWithLifecycle()
    val starting by viewModel.starting.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val favoriteItems by viewModel.favoriteItems.collectAsStateWithLifecycle()
    val wrongWords by viewModel.wrongWords.collectAsStateWithLifecycle()
    val wrongGroups by viewModel.wrongGroups.collectAsStateWithLifecycle()
    val ocrBusy by viewModel.ocrBusy.collectAsStateWithLifecycle()
    val ocrPhase by viewModel.ocrPhase.collectAsStateWithLifecycle()
    val ocrError by viewModel.ocrError.collectAsStateWithLifecycle()
    val ocrOutcome by viewModel.ocrOutcome.collectAsStateWithLifecycle()
    val ocrRetryable by viewModel.ocrRetryable.collectAsStateWithLifecycle()
    val ocrConfigured by viewModel.ocrConfigured.collectAsStateWithLifecycle()
    val ocrModel by viewModel.ocrModel.collectAsStateWithLifecycle()
    val ocrCropBitmap by viewModel.cropBitmap.collectAsStateWithLifecycle()
    val ocrCropLoading by viewModel.cropLoading.collectAsStateWithLifecycle()

    // Every one of these is a surface the user opened on purpose, and two of
    // them are destructive questions mid-answer: rotating the phone — or
    // resizing the window on a foldable — used to close the sheet and dismiss
    // 清空历史记录？ / 清空错词本？ without an answer (AUDIT C6).
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showFavorites by rememberSaveable { mutableStateOf(false) }
    var clearHistoryConfirm by rememberSaveable { mutableStateOf(false) }
    var showWrongWords by rememberSaveable { mutableStateOf(false) }
    var clearWrongConfirm by rememberSaveable { mutableStateOf(false) }

    // ---- 拍照识词 (OCR) state ----------------------------------------------
    var showOcrSheet by rememberSaveable { mutableStateOf(false) }
    // Saved across configuration changes: the crop step must survive a
    // rotation mid-selection (the overlay comes back from the VM's bitmap).
    var showOcrCrop by rememberSaveable { mutableStateOf(false) }
    val ocrLang by viewModel.ocrLang.collectAsStateWithLifecycle()
    val ocrPending by viewModel.ocrPending.collectAsStateWithLifecycle()
    val ocrNeedsSettings by viewModel.ocrNeedsSettings.collectAsStateWithLifecycle()

    // Camera/album launchers shared with 拍照批改; every pick goes through the
    // 选定识别区域 crop step first. The VM's Mutex backstops the network call
    // itself (AGENTS.md re-entry) — the picker only suppresses a second
    // launch while one is open.
    val ocrPicker = rememberOcrImagePicker(
        onPicked = { uri ->
            showOcrCrop = true
            viewModel.startOcrCrop(uri)
        },
        onError = { message ->
            messages.show(message)
        },
    )

    // One-shot OCR success message (已识别 N 个…), consumed once shown.
    LaunchedEffect(ocrOutcome) {
        ocrOutcome?.let { message ->
            messages.show(message)
            viewModel.clearOcrOutcome()
        }
    }

    // Flush a pending debounced draft when the screen goes away — the
    // upstream bug: the timer was cleared without saving, dropping the last
    // keystrokes on exit.
    DisposableEffect(Unit) {
        onDispose { viewModel.flushDraft() }
    }

    // 词库 preview's 载入草稿: one-shot import bus — apply into the draft,
    // clear the bus, confirm. Runs on every re-entry, so a request staged
    // while this screen was off-stack is consumed exactly once on return.
    val app = context.applicationContext as HearWriteApplication
    // A 多选词表 selection is composing work for 抽词听写; landing Home ends
    // it, so a stale ticked selection can never greet a later 词库 visit.
    LaunchedEffect(Unit) { app.librarySelection.setActive(false) }
    LaunchedEffect(Unit) {
        app.pendingDraftImport.collect { lines ->
            if (lines != null) {
                viewModel.applyEntry(lines)
                app.consumeDraftImport()
                // No confirmation here: the import is confirmed at its source
                // (the preview's 载入草稿), which is where the user acted. This
                // consumer only applies the staged lines, possibly minutes
                // later — echoing it here would replay a stale message.
            }
        }
    }

    // Back in 编辑态 finishes the edit (switches to 展示态) instead of
    // sending the app to the background — matching how 完成 works.
    BackHandler(enabled = !displayMode) {
        viewModel.setDisplayMode(true)
    }

    // Bottom space the content must yield: the IME when the keyboard is up,
    // else the playback panel's own height. Content bottom padding animates
    // between the two, so dismissing the keyboard never frees the panel slot
    // to the editor ("fill the screen then pop back"): the editor bottom
    // glides from the keyboard top to the panel top and the panel itself,
    // drawn as an overlay, simply emerges from behind the keyboard.
    //
    // "Is the keyboard up" is decided by the IME's own inset being non-zero,
    // never by `WindowInsets.isImeVisible`: that flag reports the insets
    // *source* as visible, which stays true with a zero-height frame whenever
    // the IME is requested but not laid out (hardware keyboard attached, soft
    // keyboard disabled, or the IME window not yet drawn). Padding by that
    // zero swallowed the panel's reservation — the editor stretched under the
    // panel and the 示例/清空 footer slid below 开始听写. A positive inset is
    // exactly the space the keyboard occupies.
    var panelHeightPx by remember { mutableIntStateOf(0) }
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    val contentPad by animateDpAsState(
        targetValue = with(LocalDensity.current) {
            if (imeBottomPx > 0) {
                imeBottomPx.toDp()
            } else {
                panelHeightPx.toDp()
            }
        },
        animationSpec = tween(250),
        label = "contentBottomPad",
    )

    // The screen body reads the message channel from any depth — the 更多
    // sheet, the history/favorites/wrong-word sheets, the panel's 开始听写
    // guard and the confirm dialogs. The host inside the root Box renders
    // whatever it is handed; the sheets install their own host, since a
    // ModalBottomSheet is a separate window drawn above this one.
    CompositionLocalProvider(LocalMessages provides messages) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    // No .navigationBarsPadding() here: both values contentPad can
                    // take already span the navigation bar — the playback panel's
                    // measured height includes its own bottom padding and its
                    // .navigationBarsPadding() (onSizeChanged sits before them in
                    // that chain), and the IME's bottom edge extends past the
                    // navigation bar too. Padding for it again would count the
                    // bar twice and float the editor above the panel.
                    .padding(bottom = contentPad),
            ) {
                // ---- Header: brand · library/settings shortcuts ----
                // The OCR progress pill used to live in a weighted Box here,
                // between the wordmark (≈190dp) and three 48dp icons: on a
                // 360dp screen that left it ≈0dp wide, so the app's only
                // recognition feedback was truncated to a few glyphs. It is a
                // full-width strip under the header now ([OcrProgressStrip]).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HearWriteWordmark()
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onOpenLibrary) {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = "词库",
                            tint = colors.onBackground,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "设置",
                            tint = colors.onBackground,
                        )
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = "菜单",
                            tint = colors.onBackground,
                        )
                    }
                }

                // ---- 拍照识词 progress: full width, with its way out ------
                if (ocrBusy) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        OcrProgressStrip(
                            phase = ocrPhase,
                            onCancel = viewModel::cancelOcr,
                            modifier = Modifier.contentWidth(),
                        )
                    }
                }

                // ---- Main: OCR error card + word-list section ---------
                // A Box centred on the top, so the word-list column can cap
                // itself to a reading measure: Chinese body copy at 27sp line
                // height runs past a comfortable line length once the window is
                // unfolded, and the editor used to stretch edge to edge there
                // (AUDIT C6). Phone widths are below the cap, so nothing moves.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .contentWidth()
                            .padding(horizontal = 20.dp),
                    ) {
                        ocrError?.let { message ->
                            OcrErrorCard(
                                message = message,
                                retryable = ocrRetryable,
                                needsSettings = ocrNeedsSettings,
                                onClose = viewModel::clearOcrError,
                                onRetry = viewModel::retryOcr,
                                onOpenSettings = onOpenOcrSettings,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        WordListSection(
                            draft = draft,
                            displayMode = displayMode,
                            wordCount = wordCount,
                            startIndex = startIndex,
                            loading = !draftLoaded,
                            onDraftChange = viewModel::onDraftChange,
                            onToggleDisplayMode = { viewModel.setDisplayMode(!displayMode) },
                            onStartIndexChange = viewModel::setStartIndex,
                            onDeleteWord = viewModel::deleteWord,
                            onFillSample = viewModel::fillSample,
                            onClear = viewModel::clearDraft,
                            onScan = { showOcrSheet = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ---- Bottom playback panel (overlay, measured for content pad) ----
            // Drawn at the screen bottom BEHIND the keyboard while typing (the
            // content pad above already reserves the IME space) and revealed when
            // the keyboard hides. Its height is reported up so the content column
            // pads by it when no keyboard is shown.
            HomePlaybackPanel(
                intervalSec = intervalSec,
                autoNext = autoNext,
                shuffle = shuffle,
                wordCount = wordCount,
                startIndex = startIndex,
                startBusyLabel = when {
                    starting -> "整理词表…"
                    !draftLoaded -> "读取草稿…"
                    ocrBusy -> "识别中…"
                    else -> null
                },
                onIntervalChange = viewModel::onIntervalChange,
                onAutoNextChange = viewModel::onAutoNextChange,
                onShuffleChange = viewModel::onShuffleChange,
                onResetStart = { viewModel.setStartIndex(0) },
                onStart = {
                    if (wordCount == 0) {
                        // Kept pressable so the app can explain why (alice).
                        messages.show("请先输入单词列表")
                    } else {
                        scope.launch {
                            viewModel.prepareAndRecord()?.let { prepared ->
                                onStartDictation(prepared.lines, prepared.historyId)
                            }
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Capped like the content above it: on a tablet the panel is
                    // a control group, not a full-width slab, and its measure
                    // should match the text it sits under (AUDIT C6).
                    .contentWidth()
                    .onSizeChanged { panelHeightPx = it.height }
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
                    .navigationBarsPadding(),
            )

            // Screen-level confirmations. The host rides the same animated pad
            // the content yields to, so the message sits just above the panel.
            // No extra .navigationBarsPadding(): contentPad is already the
            // panel's full height (bottom padding + its own navigation-bar inset
            // — see above) or the IME's bottom edge, so both values clear the
            // navigation bar; padding for it again would push the message a bar
            // height off the panel, exactly the double count B4 removes from the
            // content column. (Messages raised inside a sheet are hosted by that
            // sheet instead: the sheet is its own window, above this one.)
            MessageHost(
                messages,
                Modifier
                    .align(Alignment.BottomCenter)
                    .contentWidth()
                    .padding(bottom = contentPad),
            )
        }

        // ---- 更多 menu sheet -----------------------------------------------------
        if (showMenu) {
            ModalBottomSheet(onDismissRequest = { showMenu = false }) {
                // Every sheet window hosts its own messages: this one raises none
                // today, but the whole sheet covers the screen, so a future row
                // reporting something must not route it to the host behind the
                // scrim. Wrapping keeps that invariant uniform across sheets.
                MessageHostScope {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                        // The four rows share the sheet's own container colour,
                        // so with no gap they read as one tall block instead of
                        // four targets. 8dp between them restores the grouping
                        // without touching MenuRow's styling.
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "更多",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        MenuRow(Icons.Outlined.StarBorder, "收藏") {
                            showMenu = false
                            showFavorites = true
                        }
                        MenuRow(Icons.Outlined.History, "历史记录") {
                            showMenu = false
                            showHistory = true
                        }
                        // 错词本 is a spelling-mistake book, so it gets its own
                        // glyph: Cancel already means 删除这一行 inside the word
                        // list, and the two marks were confusable one menu apart.
                        MenuRow(Icons.Outlined.Spellcheck, "错词本") {
                            showMenu = false
                            showWrongWords = true
                        }
                        MenuRow(Icons.Outlined.BarChart, "听写统计") {
                            showMenu = false
                            onOpenStats()
                        }
                    }
                }
            }
        }

        if (showOcrSheet) {
            OcrScanSheet(
                lang = ocrLang,
                onLangChange = viewModel::setOcrLang,
                configured = ocrConfigured,
                modelName = ocrModel,
                busy = ocrBusy || ocrPicker.busy,
                recognizing = ocrBusy,
                phase = ocrPhase,
                onCancel = viewModel::cancelOcr,
                onCamera = {
                    showOcrSheet = false
                    ocrPicker.launchCamera()
                },
                onGallery = {
                    showOcrSheet = false
                    ocrPicker.launchGallery()
                },
                onOpenSettings = {
                    showOcrSheet = false
                    // 修改/去设置 lands on the OCR provider form, not the hub.
                    onOpenOcrSettings()
                },
                onDismiss = { showOcrSheet = false },
            )
        }

        // 选定识别区域 step: shown from pick/capture until confirm or dismiss;
        // a decode failure clears it by itself (bitmap null, loading done) and
        // leaves the shared OCR error card to explain.
        if (showOcrCrop && (ocrCropLoading || ocrCropBitmap != null)) {
            OcrCropOverlay(
                bitmap = ocrCropBitmap,
                onConfirm = { rect ->
                    showOcrCrop = false
                    // The language the sheet was opened with — the run's own
                    // recognition language, not a re-inferred one.
                    viewModel.confirmOcrCrop(rect, ocrLang)
                },
                onDismiss = {
                    showOcrCrop = false
                    viewModel.cancelOcrCrop()
                },
            )
        }

        if (showHistory) {
            HistorySheet(
                entries = history,
                favoriteIds = favorites,
                // Dismiss before confirming: the sheet is its own window on top
                // of this one, so a 已载入历史记录 raised while it is still up
                // would sit behind the sheet's scrim — and show for its
                // dismissal animation only. Closing first lands the message on
                // the screen-level host, just above the playback panel, where
                // the user is now looking (same as 查看词表 does from 错词本).
                onApply = { text ->
                    viewModel.applyEntry(text)
                    showHistory = false
                    messages.show("已载入历史记录")
                },
                onToggleFavorite = viewModel::toggleFavorite,
                onDelete = viewModel::deleteHistory,
                onUndoDelete = viewModel::undoDeleteHistory,
                onClear = { clearHistoryConfirm = true },
                onDismiss = { showHistory = false },
            )
        }

        if (showFavorites) {
            FavoritesSheet(
                items = favoriteItems,
                // Same close-then-confirm ordering as 历史记录 above.
                onApply = { text ->
                    viewModel.applyEntry(text)
                    showFavorites = false
                    messages.show("已载入收藏")
                },
                onToggleFavorite = viewModel::toggleFavorite,
                onDismiss = { showFavorites = false },
            )
        }
        if (showWrongWords) {
            WrongWordsSheet(
                groups = wrongGroups,
                onDictate = {
                    showWrongWords = false
                    // Book marks keep their original lines (词性/释义 / 拼音/组词)
                    // where the source still resolves; the round itself is a
                    // bare-word run, so its marks carry no new provenance.
                    scope.launch {
                        viewModel.prepareWrongWordRun()?.let { lines ->
                            onStartDictation(lines, null)
                        }
                    }
                },
                onDelete = viewModel::removeWrongWord,
                onUndoDelete = viewModel::undoRemoveWrongWord,
                onClear = { clearWrongConfirm = true },
                onJumpToSource = { category, label ->
                    showWrongWords = false
                    onOpenLibraryPreview(category, label)
                },
                onDismiss = { showWrongWords = false },
            )
        }


        // A recognition that landed over a non-blank draft: the old list is
        // gone for good if it is replaced (only a run writes history), so the
        // user picks 替换 / 追加 / 取消. An empty draft never asks.
        ocrPending?.let { lines ->
            AlertDialog(
                onDismissRequest = { viewModel.discardOcrPending() },
                title = { Text("识别到 ${lines.size} 个词") },
                text = {
                    Text(
                        "当前草稿有 ${wordCount} 个词。识别结果可以替换或追加到草稿末尾；" +
                            "未开始听写的草稿不会被记入历史，替换后无法找回。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.replaceDraftWithOcr() }) { Text("替换") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { viewModel.discardOcrPending() }) { Text("取消") }
                        TextButton(onClick = { viewModel.appendOcrToDraft() }) { Text("追加") }
                    }
                },
            )
        }

        if (clearWrongConfirm) {
            AlertDialog(
                onDismissRequest = { clearWrongConfirm = false },
                title = { Text("清空错词本？") },
                text = { Text("将删除全部 ${wrongWords.size} 个错词。") },
                confirmButton = {
                    TextButton(onClick = {
                        clearWrongConfirm = false
                        viewModel.clearWrongWords()
                        messages.show("已清空错词本")
                    }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { clearWrongConfirm = false }) { Text("取消") }
                },
            )
        }

        if (clearHistoryConfirm) {
            AlertDialog(
                onDismissRequest = { clearHistoryConfirm = false },
                title = { Text("清空历史记录？") },
                text = { Text("将清空全部 ${history.size} 条历史记录，收藏的词表会保留。") },
                confirmButton = {
                    TextButton(onClick = {
                        clearHistoryConfirm = false
                        viewModel.clearHistory()
                        messages.show("已清空历史记录")
                    }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { clearHistoryConfirm = false }) { Text("取消") }
                },
            )
        }
    }
}

/** One styled row of the 更多 menu sheet: icon · label · chevron. */
@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp).size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.NavigateNext,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Terminal OCR failure surfaced inline above the word-list section. */
@Composable
private fun OcrErrorCard(
    message: String,
    retryable: Boolean,
    /** Missing/incomplete provider config: only 设置 can fix this run. */
    needsSettings: Boolean,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 4.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                // A configuration failure is not retryable — the card used to
                // answer "请先在设置中配置 OCR 服务" with a lone 关闭, naming a
                // destination it gave no way to reach (the scan sheet has 去设置;
                // the error card did not).
                if (needsSettings) {
                    TextButton(onClick = onOpenSettings) { Text("去设置") }
                }
                TextButton(onClick = onClose) { Text("关闭") }
                if (retryable) {
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
        }
    }
}
