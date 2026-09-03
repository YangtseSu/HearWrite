package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.lifecycle.viewmodel.compose.viewModel
import org.yangtse.hearwrite.data.OCR_DISCLAIMER
import org.yangtse.hearwrite.data.OCR_PROVIDER_PRESETS
import org.yangtse.hearwrite.data.TTS_PROVIDER_PRESETS
import org.yangtse.hearwrite.data.TtsApiKind
import org.yangtse.hearwrite.domain.MAX_SPEECH_RATE
import org.yangtse.hearwrite.domain.MIN_SPEECH_RATE
import org.yangtse.hearwrite.domain.TtsSource
import java.util.Locale

/**
 * Playback settings: 语速 (system TTS rate, applied live), 朗读释义, the TTS
 * source (有道词典/自定义音源/系统语音 — the custom one expands into the
 * OpenAI-compatible provider form: presets incl. 小米 MiMo, api wire shape,
 * baseUrl/apiKey/model/voices/format fields, 测试并试听 and 保存) and 提示音.
 * The OCR 识别 section configures the BYOK vision provider (拍照识词).
 * Interval/auto-next are adjusted on the dictation screen itself; Phase 10
 * consolidates the remaining settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val speechRate by viewModel.speechRate.collectAsStateWithLifecycle()
    val readTranslation by viewModel.readTranslation.collectAsStateWithLifecycle()
    val ttsSource by viewModel.ttsSource.collectAsStateWithLifecycle()
    val soundEnabled by viewModel.soundEnabled.collectAsStateWithLifecycle()
    val ocrBaseUrl by viewModel.ocrBaseUrl.collectAsStateWithLifecycle()
    val ocrApiKey by viewModel.ocrApiKey.collectAsStateWithLifecycle()
    val ocrModel by viewModel.ocrModel.collectAsStateWithLifecycle()
    val ocrTestState by viewModel.ocrTestState.collectAsStateWithLifecycle()
    val ttsPresetId by viewModel.ttsPresetId.collectAsStateWithLifecycle()
    val ttsApi by viewModel.ttsApi.collectAsStateWithLifecycle()
    val ttsBaseUrl by viewModel.ttsBaseUrl.collectAsStateWithLifecycle()
    val ttsApiKey by viewModel.ttsApiKey.collectAsStateWithLifecycle()
    val ttsModel by viewModel.ttsModel.collectAsStateWithLifecycle()
    val ttsVoiceEn by viewModel.ttsVoiceEn.collectAsStateWithLifecycle()
    val ttsVoiceZh by viewModel.ttsVoiceZh.collectAsStateWithLifecycle()
    val ttsResponseFormat by viewModel.ttsResponseFormat.collectAsStateWithLifecycle()
    val ttsConfigSaved by viewModel.ttsConfigSaved.collectAsStateWithLifecycle()
    val ttsTestState by viewModel.ttsTestState.collectAsStateWithLifecycle()

    var showApiKey by remember { mutableStateOf(false) }
    var showTtsKey by remember { mutableStateOf(false) }
    val ocrComplete =
        ocrBaseUrl.isNotBlank() && ocrApiKey.isNotBlank() && ocrModel.isNotBlank()
    val activePresetId = OCR_PROVIDER_PRESETS.firstOrNull {
        it.id != "custom" && it.baseUrl == ocrBaseUrl.trim() && it.model == ocrModel.trim()
    }?.id ?: "custom"
    val ttsFormComplete =
        ttsBaseUrl.isNotBlank() && ttsApiKey.isNotBlank() && ttsModel.isNotBlank()

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
                .padding(horizontal = 20.dp),
        ) {
            Text(
                "听写",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            HorizontalDivider()

            // ---- 语速 ------------------------------------------------------
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("语速", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        String.format(Locale.ROOT, "%.1f", speechRate),
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

            // ---- 朗读释义 ---------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("朗读释义", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "英文词朗读后跟读中文释义",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = readTranslation,
                    onCheckedChange = viewModel::onReadTranslationChange,
                    modifier = Modifier.semantics { contentDescription = "朗读释义" },
                )
            }

            Text(
                "语音",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            HorizontalDivider()

            // ---- 发音来源 ---------------------------------------------------
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text("发音来源", style = MaterialTheme.typography.bodyLarge)
                Text(
                    when (ttsSource) {
                        TtsSource.YOUDAO -> "有道词典为真人词典发音，需要网络；断网或失败时自动改用系统语音"
                        TtsSource.CUSTOM ->
                            if (ttsConfigSaved) "当前使用自定义发音服务（${ttsModel.trim()}）"
                            else "选择服务商并填写配置后，保存并启用自定义发音"
                        TtsSource.SYSTEM -> "全部使用系统语音发音，无需网络"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = ttsSource == TtsSource.YOUDAO,
                        onClick = { viewModel.onTtsSourceChange(TtsSource.YOUDAO) },
                        label = { Text("有道词典") },
                    )
                    FilterChip(
                        selected = ttsSource == TtsSource.CUSTOM,
                        onClick = { viewModel.onTtsSourceChange(TtsSource.CUSTOM) },
                        label = { Text("自定义音源") },
                    )
                    FilterChip(
                        selected = ttsSource == TtsSource.SYSTEM,
                        onClick = { viewModel.onTtsSourceChange(TtsSource.SYSTEM) },
                        label = { Text("系统语音") },
                    )
                }
            }

            if (ttsSource == TtsSource.CUSTOM) {
                // ---- 服务商预设 ---------------------------------------------
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text("服务商预设", style = MaterialTheme.typography.bodyLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        TTS_PROVIDER_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = ttsPresetId == preset.id,
                                onClick = { viewModel.onTtsPresetChange(preset) },
                                label = { Text(preset.label) },
                            )
                        }
                    }
                    val hint = TTS_PROVIDER_PRESETS.firstOrNull { it.id == ttsPresetId }?.hint
                    if (!hint.isNullOrEmpty()) {
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                // ---- 接口类型 (自定义预设 only) --------------------------------
                if (ttsPresetId == "custom") {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Text("接口类型", style = MaterialTheme.typography.bodyLarge)
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = ttsApi == TtsApiKind.SPEECH,
                                onClick = { viewModel.onTtsApiChange(TtsApiKind.SPEECH) },
                                label = { Text("/audio/speech") },
                            )
                            FilterChip(
                                selected = ttsApi == TtsApiKind.CHAT,
                                onClick = { viewModel.onTtsApiChange(TtsApiKind.CHAT) },
                                label = { Text("Chat Completions") },
                            )
                        }
                        Text(
                            "标准 OpenAI TTS 选 /audio/speech；小米 MiMo 等经对话接口合成选 Chat Completions。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                // ---- 接口地址 -------------------------------------------------
                OutlinedTextField(
                    value = ttsBaseUrl,
                    onValueChange = viewModel::onTtsBaseUrlChange,
                    label = { Text("接口地址（Base URL）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )

                // ---- API Key ----------------------------------------------------
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

                // ---- 模型 ---------------------------------------------------------
                OutlinedTextField(
                    value = ttsModel,
                    onValueChange = viewModel::onTtsModelChange,
                    label = { Text("模型") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )

                // ---- 音色 ---------------------------------------------------------
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

                // ---- 响应格式 (speech shape only) ----------------------------------
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

                // ---- 测试并试听 -----------------------------------------------------
                OutlinedButton(
                    onClick = viewModel::testTtsVoice,
                    enabled = ttsFormComplete && ttsTestState != TtsTestState.Testing,
                    modifier = Modifier.padding(top = 12.dp),
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

                // ---- 保存并启用 / 清除配置 ---------------------------------------------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (ttsConfigSaved) {
                        OutlinedButton(
                            onClick = viewModel::clearTtsConfig,
                        ) {
                            Text("清除配置")
                        }
                    }
                    Button(
                        onClick = {
                            viewModel.saveTtsConfig()
                            android.widget.Toast.makeText(
                                context, "已保存自定义发音配置", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        },
                        enabled = ttsFormComplete,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存并启用")
                    }
                }
            }

            // ---- 提示音 -----------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("提示音", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "倒计时最后一秒的滴答声与完成时的提示音",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = soundEnabled,
                    onCheckedChange = viewModel::onSoundEnabledChange,
                    modifier = Modifier.semantics { contentDescription = "提示音" },
                )
            }

            Text(
                "OCR 识别（拍照识词）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            HorizontalDivider()

            // ---- 服务商预设 ---------------------------------------------------
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text("服务商", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "拍照识词调用 OpenAI 兼容视觉接口，需填写自己的 API Key",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    OCR_PROVIDER_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = activePresetId == preset.id,
                            onClick = { viewModel.onOcrPresetChange(preset) },
                            label = { Text(preset.label) },
                        )
                    }
                }
            }

            // ---- Base URL -----------------------------------------------------
            OutlinedTextField(
                value = ocrBaseUrl,
                onValueChange = viewModel::onOcrBaseUrlChange,
                label = { Text("接口地址（Base URL）") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )

            // ---- API Key --------------------------------------------------------
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

            // ---- 模型 ------------------------------------------------------------
            OutlinedTextField(
                value = ocrModel,
                onValueChange = viewModel::onOcrModelChange,
                label = { Text("模型") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )

            // ---- 测试连接 ---------------------------------------------------------
            OutlinedButton(
                onClick = viewModel::testOcrConnection,
                enabled = ocrComplete && ocrTestState != OcrTestState.Testing,
                modifier = Modifier.padding(top = 12.dp),
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
                    android.widget.Toast.makeText(
                        context, "已保存 OCR 服务配置", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
                enabled = ocrComplete,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text("保存并启用")
            }
            Text(
                OCR_DISCLAIMER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
        }
    }
}
