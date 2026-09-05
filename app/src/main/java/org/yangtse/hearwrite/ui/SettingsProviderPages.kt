package org.yangtse.hearwrite.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import org.yangtse.hearwrite.data.EDGE_VOICE_EN
import org.yangtse.hearwrite.data.EDGE_VOICE_EN_GB
import org.yangtse.hearwrite.data.EdgeVoice
import org.yangtse.hearwrite.data.MIMO_VOICES
import org.yangtse.hearwrite.data.edgeDefaultEnVoice
import org.yangtse.hearwrite.data.edgeDefaultVoiceFor
import org.yangtse.hearwrite.data.edgeEnRegionOf
import org.yangtse.hearwrite.data.OCR_DISCLAIMER
import org.yangtse.hearwrite.data.OCR_PROVIDER_PRESETS
import org.yangtse.hearwrite.data.TTS_PROVIDER_PRESETS
import org.yangtse.hearwrite.data.TtsApiKind
import org.yangtse.hearwrite.domain.TtsSource

/**
 * 设置 → 发音来源. Top: the source radio list (有道词典 / 微软 Edge /
 * TTS API / 系统语音). When TTS API is active the page grows the provider
 * configuration form (kelivo-style API-provider setup): preset picker,
 * optional wire-shape choice, base URL / key / model / voices / format,
 * 测试并试听 and 保存并启用.
 */
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
    val youdaoPreviewState by viewModel.youdaoPreviewState.collectAsStateWithLifecycle()
    val systemPreviewState by viewModel.systemPreviewState.collectAsStateWithLifecycle()
    val edgeVoiceZh by viewModel.edgeVoiceZh.collectAsStateWithLifecycle()
    val edgeUseDefaultEn by viewModel.edgeUseDefaultEn.collectAsStateWithLifecycle()
    val edgeVoiceEn by viewModel.edgeVoiceEn.collectAsStateWithLifecycle()
    val edgePreviewState by viewModel.edgePreviewState.collectAsStateWithLifecycle()
    var showTtsKey by remember { mutableStateOf(false) }
    var showClearTtsConfirm by remember { mutableStateOf(false) }
    // A seeded-but-untouched key counts as present (the secret stays stored);
    // only a dirty empty field blocks 测试并试听/保存并启用.
    val ttsKeyPresent = ttsForm.apiKey.isNotBlank() ||
        (!ttsForm.apiKeyDirty && ttsForm.apiKeySavedHint.isNotEmpty())
    val ttsFormComplete =
        ttsForm.baseUrl.isNotBlank() && ttsKeyPresent && ttsForm.model.isNotBlank()

    val active = ttsActive

    SettingsSubPage(title = "发音来源", onBack = onBack) {
        SettingsCard {
            SettingsRadioRow(
                title = "有道词典",
                supporting = "真人词典发音，需要网络；断网或失败时自动改用系统语音",
                selected = ttsSource == TtsSource.YOUDAO,
                onClick = { viewModel.onTtsSourceChange(TtsSource.YOUDAO) },
                expanded = ttsSource == TtsSource.YOUDAO,
                expandedContent = {
                    SourcePreviewSection(
                        state = youdaoPreviewState,
                        testingText = "试听生成中…",
                        okText = "已播放试听",
                        onPreview = viewModel::previewYoudaoVoice,
                    )
                },
            )
            SettingsRadioRow(
                title = "系统语音",
                supporting = "全部使用系统内置语音，无需网络",
                selected = ttsSource == TtsSource.SYSTEM,
                onClick = { viewModel.onTtsSourceChange(TtsSource.SYSTEM) },
                expanded = ttsSource == TtsSource.SYSTEM,
                expandedContent = {
                    SourcePreviewSection(
                        state = systemPreviewState,
                        testingText = "试听播放中…",
                        okText = "已播放试听",
                        onPreview = viewModel::previewSystemVoice,
                    )
                },
            )
            SettingsRadioRow(
                title = "微软 Edge",
                supporting = "微软在线神经网络语音，免费无需 API Key；需要网络，失败时自动降级",
                selected = ttsSource == TtsSource.EDGE,
                onClick = { viewModel.onTtsSourceChange(TtsSource.EDGE) },
                expanded = ttsSource == TtsSource.EDGE,
                expandedContent = {
                    // 微软 Edge 音色: one default voice (zh-CN, speaks Chinese
                    // + English) plus an optional dedicated English voice
                    // behind 英文使用默认音色. A voice switch takes effect
                    // immediately (the clip cache keys bind voice+rate).
                    EdgeVoiceSection(
                        defaultVoice = edgeVoiceZh,
                        useDefaultEn = edgeUseDefaultEn,
                        englishVoice = edgeVoiceEn,
                        previewState = edgePreviewState,
                        onDefaultVoiceChange = viewModel::onEdgeVoiceZhChange,
                        onUseDefaultEnChange = viewModel::onEdgeUseDefaultEnChange,
                        onEnglishVoiceChange = viewModel::onEdgeVoiceEnChange,
                        onPreview = viewModel::previewEdgeVoice,
                    )
                },
            )
            SettingsRadioRow(
                title = "OpenAI 兼容语音",
                supporting = if (active != null) {
                    "当前使用：${active.model.trim()}"
                } else {
                    "尚未保存可用配置，暂用系统语音；选好服务商、填完配置后点「保存并启用」"
                },
                selected = ttsSource == TtsSource.CUSTOM,
                divider = false,
                onClick = { viewModel.onTtsSourceChange(TtsSource.CUSTOM) },
                expanded = ttsSource == TtsSource.CUSTOM,
                expandedContent = {
                    Column {
                        TTS_PROVIDER_PRESETS.forEachIndexed { index, preset ->
                            SettingsRadioRow(
                                title = preset.label,
                                supporting = listOfNotNull(
                                    if (preset.id == "mimo") "限时免费，需自备 API Key" else null,
                                    if (ttsStoredConfigs.containsKey(preset.id)) "已保存" else null,
                                ).joinToString(" · ").ifEmpty { null },
                                selected = ttsPresetId == preset.id,
                                divider = index < TTS_PROVIDER_PRESETS.lastIndex,
                                onClick = { viewModel.onTtsPresetChange(preset) },
                            )
                        }
                    }
                },
            )
        }
        if (ttsSource == TtsSource.CUSTOM) {
            SettingsSectionHeader("OpenAI 兼容语音接口设置")
            SettingsCard {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                ) {
                    // 接口类型 only matters for the hand-rolled preset; the named
                    // presets fix the wire shape.
                    if (ttsPresetId == "custom") {
                        TtsApiKindDropdown(
                            selected = ttsForm.api,
                            onSelect = viewModel::onTtsApiChange,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = ttsForm.baseUrl,
                        onValueChange = viewModel::onTtsBaseUrlChange,
                        readOnly = ttsPresetId == "mimo",
                        label = { Text("接口地址（Base URL）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = if (ttsForm.apiKeyDirty) ttsForm.apiKey else "",
                        onValueChange = viewModel::onTtsApiKeyChange,
                        label = { Text("API Key") },
                        supportingText = {
                            if (!ttsForm.apiKeyDirty && ttsForm.apiKeySavedHint.isNotEmpty()) {
                                Text("已保存 ••••${ttsForm.apiKeySavedHint}（输入即替换）· Key 仅保存在本机")
                            } else {
                                Text("Key 仅保存在本机，仅用于发音请求")
                            }
                        },
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
                        readOnly = ttsPresetId == "mimo",
                        label = { Text("模型") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                    if (ttsPresetId == "mimo") {
                        MimoVoiceSection(
                            defaultVoice = ttsForm.voiceZh,
                            useDefaultEn = ttsForm.useDefaultEn,
                            englishVoice = ttsForm.voiceEn,
                            previewing = ttsTestState == TtsTestState.Testing,
                            previewEnabled = ttsFormComplete,
                            onDefaultVoiceChange = viewModel::onTtsVoiceZhChange,
                            onUseDefaultEnChange = viewModel::onTtsUseDefaultEnChange,
                            onEnglishVoiceChange = viewModel::onTtsVoiceEnChange,
                            onPreview = viewModel::previewTtsVoice,
                        )
                        TtsTestStatusLine(
                            state = ttsTestState,
                            testingText = "生成试听中…",
                            okText = "连接成功，已播放试听",
                        )
                    } else {
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
                    }
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
                    if (ttsPresetId != "mimo") {
                        OutlinedButton(
                            onClick = viewModel::testTtsVoice,
                            enabled = ttsFormComplete && ttsTestState != TtsTestState.Testing,
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            Text("测试并试听")
                        }
                        TtsTestStatusLine(
                            state = ttsTestState,
                            testingText = "生成试听中…",
                            okText = "连接成功，已播放试听",
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (ttsStoredConfigs.containsKey(ttsPresetId)) {
                            OutlinedButton(onClick = { showClearTtsConfirm = true }) {
                                Text("清除配置")
                            }
                        }
                        Button(
                            onClick = {
                                viewModel.saveTtsConfig()
                                Toast.makeText(
                                    context, "已保存发音配置", Toast.LENGTH_SHORT,
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
    if (showClearTtsConfirm) {
        AlertDialog(
            onDismissRequest = { showClearTtsConfirm = false },
            title = { Text("清除该服务商的配置？") },
            text = { Text("将删除已保存的接口地址、Key 与模型，草稿恢复为预设默认值。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearTtsConfirm = false
                    viewModel.clearTtsConfig()
                }) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearTtsConfirm = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 设置 → 拍照识词: the BYOK OpenAI-compatible vision provider form (preset
 * picker + base URL / key / model), 测试连接 and 保存并启用.
 */
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
    var showClearOcrConfirm by remember { mutableStateOf(false) }
    // A seeded-but-untouched key counts as present (the secret stays stored);
    // only a dirty empty field blocks 测试连接/保存并启用.
    val ocrKeyPresent = ocrForm.apiKey.isNotBlank() ||
        (!ocrForm.apiKeyDirty && ocrForm.apiKeySavedHint.isNotEmpty())
    val ocrComplete =
        ocrForm.baseUrl.isNotBlank() && ocrKeyPresent && ocrForm.model.isNotBlank()

    SettingsSubPage(title = "拍照识词", onBack = onBack) {
        SettingsSectionHeader("服务商")
        SettingsCard {
            OCR_PROVIDER_PRESETS.forEachIndexed { index, preset ->
                SettingsRadioRow(
                    title = preset.label,
                    supporting = listOfNotNull(
                        preset.model.ifBlank { null },
                        if (ocrStoredConfigs.containsKey(preset.id)) "已保存" else null,
                    ).joinToString(" · ").ifEmpty { null },
                    selected = ocrPresetId == preset.id,
                    divider = index < OCR_PROVIDER_PRESETS.lastIndex,
                    onClick = { viewModel.onOcrPresetChange(preset) },
                )
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
                    value = if (ocrForm.apiKeyDirty) ocrForm.apiKey else "",
                    onValueChange = viewModel::onOcrApiKeyChange,
                    label = { Text("API Key") },
                    supportingText = {
                        if (!ocrForm.apiKeyDirty && ocrForm.apiKeySavedHint.isNotEmpty()) {
                            // An empty value keeps the label docked inside the
                            // field, which covers placeholder — so the saved-key
                            // hint lives here where it is always visible.
                            Text("已保存 ••••${ocrForm.apiKeySavedHint}（输入即替换）· Key 仅保存在本机")
                        } else {
                            Text("Key 仅保存在本机，仅用于拍照识词请求")
                        }
                    },
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
                        "连接成功，模型 ${ocrForm.model.trim()} 可用",
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
                        OutlinedButton(onClick = { showClearOcrConfirm = true }) {
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
    if (showClearOcrConfirm) {
        AlertDialog(
            onDismissRequest = { showClearOcrConfirm = false },
            title = { Text("清除该服务商的配置？") },
            text = { Text("将删除已保存的接口地址、Key 与模型，草稿恢复为预设默认值。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearOcrConfirm = false
                    viewModel.clearOcrConfig()
                }) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearOcrConfirm = false }) { Text("取消") }
            },
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
 * regenerate) and persist as explicit shortNames: a blank stored value only
 * survives from older versions and resolves to the built-in default for
 * display.
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

    Column {
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
            Column(modifier = Modifier.padding(top = 8.dp)) {
                SettingsRadioRow(
                    title = "美式英语",
                    selected = region != EDGE_EN_REGION_GB,
                    onClick = {
                        // Switching region selects that region's default
                        // voice explicitly: a blank stored value cannot
                        // remember its region (Sonia blanked would read
                        // back as US and bounce the picker off en-GB).
                        onEnglishVoiceChange(EDGE_VOICE_EN)
                    },
                )
                SettingsRadioRow(
                    title = "英式英语",
                    selected = region == EDGE_EN_REGION_GB,
                    divider = false,
                    onClick = { onEnglishVoiceChange(EDGE_VOICE_EN_GB) },
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
                selectedShortName = englishVoice.ifBlank { regionDefault },
                defaultShortName = regionDefault,
                previewing = previewing,
                onSelect = onEnglishVoiceChange,
                onPreview = onPreview,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // Preview status lives under the dropdowns; a transient per-voice
        // result is shown once here (the dropdowns re-render but state is a
        // single shared flow).
        TtsTestStatusLine(
            state = previewState,
            testingText = "试听生成中…",
            okText = "已播放试听",
        )
    }
}
/**
 * 小米 MiMo 音色 picker: 默认音色 dropdown (8 个官方音色, 均支持中英文),
 * 英文使用默认音色 switch (default on), 关掉后出现英文音色 dropdown
 * (同样 8 个). 每个 dropdown 右侧有试听按钮, 用当前表单配置合成
 * (默认音色试听混合句, 英文音色试听英文句) 而不改动草稿. 自定义预设
 * 不用此组件 (手填 voice id).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MimoVoiceSection(
    defaultVoice: String,
    useDefaultEn: Boolean,
    englishVoice: String,
    previewing: Boolean,
    previewEnabled: Boolean,
    onDefaultVoiceChange: (String) -> Unit,
    onUseDefaultEnChange: (Boolean) -> Unit,
    onEnglishVoiceChange: (String) -> Unit,
    onPreview: (voice: String, english: Boolean) -> Unit,
) {
    MimoVoiceDropdown(
        label = "默认音色",
        selectedVoice = defaultVoice.ifBlank { "冰糖" },
        previewing = previewing,
        previewEnabled = previewEnabled,
        onSelect = onDefaultVoiceChange,
        onPreview = { onPreview(it, false) },
        modifier = Modifier.padding(top = 4.dp),
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
        MimoVoiceDropdown(
            label = "英文音色",
            selectedVoice = englishVoice.ifBlank { "Chloe" },
            previewing = previewing,
            previewEnabled = previewEnabled,
            onSelect = onEnglishVoiceChange,
            onPreview = { onPreview(it, true) },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Text(
        "8 个官方音色均支持中英文；修改音色或语速后会重新生成发音。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}
/**
 * 接口类型 dropdown (TTS API form, 自定义 preset only): /audio/speech vs
 * Chat Completions. The menu descriptions carry the old radio supporting
 * lines so the wire-shape choice stays explained.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsApiKindDropdown(
    selected: TtsApiKind,
    onSelect: (TtsApiKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (selected) {
        TtsApiKind.SPEECH -> "/audio/speech"
        TtsApiKind.CHAT -> "Chat Completions"
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("接口类型") },
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
            DropdownMenuItem(
                text = {
                    Column {
                        Text("/audio/speech")
                        Text(
                            "标准 OpenAI TTS 兼容接口",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    onSelect(TtsApiKind.SPEECH)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Chat Completions")
                        Text(
                            "经对话接口合成",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    onSelect(TtsApiKind.CHAT)
                    expanded = false
                },
            )
        }
    }
}

/**
 * One MiMo voice dropdown: a read-only outlined field opening the 8-voice
 * menu, plus a 试听 button synthesizing a sample in the current selection.
 * Unknown stored ids (hand-typed before the dropdown era) fall back to the
 * first voice for display but keep the stored value until reselected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MimoVoiceDropdown(
    label: String,
    selectedVoice: String,
    previewing: Boolean,
    previewEnabled: Boolean,
    onSelect: (String) -> Unit,
    onPreview: (voice: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val effective = MIMO_VOICES.firstOrNull { it.first == selectedVoice }
        ?: MIMO_VOICES.first()

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
                value = effective.second,
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
                MIMO_VOICES.forEach { (id, display) ->
                    DropdownMenuItem(
                        text = { Text(display) },
                        onClick = {
                            onSelect(id)
                            expanded = false
                        },
                    )
                }
            }
        }
        IconButton(
            onClick = { onPreview(effective.first) },
            enabled = previewEnabled && !previewing,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "试听 ${effective.second}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One language's voice dropdown (TTS API form style): a read-only outlined
 * field opening the voice menu, plus a 试听 button on the right that plays a
 * sample in the current selection. Selections persist as explicit shortNames
 * (a blank stored value only survives from older versions and resolves to
 * the built-in default for display).
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
                            // Store the explicit shortName: a blank cannot
                            // remember its region (en-GB Sonia blanked
                            // reads back as US and bounces the picker).
                            onSelect(voice.shortName)
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

/**
 * One shared Testing/Ok/Failed status line for every TTS 试听 button
 * (MiMo/Edge dropdown previews, the custom-form 测试并试听, and the
 * 有道/系统 preview sections). The OCR form keeps its own block:
 * [OcrTestState] is a different type and its copy mentions the model name.
 */
@Composable
private fun TtsTestStatusLine(
    state: TtsTestState,
    testingText: String,
    okText: String,
    modifier: Modifier = Modifier.padding(top = 8.dp),
) {
    when (state) {
        TtsTestState.Idle -> Unit
        TtsTestState.Testing -> Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                testingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TtsTestState.Ok -> Text(
            okText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
        is TtsTestState.Failed -> Text(
            state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }
}

/**
 * 测试并试听 button + status line for the 有道词典 / 系统语音 sources (no
 * config form to test — just plays the word samples in that source's voice).
 */
@Composable
private fun SourcePreviewSection(
    state: TtsTestState,
    testingText: String,
    okText: String,
    onPreview: () -> Unit,
) {
    Column {
        OutlinedButton(
            onClick = onPreview,
            enabled = state != TtsTestState.Testing,
        ) {
            Text("测试并试听")
        }
        TtsTestStatusLine(
            state = state,
            testingText = testingText,
            okText = okText,
        )
    }
}
