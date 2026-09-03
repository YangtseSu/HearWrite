package org.yangtse.hearwrite.ui

import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.yangtse.hearwrite.data.OcrLang
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Home (alice layout, Material 3 tokens): a brand header with the OCR
 * progress pill and a 更多 menu sheet (收藏 / 历史记录 / 词库 / 设置), the
 * 单词列表 section ([WordListSection] — 编辑/展示 two-state word list where
 * tapping a display row selects the 起始词), a floating 拍照识词 FAB and the
 * bottom playback panel ([HomePlaybackPanel] — 间隔 / 自动播放 / 随机顺序 /
 * 开始听写). The draft persists with a 500 ms debounce flushed on dispose;
 * the FAB and bottom panel hide while the keyboard is up.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onStartDictation: (List<String>) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val draft by viewModel.draft.collectAsStateWithLifecycle()
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
    val ocrBusy by viewModel.ocrBusy.collectAsStateWithLifecycle()
    val ocrPhase by viewModel.ocrPhase.collectAsStateWithLifecycle()
    val ocrError by viewModel.ocrError.collectAsStateWithLifecycle()
    val ocrOutcome by viewModel.ocrOutcome.collectAsStateWithLifecycle()
    val ocrRetryable by viewModel.ocrRetryable.collectAsStateWithLifecycle()
    val ocrConfigured by viewModel.ocrConfigured.collectAsStateWithLifecycle()
    val ocrModel by viewModel.ocrModel.collectAsStateWithLifecycle()

    var showMenu by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
    var clearHistoryConfirm by remember { mutableStateOf(false) }

    // ---- 拍照识词 (OCR) state ----------------------------------------------
    var showOcrSheet by remember { mutableStateOf(false) }
    var ocrLang by remember { mutableStateOf(OcrLang.ENGLISH) }
    // Suppresses a second picker/camera launch while one is open (the VM's
    // Mutex backstop guards the network call itself — AGENTS.md re-entry).
    var pickerOpen by remember { mutableStateOf(false) }

    // One fixed cache file, overwritten per shot; FileProvider hands the
    // camera app a writable content Uri (no CAMERA permission needed).
    val cameraFile = remember { File(context.cacheDir, "ocr/capture.jpg") }
    val cameraUri = remember {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", cameraFile)
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        pickerOpen = false
        if (uri != null) viewModel.recognizeImage(uri, ocrLang)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        pickerOpen = false
        if (ok) viewModel.recognizeImage(cameraUri, ocrLang)
    }

    // One-shot OCR success toast (已识别 N 个…), consumed once shown.
    LaunchedEffect(ocrOutcome) {
        ocrOutcome?.let { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearOcrOutcome()
        }
    }

    // Flush a pending debounced draft when the screen goes away — the
    // upstream bug: the timer was cleared without saving, dropping the last
    // keystrokes on exit.
    DisposableEffect(Unit) {
        onDispose { viewModel.flushDraft() }
    }

    // alice parity: the FAB and the bottom panel step aside while typing.
    val imeVisible = WindowInsets.isImeVisible

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        // ---- Header: brand · OCR progress pill · menu ----------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.RecordVoiceOver,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("HearWrite", style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
            Spacer(Modifier.width(6.dp))
            Text("听写", style = MaterialTheme.typography.titleLarge, color = colors.primary)
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (ocrBusy) {
                    Surface(
                        shape = CircleShape,
                        color = colors.primaryContainer,
                        contentColor = colors.onPrimaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (ocrPhase.isEmpty()) "识别中…" else ocrPhase,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Filled.Menu, contentDescription = "菜单")
            }
        }

        // ---- Main: OCR error card + word-list section, FAB overlay ---------
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 20.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ocrError?.let { message ->
                    OcrErrorCard(
                        message = message,
                        retryable = ocrRetryable,
                        onClose = viewModel::clearOcrError,
                        onRetry = viewModel::retryOcr,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                WordListSection(
                    draft = draft,
                    displayMode = displayMode,
                    wordCount = wordCount,
                    startIndex = startIndex,
                    onDraftChange = viewModel::onDraftChange,
                    onToggleDisplayMode = { viewModel.setDisplayMode(!displayMode) },
                    onStartIndexChange = viewModel::setStartIndex,
                    onDeleteWord = viewModel::deleteWord,
                    onFillSample = viewModel::fillSample,
                    onClear = viewModel::clearDraft,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!imeVisible) {
                FloatingActionButton(
                    onClick = { showOcrSheet = true },
                    shape = CircleShape,
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 4.dp,
                            // Lift above the edit-mode footer row.
                            bottom = if (displayMode && wordCount > 0) 12.dp else 60.dp,
                        ),
                ) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = "拍照识词")
                }
            }
        }

        // ---- Bottom playback panel (hidden with the keyboard) --------------
        if (!imeVisible) {
            HomePlaybackPanel(
                intervalSec = intervalSec,
                autoNext = autoNext,
                shuffle = shuffle,
                wordCount = wordCount,
                starting = starting,
                onIntervalChange = viewModel::onIntervalChange,
                onAutoNextChange = viewModel::onAutoNextChange,
                onShuffleChange = viewModel::onShuffleChange,
                onStart = {
                    if (wordCount == 0) {
                        // Kept pressable so the app can explain why (alice).
                        android.widget.Toast.makeText(
                            context, "请先输入单词列表", android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        scope.launch {
                            viewModel.prepareAndRecord()?.let(onStartDictation)
                        }
                    }
                },
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }

    // ---- 更多 menu sheet -----------------------------------------------------
    if (showMenu) {
        ModalBottomSheet(onDismissRequest = { showMenu = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            ) {
                Text(
                    "更多",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                MenuRow(Icons.Outlined.StarBorder, "收藏") {
                    showMenu = false
                    showFavorites = true
                }
                MenuRow(Icons.Outlined.History, "历史记录") {
                    showMenu = false
                    showHistory = true
                }
                MenuRow(Icons.AutoMirrored.Filled.MenuBook, "词库") {
                    showMenu = false
                    onOpenLibrary()
                }
                MenuRow(Icons.Outlined.Settings, "设置") {
                    showMenu = false
                    onOpenSettings()
                }
            }
        }
    }

    if (showOcrSheet) {
        OcrScanSheet(
            lang = ocrLang,
            onLangChange = { ocrLang = it },
            configured = ocrConfigured,
            modelName = ocrModel,
            busy = ocrBusy || pickerOpen,
            onCamera = {
                showOcrSheet = false
                pickerOpen = true
                cameraFile.parentFile?.mkdirs()
                runCatching { cameraLauncher.launch(cameraUri) }
                    .onFailure {
                        pickerOpen = false
                        android.widget.Toast.makeText(
                            context, "无法启动相机", android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
            },
            onGallery = {
                showOcrSheet = false
                pickerOpen = true
                runCatching {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }.onFailure {
                    pickerOpen = false
                    android.widget.Toast.makeText(
                        context, "无法打开相册", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onOpenSettings = {
                showOcrSheet = false
                onOpenSettings()
            },
            onDismiss = { showOcrSheet = false },
        )
    }

    if (showHistory) {
        HistorySheet(
            entries = history,
            favoriteIds = favorites,
            onApply = viewModel::applyEntry,
            onToggleFavorite = viewModel::toggleFavorite,
            onDelete = { id ->
                viewModel.deleteHistory(id)
                android.widget.Toast.makeText(context, "已删除", android.widget.Toast.LENGTH_SHORT).show()
            },
            onClear = { clearHistoryConfirm = true },
            onDismiss = { showHistory = false },
        )
    }

    if (showFavorites) {
        FavoritesSheet(
            items = favoriteItems,
            onApply = viewModel::applyEntry,
            onToggleFavorite = viewModel::toggleFavorite,
            onDismiss = { showFavorites = false },
        )
    }

    if (clearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { clearHistoryConfirm = false },
            title = { Text("清空历史记录？") },
            text = { Text("将删除全部 ${history.size} 条历史记录，收藏的条目不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    clearHistoryConfirm = false
                    viewModel.clearHistory()
                    android.widget.Toast.makeText(context, "已清空历史记录", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { clearHistoryConfirm = false }) { Text("取消") }
            },
        )
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
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
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
    onClose: () -> Unit,
    onRetry: () -> Unit,
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
                TextButton(onClick = onClose) { Text("关闭") }
                if (retryable) {
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
        }
    }
}
