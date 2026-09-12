package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.yangtse.hearwrite.data.OCR_DISCLAIMER
import org.yangtse.hearwrite.data.OCR_PROVIDER_PRESETS
import org.yangtse.hearwrite.data.TTS_PROVIDER_PRESETS
import org.yangtse.hearwrite.data.TtsApiKind
import org.yangtse.hearwrite.domain.TtsSource

/**
 * 设置 → 发音来源. Top: the source radio list (有道词典 / 系统语音 /
 * 微软 Edge / OpenAI 兼容语音), each expanding inline with its own config —
 * 有道/系统试听, Edge voices, TTS presets. When OpenAI 兼容语音 is active the
 * page grows the provider configuration form below: preset picker, optional
 * wire-shape choice, base URL / key / model / voices / format, 测试并试听
 * and 保存并启用.
 *
 * The provider form itself is one widget set shared with 拍照识词's OCR form
 * (SettingsProviderForms.kt): the two pages used to be line-by-line copies,
 * which is how only one of them ended up validating its base URL and its
 * save result.
 */
@Composable
fun VoiceSourceSettingsPage(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val ttsSource by viewModel.ttsSource.collectAsStateWithLifecycle()
    val ttsForm by viewModel.ttsForm.collectAsStateWithLifecycle()
    val ttsPresetId by viewModel.ttsPresetId.collectAsStateWithLifecycle()
    val ttsStoredConfigs by viewModel.ttsStoredConfigs.collectAsStateWithLifecycle()
    val ttsActive by viewModel.ttsActive.collectAsStateWithLifecycle()
    val ttsActivePresetId by viewModel.ttsActivePresetId.collectAsStateWithLifecycle()
    val ttsTestState by viewModel.ttsTestState.collectAsStateWithLifecycle()
    val youdaoPreviewState by viewModel.youdaoPreviewState.collectAsStateWithLifecycle()
    val systemPreviewState by viewModel.systemPreviewState.collectAsStateWithLifecycle()
    val edgeVoiceZh by viewModel.edgeVoiceZh.collectAsStateWithLifecycle()
    val edgeUseDefaultEn by viewModel.edgeUseDefaultEn.collectAsStateWithLifecycle()
    val edgeVoiceEn by viewModel.edgeVoiceEn.collectAsStateWithLifecycle()
    val edgePreviewState by viewModel.edgePreviewState.collectAsStateWithLifecycle()
    val systemVoicesLoading by viewModel.systemVoicesLoading.collectAsStateWithLifecycle()
    val systemVoicesZh by viewModel.systemVoicesZh.collectAsStateWithLifecycle()
    val systemEnVoices by viewModel.systemEnVoices.collectAsStateWithLifecycle()
    val systemUseDefaultEn by viewModel.systemUseDefaultEn.collectAsStateWithLifecycle()
    val systemVoiceZh by viewModel.systemVoiceZh.collectAsStateWithLifecycle()
    val systemVoiceEn by viewModel.systemVoiceEn.collectAsStateWithLifecycle()
    val saveMessage by viewModel.ttsSaveMessage.collectAsStateWithLifecycle()
    var showTtsKey by remember { mutableStateOf(false) }
    var showClearTtsConfirm by remember { mutableStateOf(false) }
    // A seeded-but-untouched key counts as present (the secret stays stored);
    // only a dirty empty field blocks 测试并试听/保存并启用.
    val ttsKeyPresent = ttsForm.apiKey.trim().isNotBlank() ||
        (!ttsForm.apiKeyDirty && ttsForm.apiKeySavedHint.isNotEmpty())
    val ttsFormComplete =
        ttsForm.baseUrl.trim().isNotBlank() && ttsKeyPresent && ttsForm.model.trim().isNotBlank()

    val active = ttsActive

    // Enumerate the engine's voices when the 系统语音 section expands (a
    // settings visit without any prior dictation would otherwise find the
    // engine uninitialized; nothing binds until the section is opened).
    LaunchedEffect(ttsSource == TtsSource.SYSTEM) {
        if (ttsSource == TtsSource.SYSTEM) viewModel.loadSystemVoices()
    }

    SettingsSubPage(title = "发音来源", onBack = onBack) {
        // The sub-page shell installs the message channel for exactly this
        // content, so the page's own confirmations are raised from in here.
        val messages = LocalMessages.current
        // 保存并启用 is announced only once its write has actually landed: the
        // view model reports 已保存发音配置 / 保存失败 as a one-shot message, so
        // a failed write can never be shown as a success (it used to be,
        // because the save swallowed its exception and returned Unit).
        LaunchedEffect(saveMessage) {
            val message = saveMessage ?: return@LaunchedEffect
            messages.show(message)
            viewModel.clearTtsSaveMessage()
        }
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
                    // 系统语音音色: the engine's own selectable voices for
                    // each dictation language (中文 + 英文), with a 试听
                    // button per voice. A switch takes effect immediately.
                    // Android exposes no voice display names — see
                    // SystemSpeaker's naming helpers.
                    SystemVoiceSection(
                        loading = systemVoicesLoading,
                        zhVoices = systemVoicesZh,
                        enVoices = systemEnVoices,
                        useDefaultEn = systemUseDefaultEn,
                        zhKey = systemVoiceZh,
                        enKey = systemVoiceEn,
                        onZhChange = viewModel::onSystemVoiceZhChange,
                        onUseDefaultEnChange = viewModel::onSystemUseDefaultEnChange,
                        onEnChange = viewModel::onSystemVoiceEnChange,
                        onPreviewZh = viewModel::previewSystemVoiceZh,
                        onPreviewEn = viewModel::previewSystemVoiceEn,
                        previewState = systemPreviewState,
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
                    ProviderPresetList(
                        items = TTS_PROVIDER_PRESETS.map { preset ->
                            ProviderPresetItem(
                                id = preset.id,
                                title = preset.label,
                                detail = if (preset.id == "mimo") {
                                    "限时免费，需自备 API Key"
                                } else {
                                    null
                                },
                                saved = ttsStoredConfigs.containsKey(preset.id),
                                inUse = ttsActivePresetId == preset.id,
                            )
                        },
                        selectedId = ttsPresetId,
                        onSelect = { id ->
                            TTS_PROVIDER_PRESETS.firstOrNull { it.id == id }
                                ?.let(viewModel::onTtsPresetChange)
                        },
                    )
                },
            )
        }
        if (ttsSource == TtsSource.CUSTOM) {
            SettingsSectionHeader("OpenAI 兼容语音接口设置")
            ProviderFormBody {
                // 接口类型 only matters for the hand-rolled preset; the named
                // presets fix the wire shape.
                if (ttsPresetId == "custom") {
                    TtsApiKindDropdown(
                        selected = ttsForm.api,
                        onSelect = viewModel::onTtsApiChange,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ProviderBaseUrlField(
                    value = ttsForm.baseUrl,
                    onValueChange = viewModel::onTtsBaseUrlChange,
                    readOnly = ttsPresetId == "mimo",
                )
                ProviderApiKeyField(
                    apiKey = ttsForm.apiKey,
                    onValueChange = viewModel::onTtsApiKeyChange,
                    dirty = ttsForm.apiKeyDirty,
                    savedHint = ttsForm.apiKeySavedHint,
                    purpose = "发音请求",
                    visible = showTtsKey,
                    onVisibleChange = { showTtsKey = it },
                )
                ProviderModelField(
                    value = ttsForm.model,
                    onValueChange = viewModel::onTtsModelChange,
                    readOnly = ttsPresetId == "mimo",
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
                // 小米 MiMo previews from its voice dropdowns; every other
                // preset gets the explicit 测试并试听 button. Both report into
                // the same status line below.
                if (ttsPresetId != "mimo") {
                    ProviderTestButton(
                        text = "测试并试听",
                        enabled = ttsFormComplete && ttsTestState != TtsTestState.Testing,
                        onClick = viewModel::testTtsVoice,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                ProviderTestStatus(
                    state = ttsTestState,
                    testingText = "生成试听中…",
                    okText = "连接成功，已播放试听",
                )
                ProviderActionRow(
                    clearEnabled = ttsStoredConfigs.containsKey(ttsPresetId),
                    onClear = { showClearTtsConfirm = true },
                    saveEnabled = ttsFormComplete,
                    onSave = { viewModel.saveTtsConfig() },
                )
            }
        }
    }
    if (showClearTtsConfirm) {
        ProviderClearConfirmDialog(
            onDismiss = { showClearTtsConfirm = false },
            onConfirm = {
                showClearTtsConfirm = false
                viewModel.clearTtsConfig()
            },
        )
    }
}

/**
 * 设置 → 拍照识词: the BYOK OpenAI-compatible vision provider form (preset
 * picker + base URL / key / model), 测试连接 and 保存并启用. Same widget set
 * as 发音来源's custom-TTS form — including the 清除该服务商的配置？
 * confirmation, which used to be a byte-identical second copy.
 */
@Composable
fun OcrProviderSettingsPage(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val ocrForm by viewModel.ocrForm.collectAsStateWithLifecycle()
    val ocrPresetId by viewModel.ocrPresetId.collectAsStateWithLifecycle()
    val ocrStoredConfigs by viewModel.ocrStoredConfigs.collectAsStateWithLifecycle()
    val ocrActivePresetId by viewModel.ocrActivePresetId.collectAsStateWithLifecycle()
    val ocrTestState by viewModel.ocrTestState.collectAsStateWithLifecycle()
    val saveMessage by viewModel.ocrSaveMessage.collectAsStateWithLifecycle()
    var showApiKey by remember { mutableStateOf(false) }
    var showClearOcrConfirm by remember { mutableStateOf(false) }
    // A seeded-but-untouched key counts as present (the secret stays stored);
    // only a dirty empty field blocks 测试连接/保存并启用.
    val ocrKeyPresent = ocrForm.apiKey.trim().isNotBlank() ||
        (!ocrForm.apiKeyDirty && ocrForm.apiKeySavedHint.isNotEmpty())
    val ocrComplete =
        ocrForm.baseUrl.trim().isNotBlank() && ocrKeyPresent && ocrForm.model.trim().isNotBlank()

    SettingsSubPage(title = "拍照识词", onBack = onBack) {
        val messages = LocalMessages.current
        // Same consume-once save message as 发音来源: 已保存 OCR 服务配置 is
        // only announced for a write that landed, 保存失败 otherwise.
        LaunchedEffect(saveMessage) {
            val message = saveMessage ?: return@LaunchedEffect
            messages.show(message)
            viewModel.clearOcrSaveMessage()
        }
        SettingsSectionHeader("服务商")
        SettingsCard {
            ProviderPresetList(
                items = OCR_PROVIDER_PRESETS.map { preset ->
                    ProviderPresetItem(
                        id = preset.id,
                        title = preset.label,
                        detail = preset.model.ifBlank { null },
                        saved = ocrStoredConfigs.containsKey(preset.id),
                        inUse = ocrActivePresetId == preset.id,
                    )
                },
                selectedId = ocrPresetId,
                onSelect = { id ->
                    OCR_PROVIDER_PRESETS.firstOrNull { it.id == id }
                        ?.let(viewModel::onOcrPresetChange)
                },
            )
        }

        SettingsSectionHeader("接口设置")
        ProviderFormBody {
            ProviderBaseUrlField(
                value = ocrForm.baseUrl,
                onValueChange = viewModel::onOcrBaseUrlChange,
                // Named presets pin their own endpoint — only 自定义 is
                // hand-typed (mirrors the TTS form's locked mimo URL).
                readOnly = ocrPresetId != "custom",
            )
            ProviderApiKeyField(
                apiKey = ocrForm.apiKey,
                onValueChange = viewModel::onOcrApiKeyChange,
                dirty = ocrForm.apiKeyDirty,
                savedHint = ocrForm.apiKeySavedHint,
                purpose = "拍照识词请求",
                visible = showApiKey,
                onVisibleChange = { showApiKey = it },
            )
            ProviderModelField(
                value = ocrForm.model,
                onValueChange = viewModel::onOcrModelChange,
                readOnly = false,
            )
            ProviderTestButton(
                text = "测试连接",
                enabled = ocrComplete && ocrTestState != OcrTestState.Testing,
                onClick = viewModel::testOcrConnection,
                modifier = Modifier.padding(top = 16.dp),
            )
            ProviderTestStatus(
                state = ocrTestState,
                testingText = "测试中…",
                okText = "连接成功，模型 ${ocrForm.model.trim()} 可用",
            )
            ProviderActionRow(
                clearEnabled = ocrStoredConfigs.containsKey(ocrPresetId),
                onClear = { showClearOcrConfirm = true },
                saveEnabled = ocrComplete,
                onSave = { viewModel.saveOcrConfig() },
            )
        }
        Text(
            OCR_DISCLAIMER,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        )
    }
    if (showClearOcrConfirm) {
        ProviderClearConfirmDialog(
            onDismiss = { showClearOcrConfirm = false },
            onConfirm = {
                showClearOcrConfirm = false
                viewModel.clearOcrConfig()
            },
        )
    }
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
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
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
        ProviderTestButton(
            text = "测试并试听",
            enabled = state != TtsTestState.Testing,
            onClick = onPreview,
        )
        ProviderTestStatus(
            state = state,
            testingText = testingText,
            okText = okText,
        )
    }
}
