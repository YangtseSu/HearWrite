package org.yangtse.hearwrite.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val ttsConfigSaved by viewModel.ttsConfigSaved.collectAsStateWithLifecycle()
    val ttsPresetId by viewModel.ttsPresetId.collectAsStateWithLifecycle()
    val ttsApi by viewModel.ttsApi.collectAsStateWithLifecycle()
    val ttsBaseUrl by viewModel.ttsBaseUrl.collectAsStateWithLifecycle()
    val ttsApiKey by viewModel.ttsApiKey.collectAsStateWithLifecycle()
    val ttsModel by viewModel.ttsModel.collectAsStateWithLifecycle()
    val ttsVoiceEn by viewModel.ttsVoiceEn.collectAsStateWithLifecycle()
    val ttsVoiceZh by viewModel.ttsVoiceZh.collectAsStateWithLifecycle()
    val ttsResponseFormat by viewModel.ttsResponseFormat.collectAsStateWithLifecycle()
    val ttsTestState by viewModel.ttsTestState.collectAsStateWithLifecycle()
    var showTtsKey by remember { mutableStateOf(false) }
    val ttsFormComplete =
        ttsBaseUrl.isNotBlank() && ttsApiKey.isNotBlank() && ttsModel.isNotBlank()

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
                TtsSource.SYSTEM -> Text(
                    "全部使用系统内置语音，无需网络",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
                TtsSource.CUSTOM -> Text(
                    if (ttsConfigSaved) {
                        "当前使用：${ttsModel.trim()}"
                    } else {
                        "自备 API Key；选好服务商、填完配置后点「保存并启用」"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
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
                        selected = ttsApi == TtsApiKind.SPEECH,
                        divider = true,
                        onClick = { viewModel.onTtsApiChange(TtsApiKind.SPEECH) },
                    )
                    SettingsRadioRow(
                        title = "Chat Completions",
                        supporting = "经对话接口合成（如小米 MiMo）",
                        selected = ttsApi == TtsApiKind.CHAT,
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
                        value = ttsBaseUrl,
                        onValueChange = viewModel::onTtsBaseUrlChange,
                        label = { Text("接口地址（Base URL）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = ttsApiKey,
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
                        value = ttsModel,
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
                            value = ttsVoiceEn,
                            onValueChange = viewModel::onTtsVoiceEnChange,
                            label = { Text("英文音色") },
                            placeholder = { Text("默认") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = ttsVoiceZh,
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
                    if (ttsApi == TtsApiKind.SPEECH) {
                        OutlinedTextField(
                            value = ttsResponseFormat,
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
                        if (ttsConfigSaved) {
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
    val ocrBaseUrl by viewModel.ocrBaseUrl.collectAsStateWithLifecycle()
    val ocrApiKey by viewModel.ocrApiKey.collectAsStateWithLifecycle()
    val ocrModel by viewModel.ocrModel.collectAsStateWithLifecycle()
    val ocrTestState by viewModel.ocrTestState.collectAsStateWithLifecycle()
    var showApiKey by remember { mutableStateOf(false) }
    val ocrComplete =
        ocrBaseUrl.isNotBlank() && ocrApiKey.isNotBlank() && ocrModel.isNotBlank()
    val activePresetId = OCR_PROVIDER_PRESETS.firstOrNull {
        it.id != "custom" && it.baseUrl == ocrBaseUrl.trim() && it.model == ocrModel.trim()
    }?.id ?: "custom"

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
                        selected = activePresetId == preset.id,
                        onClick = { viewModel.onOcrPresetChange(preset) },
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
                    value = ocrBaseUrl,
                    onValueChange = viewModel::onOcrBaseUrlChange,
                    label = { Text("接口地址（Base URL）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ocrApiKey,
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
                    value = ocrModel,
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
                Button(
                    onClick = {
                        viewModel.saveOcrConfig()
                        Toast.makeText(
                            context, "已保存 OCR 服务配置", Toast.LENGTH_SHORT,
                        ).show()
                    },
                    enabled = ocrComplete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Text("保存并启用")
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
