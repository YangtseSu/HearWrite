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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.yangtse.hearwrite.data.EDGE_VOICE_CATALOG
import org.yangtse.hearwrite.data.EdgeVoice
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
                    "微软在线神经网络语音，免费无需 API Key；需要网络，失败时自动降级。下方可分别选择中文与英文音色并试听。",
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

        // 微软 Edge 音色: the source is sentence-capable (组词 phrases ride
        // the chain), so both dictation languages get their own voice. The
        // picker shows the curated catalog — zh-CN voices for Chinese
        // (simplified textbooks), en-US for English; a voice switch takes
        // effect immediately (the clip cache keys bind voice + rate).
        if (ttsSource == TtsSource.EDGE) {
            EdgeVoiceSection(
                voiceZh = viewModel.edgeVoiceZh.collectAsStateWithLifecycle().value,
                voiceEn = viewModel.edgeVoiceEn.collectAsStateWithLifecycle().value,
                previewState = viewModel.edgePreviewState.collectAsStateWithLifecycle().value,
                onVoiceZhChange = { viewModel.onEdgeVoiceChange("zh", it) },
                onVoiceEnChange = { viewModel.onEdgeVoiceChange("en", it) },
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
 * selected): one radio group per dictation language — 中文 (普通话 zh-CN
 * voices) and English (en-US) — each row with a preview (试听) button that
 * synthesizes a sample in that voice without selecting it. Selections apply
 * immediately (cache keys bind voice+rate so clips regenerate); a 恢复默认
 * appears once any voice deviates from the app default.
 */
@Composable
private fun EdgeVoiceSection(
    voiceZh: String,
    voiceEn: String,
    previewState: TtsTestState,
    onVoiceZhChange: (String) -> Unit,
    onVoiceEnChange: (String) -> Unit,
    onPreview: (shortName: String, lang: String) -> Unit,
) {
    val zhVoices = EDGE_VOICE_CATALOG.filter { it.locale.startsWith("zh") }
    val enVoices = EDGE_VOICE_CATALOG.filter { it.locale.startsWith("en") }

    SettingsSectionHeader("Edge 音色")
    SettingsCard {
        EdgeVoiceLangGroup(
            title = "中文",
            supporting = "用于汉字听写与组词朗读",
            voices = zhVoices,
            selectedShortName = voiceZh,
            previewing = previewState is TtsTestState.Testing,
            onSelect = onVoiceZhChange,
            onPreview = { onPreview(it, "zh") },
        )
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        EdgeVoiceLangGroup(
            title = "English",
            supporting = "用于英文单词朗读",
            voices = enVoices,
            selectedShortName = voiceEn,
            previewing = previewState is TtsTestState.Testing,
            onSelect = onVoiceEnChange,
            onPreview = { onPreview(it, "en") },
        )
    }
    // Preview status lives under the group card; a transient per-voice
    // result is shown once here (the group rows re-render but state is a
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
 * One language's voice list: a 默认 (use the built-in neural default for the
 * language) radio + a row per curated voice, each with its own 试听 button.
 */
@Composable
private fun EdgeVoiceLangGroup(
    title: String,
    supporting: String,
    voices: List<EdgeVoice>,
    selectedShortName: String,
    previewing: Boolean,
    onSelect: (String) -> Unit,
    onPreview: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
        )
        Text(
            supporting,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
        )
        // 默认 voice = the app's built-in neural default (blank stored value).
        EdgeVoiceRow(
            label = "默认（${if (voices.firstOrNull()?.locale?.startsWith("zh") == true) "晓晓" else "Aria"}）",
            selected = selectedShortName.isBlank(),
            enabled = true,
            onClick = { onSelect("") },
            onPreview = null,
        )
        voices.forEach { voice ->
            EdgeVoiceRow(
                label = voice.friendlyName,
                selected = selectedShortName == voice.shortName,
                enabled = !previewing,
                onClick = { onSelect(voice.shortName) },
                onPreview = { onPreview(voice.shortName) },
            )
        }
    }
}

/**
 * One selectable voice row: the whole row selects (radio), the trailing
 * 试听 icon plays a sample in that voice without changing the selection.
 */
@Composable
private fun EdgeVoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onPreview: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (onPreview != null) {
            IconButton(
                onClick = onPreview,
                enabled = enabled,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "试听 $label",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
