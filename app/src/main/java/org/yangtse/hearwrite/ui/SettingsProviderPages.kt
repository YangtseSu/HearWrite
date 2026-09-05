package org.yangtse.hearwrite.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.yangtse.hearwrite.data.EDGE_EN_REGION_GB
import org.yangtse.hearwrite.data.EDGE_EN_REGION_US
import org.yangtse.hearwrite.data.EDGE_VOICE_CATALOG
import org.yangtse.hearwrite.data.EDGE_VOICE_EN_GB
import org.yangtse.hearwrite.data.EdgeVoice
import org.yangtse.hearwrite.data.edgeDefaultEnVoice
import org.yangtse.hearwrite.data.edgeDefaultVoiceFor
import org.yangtse.hearwrite.data.edgeEnRegionOf
import org.yangtse.hearwrite.data.OCR_DISCLAIMER
import org.yangtse.hearwrite.data.OCR_PROVIDER_PRESETS
import org.yangtse.hearwrite.data.TTS_PROVIDER_PRESETS
import org.yangtse.hearwrite.data.TtsApiKind
import org.yangtse.hearwrite.domain.TtsSource

/**
 * 设置 → 发音来源. Top: the source chip row (有道词典 / TTS API /
 * 系统语音). When TTS API is active the page grows the provider
 * configuration form (kelivo-style API-provider setup): preset picker,
 * optional wire-shape choice, base URL / key / model / voices / format,
 * 测试并试听 and 保存并启用.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceSourceSettingsPage(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val ttsSource by viewModel.ttsSource.collectAsStateWithLifecycle()
    val ttsForm by viewModel.ttsForm.collectAsStateWithLifecycle()
    val ttsPresetId by viewModel.ttsPresetId.collectAsStateWithLifecycle()
    val ttsStoredConfigs by viewModel.ttsStoredConfigs.collectAsStateWithLifecycle()
    val ttsActive by viewModel.ttsActive.collectAsStateWithLifecycle()
    val ttsTestState by viewModel.ttsTestState.collectAsStateWithLifecycle()
    var showTtsKey by remember { mutableStateOf(false) }
    val ttsFormComplete =
        ttsForm.baseUrl.isNotBlank() && ttsForm.apiKey.isNotBlank() && ttsForm.model.isNotBlank()

    SettingsSubPage(title = "发音来源", onBack = onBack) {
        SettingsCard {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = ttsSource == TtsSource.YOUDAO,
                    onClick = { viewModel.onTtsSourceChange(TtsSource.YOUDAO) },
                    label = { Text("有道词典", style = MaterialTheme.typography.bodyLarge) },
                )
                FilterChip(
                    selected = ttsSource == TtsSource.EDGE,
                    onClick = { viewModel.onTtsSourceChange(TtsSource.EDGE) },
                    label = { Text("微软 Edge", style = MaterialTheme.typography.bodyLarge) },
                )
                FilterChip(
                    selected = ttsSource == TtsSource.CUSTOM,
                    onClick = { viewModel.onTtsSourceChange(TtsSource.CUSTOM) },
                    label = { Text("TTS API", style = MaterialTheme.typography.bodyLarge) },
                )
                FilterChip(
                    selected = ttsSource == TtsSource.SYSTEM,
                    onClick = { viewModel.onTtsSourceChange(TtsSource.SYSTEM) },
                    label = { Text("系统语音", style = MaterialTheme.typography.bodyLarge) },
                )
            }
            when (ttsSource) {
                TtsSource.YOUDAO -> Text(
                    "真人词典发音，需要网络；断网或失败时自动改用系统语音",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
                TtsSource.EDGE -> Text(
                    "微软在线神经网络语音，免费无需 API Key；需要网络，失败时自动降级。下方选择默认音色（中英文通用）并试听；英文可单独选用美式或英式音色。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
                TtsSource.SYSTEM -> Text(
                    "全部使用系统内置语音，无需网络",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
                TtsSource.CUSTOM -> {
                    val active = ttsActive
                    Text(
                        if (active != null) {
                            "当前使用：${active.model.trim()}"
                        } else {
                            "自备 API Key；选好服务商、填完配置后点「保存并启用」"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
        }

        // 微软 Edge 音色: one default voice (zh-CN, speaks Chinese + English)
        // plus an optional dedicated English voice behind 英文使用默认音色.
        // A voice switch takes effect immediately (the clip cache keys bind
        // voice + rate).
        if (ttsSource == TtsSource.EDGE) {
            EdgeVoiceSection(
                defaultVoice = viewModel.edgeVoiceZh.collectAsStateWithLifecycle().value,
                useDefaultEn = viewModel.edgeUseDefaultEn.collectAsStateWithLifecycle().value,
                englishVoice = viewModel.edgeVoiceEn.collectAsStateWithLifecycle().value,
                previewState = viewModel.edgePreviewState.collectAsStateWithLifecycle().value,
                onDefaultVoiceChange = viewModel::onEdgeVoiceZhChange,
                onUseDefaultEnChange = viewModel::onEdgeUseDefaultEnChange,
                onEnglishVoiceChange = viewModel::onEdgeVoiceEnChange,
                onPreview = viewModel::previewEdgeVoice,
            )
        }

        if (ttsSource == TtsSource.CUSTOM) {
            SettingsSectionHeader("服务商预设")
            SettingsCard {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = if (ttsPresetId == "mimo") 0.dp else 12.dp,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TTS_PROVIDER_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = ttsPresetId == preset.id,
                            onClick = { viewModel.onTtsPresetChange(preset) },
                            leadingIcon = if (ttsStoredConfigs.containsKey(preset.id)) {
                                {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                            label = {
                                Text(preset.label, style = MaterialTheme.typography.bodyLarge)
                            },
                        )
                    }
                }
                if (ttsPresetId == "mimo") {
                    Text(
                        "限时免费",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }

            // 接口类型 only matters for the hand-rolled preset; the named
            // presets fix the wire shape.
            if (ttsPresetId == "custom") {
                SettingsSectionHeader("接口类型")
                SettingsCard {
                    SettingsRadioRow(
                        title = "/audio/speech",
                        supporting = "标准 OpenAI TTS 兼容接口",
                        selected = ttsForm.api == TtsApiKind.SPEECH,
                        divider = true,
                        onClick = { viewModel.onTtsApiChange(TtsApiKind.SPEECH) },
                    )
                    SettingsRadioRow(
                        title = "Chat Completions",
                        supporting = "经对话接口合成（如小米 MiMo）",
                        selected = ttsForm.api == TtsApiKind.CHAT,
                        divider = false,
                        onClick = { viewModel.onTtsApiChange(TtsApiKind.CHAT) },
                    )
                }
            }

            SettingsSectionHeader("接口设置")
            SettingsCard {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                ) {
                    OutlinedTextField(
                        value = ttsForm.baseUrl,
                        onValueChange = viewModel::onTtsBaseUrlChange,
                        label = { Text("接口地址（Base URL）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = ttsForm.apiKey,
                        onValueChange = viewModel::onTtsApiKeyChange,
                        label = { Text("API Key") },
                        supportingText = { Text("Key 仅保存在本机，仅用于发音请求") },
                        singleLine = true,
                        visualTransformation = if (showTtsKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showTtsKey = !showTtsKey }) {
                                Icon(
                                    if (showTtsKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showTtsKey) "隐藏 API Key" else "显示 API Key",
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = ttsForm.model,
                        onValueChange = viewModel::onTtsModelChange,
                        label = { Text("模型") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = ttsForm.voiceEn,
                            onValueChange = viewModel::onTtsVoiceEnChange,
                            label = { Text("英文音色") },
                            placeholder = { Text("默认") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = ttsForm.voiceZh,
                            onValueChange = viewModel::onTtsVoiceZhChange,
                            label = { Text("中文音色") },
                            placeholder = { Text("默认") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "留空使用服务商默认音色；修改音色或语速后会重新生成发音。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (ttsForm.api == TtsApiKind.SPEECH) {
                        OutlinedTextField(
                            value = ttsForm.responseFormat,
                            onValueChange = viewModel::onTtsResponseFormatChange,
                            label = { Text("响应格式") },
                            placeholder = { Text("mp3") },
                            supportingText = { Text("/audio/speech 返回的音频格式（mp3/wav…）") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = viewModel::testTtsVoice,
                        enabled = ttsFormComplete && ttsTestState != TtsTestState.Testing,
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text("测试并试听")
                    }
                    when (val state = ttsTestState) {
                        TtsTestState.Idle -> Unit
                        TtsTestState.Testing -> Row(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "生成试听中…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TtsTestState.Ok -> Text(
                            "连接成功，已播放试听",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        is TtsTestState.Failed -> Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (ttsStoredConfigs.containsKey(ttsPresetId)) {
                            OutlinedButton(onClick = viewModel::clearTtsConfig) {
                                Text("清除配置")
                            }
                        }
                        Button(
                            onClick = {
                                viewModel.saveTtsConfig()
                                Toast.makeText(
                                    context, "已保存自定义发音配置", Toast.LENGTH_SHORT,
                                ).show()
                            },
                            enabled = ttsFormComplete,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("保存并启用")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 设置 → 拍照识词: the BYOK OpenAI-compatible vision provider form (preset
 * picker + base URL / key / model), 测试连接 and 保存并启用.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OcrProviderSettingsPage(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val ocrForm by viewModel.ocrForm.collectAsStateWithLifecycle()
    val ocrPresetId by viewModel.ocrPresetId.collectAsStateWithLifecycle()
    val ocrStoredConfigs by viewModel.ocrStoredConfigs.collectAsStateWithLifecycle()
    val ocrTestState by viewModel.ocrTestState.collectAsStateWithLifecycle()
    var showApiKey by remember { mutableStateOf(false) }
    val ocrComplete =
        ocrForm.baseUrl.isNotBlank() && ocrForm.apiKey.isNotBlank() && ocrForm.model.isNotBlank()

    SettingsSubPage(title = "拍照识词", onBack = onBack) {
        SettingsSectionHeader("服务商")
        SettingsCard {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OCR_PROVIDER_PRESETS.forEach { preset ->
                    FilterChip(
                        selected = ocrPresetId == preset.id,
                        onClick = { viewModel.onOcrPresetChange(preset) },
                        leadingIcon = if (ocrStoredConfigs.containsKey(preset.id)) {
                            {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else {
                            null
                        },
                        label = { Text(preset.label, style = MaterialTheme.typography.bodyLarge) },
                    )
                }
            }
        }

        SettingsSectionHeader("接口设置")
        SettingsCard {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            ) {
                OutlinedTextField(
                    value = ocrForm.baseUrl,
                    onValueChange = viewModel::onOcrBaseUrlChange,
                    label = { Text("接口地址（Base URL）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ocrForm.apiKey,
                    onValueChange = viewModel::onOcrApiKeyChange,
                    label = { Text("API Key") },
                    supportingText = { Text("Key 仅保存在本机，仅用于拍照识词请求") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showApiKey) "隐藏 API Key" else "显示 API Key",
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = ocrForm.model,
                    onValueChange = viewModel::onOcrModelChange,
                    label = { Text("模型") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
                OutlinedButton(
                    onClick = viewModel::testOcrConnection,
                    enabled = ocrComplete && ocrTestState != OcrTestState.Testing,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text("测试连接")
                }
                when (val state = ocrTestState) {
                    OcrTestState.Idle -> Unit
                    OcrTestState.Testing -> Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "测试中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OcrTestState.Ok -> Text(
                        "连接成功",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    is OcrTestState.Failed -> Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (ocrStoredConfigs.containsKey(ocrPresetId)) {
                        OutlinedButton(onClick = viewModel::clearOcrConfig) {
                            Text("清除配置")
                        }
                    }
                    Button(
                        onClick = {
                            viewModel.saveOcrConfig()
                            Toast.makeText(
                                context, "已保存 OCR 服务配置", Toast.LENGTH_SHORT,
                            ).show()
                        },
                        enabled = ocrComplete,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存并启用")
                    }
                }
            }
        }
        Text(
            OCR_DISCLAIMER,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        )
    }
}
/**
 * 微软 Edge 音色 picker (shown on the 发音来源 page when the Edge source is
 * selected): the 默认音色 dropdown (zh-CN voices — bilingual, they speak
 * both Chinese and English), an 英文使用默认音色 switch (default on), and —
 * only when the switch is off — the dedicated English voice in two levels:
 * 英文地区 (美式英语/英式英语) then the voice dropdown for that region.
 * Each dropdown carries a 试听 button that synthesizes a sample in the
 * current selection without changing it (the default voice previews a
 * mixed Chinese+English sample; English voices an English sample).
 * Selections apply immediately (cache keys bind voice+rate so clips
 * regenerate). There is no separate 默认 entry: the catalog already contains
 * the built-in defaults (晓晓 / Aria / Sonia) and picking one persists as
 * blank (= built-in default).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EdgeVoiceSection(
    defaultVoice: String,
    useDefaultEn: Boolean,
    englishVoice: String,
    previewState: TtsTestState,
    onDefaultVoiceChange: (String) -> Unit,
    onUseDefaultEnChange: (Boolean) -> Unit,
    onEnglishVoiceChange: (String) -> Unit,
    onPreview: (shortName: String, lang: String) -> Unit,
) {
    val zhVoices = EDGE_VOICE_CATALOG.filter { it.locale == "zh-CN" }
    val region = edgeEnRegionOf(englishVoice.ifBlank { edgeDefaultEnVoice(EDGE_EN_REGION_US) })
    val previewing = previewState is TtsTestState.Testing

    SettingsSectionHeader("Edge 音色")
    SettingsCard {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        ) {
            EdgeVoiceDropdown(
                label = "默认音色",
                lang = "zh",
                voices = zhVoices,
                selectedShortName = defaultVoice,
                previewing = previewing,
                onSelect = onDefaultVoiceChange,
                onPreview = onPreview,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "英文使用默认音色",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = useDefaultEn,
                    onCheckedChange = onUseDefaultEnChange,
                    modifier = Modifier.semantics { contentDescription = "英文使用默认音色" },
                )
            }
            if (!useDefaultEn) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = region != EDGE_EN_REGION_GB,
                        onClick = {
                            // Switching region resets the voice to that
                            // region's default (persisted as blank).
                            onEnglishVoiceChange("")
                        },
                        label = { Text("美式英语", style = MaterialTheme.typography.bodyLarge) },
                    )
                    FilterChip(
                        selected = region == EDGE_EN_REGION_GB,
                        onClick = { onEnglishVoiceChange(EDGE_VOICE_EN_GB) },
                        label = { Text("英式英语", style = MaterialTheme.typography.bodyLarge) },
                    )
                }
                val regionVoices = EDGE_VOICE_CATALOG.filter {
                    it.locale == if (region == EDGE_EN_REGION_GB) "en-GB" else "en-US"
                }
                val regionDefault = edgeDefaultEnVoice(region)
                EdgeVoiceDropdown(
                    label = "英文音色",
                    lang = "en",
                    voices = regionVoices,
                    selectedShortName = englishVoice,
                    defaultShortName = regionDefault,
                    previewing = previewing,
                    onSelect = { onEnglishVoiceChange(if (it == regionDefault) "" else it) },
                    onPreview = onPreview,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    // Preview status lives under the group card; a transient per-voice
    // result is shown once here (the dropdowns re-render but state is a
    // single shared flow).
    when (val state = previewState) {
        TtsTestState.Idle -> Unit
        TtsTestState.Testing -> Text(
            "试听生成中…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        TtsTestState.Ok -> Text(
            "已播放试听",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        is TtsTestState.Failed -> Text(
            state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
    }
}

/**
 * One language's voice dropdown (TTS API form style): a read-only outlined
 * field opening the voice menu, plus a 试听 button on the right that plays a
 * sample in the current selection. A blank [selectedShortName] resolves to
 * the built-in default for display; picking the default persists as blank.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EdgeVoiceDropdown(
    label: String,
    lang: String,
    voices: List<EdgeVoice>,
    selectedShortName: String,
    previewing: Boolean,
    onSelect: (String) -> Unit,
    onPreview: (shortName: String, lang: String) -> Unit,
    modifier: Modifier = Modifier,
    defaultShortName: String = edgeDefaultVoiceFor(lang),
) {
    var expanded by remember { mutableStateOf(false) }
    val effective = voices.firstOrNull { it.shortName == selectedShortName }
        ?: voices.firstOrNull { it.shortName == defaultShortName }
        ?: voices.firstOrNull()

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = effective?.friendlyName ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice.friendlyName) },
                        onClick = {
                            onSelect(if (voice.shortName == defaultShortName) "" else voice.shortName)
                            expanded = false
                        },
                    )
                }
            }
        }
        IconButton(
            onClick = {
                val voice = effective
                if (voice != null) onPreview(voice.shortName, lang)
            },
            enabled = !previewing && effective != null,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "试听 ${effective?.friendlyName ?: label}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
