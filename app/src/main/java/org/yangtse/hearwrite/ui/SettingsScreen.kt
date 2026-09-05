package org.yangtse.hearwrite.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.yangtse.hearwrite.data.OCR_PROVIDER_PRESETS
import org.yangtse.hearwrite.domain.MAX_SPEECH_RATE
import org.yangtse.hearwrite.domain.MIN_SPEECH_RATE
import org.yangtse.hearwrite.domain.ThemeMode
import org.yangtse.hearwrite.domain.TtsSource

/** One sub-page reachable from the settings hub (each draws its own top bar). */
private enum class SettingsSubPage { VOICE_SOURCE, OCR_PROVIDER, ABOUT }

/**
 * 设置 — an Android-settings-style hub. Grouped cards list every setting;
 * tappable rows open sub-pages (发音来源 / 拍照识词 /
 * 关于); 外观 is an inline theme-preview row and 语速 an inline slider. System back pops the sub-page first, then leaves 设置 entirely.
 * All state lives in the single [SettingsViewModel] shared by hub and
 * sub-pages, so edits made deep in a page show up on the hub immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    var subPage by rememberSaveable { mutableStateOf<SettingsSubPage?>(null) }

    fun goHub() {
        subPage = null
        // A provider page may have played a test clip → refresh the cache row.
        viewModel.refreshTtsCacheInfo()
    }

    BackHandler(enabled = subPage != null) { goHub() }

    when (subPage) {
        null -> SettingsHub(onClose = onClose, viewModel = viewModel, onOpen = { subPage = it })
        SettingsSubPage.VOICE_SOURCE -> VoiceSourceSettingsPage(
            viewModel = viewModel,
            onBack = { goHub() },
        )
        SettingsSubPage.OCR_PROVIDER -> OcrProviderSettingsPage(
            viewModel = viewModel,
            onBack = { goHub() },
        )
        SettingsSubPage.ABOUT -> AboutSettingsPage(onBack = { goHub() })
    }
}

/** The top-level hub: grouped list cards + the 清空发音缓存 confirm dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHub(
    onClose: () -> Unit,
    onOpen: (SettingsSubPage) -> Unit,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val speechRate by viewModel.speechRate.collectAsStateWithLifecycle()
    val readTranslation by viewModel.readTranslation.collectAsStateWithLifecycle()
    val ttsSource by viewModel.ttsSource.collectAsStateWithLifecycle()
    val ttsActive by viewModel.ttsActive.collectAsStateWithLifecycle()
    val soundEnabled by viewModel.soundEnabled.collectAsStateWithLifecycle()
    val cacheInfo by viewModel.ttsCacheInfo.collectAsStateWithLifecycle()
    val ocrActive by viewModel.ocrActive.collectAsStateWithLifecycle()
    val ocrActiveId by viewModel.ocrActivePresetId.collectAsStateWithLifecycle()
    val ocrPresetLabel = OCR_PROVIDER_PRESETS
        .firstOrNull { it.id == ocrActiveId }?.label ?: "自定义"
    val cacheCleared by viewModel.ttsCacheCleared.collectAsStateWithLifecycle()
    // Edge voices live only on the 发音来源 page (VoiceSourceSettingsPage)
    // when 微软 Edge is selected — never as rows on this hub.

    var showClearCacheDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(cacheCleared) {
        if (cacheCleared > 0) {
            Toast.makeText(context, "已清空发音缓存", Toast.LENGTH_SHORT).show()
        }
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("清空发音缓存？") },
            text = {
                val info = cacheInfo
                when {
                    info == null -> Text("将删除全部已下载的发音文件；之后如需在线发音会自动重新下载。")
                    info.fileCount == 0 -> Text("当前没有缓存的发音文件。")
                    else -> Text(
                        "将删除 ${info.fileCount} 个已下载的发音文件（约 " +
                            "${formatBytes(info.bytes)}）。删除后如需在线发音会自动重新下载。",
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheDialog = false
                        viewModel.clearTtsCache()
                    },
                ) {
                    Text(
                        "清空",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SettingsSectionHeader("外观")
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemePreviewCard(
                        label = "浅色",
                        selected = theme == ThemeMode.LIGHT,
                        onClick = { viewModel.onThemeChange(ThemeMode.LIGHT) },
                        modifier = Modifier.weight(1f),
                    )
                    ThemePreviewCard(
                        label = "深色",
                        selected = theme == ThemeMode.DARK,
                        onClick = { viewModel.onThemeChange(ThemeMode.DARK) },
                        modifier = Modifier.weight(1f),
                    )
                    ThemePreviewCard(
                        label = "跟随系统",
                        selected = theme == ThemeMode.SYSTEM,
                        onClick = { viewModel.onThemeChange(ThemeMode.SYSTEM) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            SettingsSectionHeader("听写")
            SettingsCard {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "语速",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatRate(speechRate),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = speechRate,
                        onValueChange = viewModel::onSpeechRateChange,
                        valueRange = MIN_SPEECH_RATE..MAX_SPEECH_RATE,
                        steps = 9, // 0.1 steps → 11 stops incl. endpoints
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "听写语速" },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                SettingsRow(
                    title = "英文词朗读后跟读中文释义",
                    leading = { Icon(Icons.Outlined.Translate, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailing = {
                        Switch(
                            checked = readTranslation,
                            onCheckedChange = viewModel::onReadTranslationChange,
                            modifier = Modifier.semantics { contentDescription = "朗读释义" },
                        )
                    },
                    divider = false,
                    onClick = { viewModel.onReadTranslationChange(!readTranslation) },
                )
            }

            SettingsSectionHeader("语音")
            SettingsCard {
                SettingsRow(
                    title = "发音来源",
                    supporting = when (ttsSource) {
                        TtsSource.YOUDAO -> "有道真人词典发音，需要网络；失败时自动改用系统语音"
                        TtsSource.EDGE -> "微软在线神经网络语音，免费无需 API Key"
                        TtsSource.SYSTEM -> "系统内置语音，完全离线"
                        TtsSource.CUSTOM -> ttsActive?.let {
                            "当前使用：${it.model.trim()}"
                        } ?: "自备 API Key；选好服务商、填完配置后点「保存并启用」"
                    },
                    leading = { Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailing = {
                        SettingsValueTrailing(
                            when (ttsSource) {
                                TtsSource.YOUDAO -> "有道词典"
                                TtsSource.EDGE -> "微软 Edge"
                                TtsSource.SYSTEM -> "系统语音"
                                TtsSource.CUSTOM -> "TTS API"
                            },
                        )
                    },
                    onClick = { onOpen(SettingsSubPage.VOICE_SOURCE) },
                )
                SettingsRow(
                    title = "提示音",
                    leading = { Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailing = {
                        Switch(
                            checked = soundEnabled,
                            onCheckedChange = viewModel::onSoundEnabledChange,
                            modifier = Modifier.semantics { contentDescription = "提示音" },
                        )
                    },
                    onClick = { viewModel.onSoundEnabledChange(!soundEnabled) },
                )
                SettingsRow(
                    title = "清空发音缓存",
                    leading = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailing = {
                        val info = cacheInfo
                        Text(
                            when {
                                info == null -> "计算中…"
                                info.bytes > 0L -> formatBytes(info.bytes)
                                else -> "无缓存"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    divider = false,
                    onClick = { showClearCacheDialog = true },
                )
            }

            SettingsSectionHeader("拍照识词")
            SettingsCard {
                SettingsRow(
                    title = "识别服务",
                    supporting = ocrActive?.let {
                        "用 AI 视觉识别课本照片中的词表 · ${it.model.trim()}"
                    } ?: "用 AI 视觉识别课本照片中的词表（需自备 API Key）",
                    leading = { Icon(Icons.Outlined.DocumentScanner, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailing = { SettingsValueTrailing(ocrPresetLabel) },
                    divider = false,
                    onClick = { onOpen(SettingsSubPage.OCR_PROVIDER) },
                )
            }

            SettingsSectionHeader("关于")
            SettingsCard {
                SettingsRow(
                    title = "关于听写",
                    leading = { Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailing = { SettingsChevronTrailing() },
                    divider = false,
                    onClick = { onOpen(SettingsSubPage.ABOUT) },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
