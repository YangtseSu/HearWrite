package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.yangtse.hearwrite.data.EDGE_VOICE_CATALOG
import org.yangtse.hearwrite.data.MIMO_VOICES
import org.yangtse.hearwrite.data.SystemVoiceInfo
import org.yangtse.hearwrite.data.edgeDefaultVoiceFor
import org.yangtse.hearwrite.ui.theme.hearWriteSemantics

// Everything the two BYOK provider forms share. 发音来源's OpenAI 兼容语音 form
// and 拍照识词's OCR form are one form — a preset picker, a Base URL / API Key
// / 模型 trio, a test control with its status line, and a 清除配置 / 保存并启用
// row — and they used to be line-for-line copies that promptly drifted: only
// the OCR form validated its base URL, only it checked its save result, and its
// status block existed a second time inline. Each shared piece now exists once,
// so a fix lands in both; only the copy (labels, hints, test buttons, ok text)
// stays at the call site.
//
// The voice pickers below live here for the same reason: 系统语音, 微软 Edge and
// 小米 MiMo each had their own dropdown implementation of one widget — a
// read-only field over labelled choices plus a 试听 button — differing only in
// the entries they enumerate and how a stale stored key resolves.

/** One preset row's display data, projected from a preset by the caller. */
@Immutable
data class ProviderPresetItem(
    val id: String,
    val title: String,
    /** Form-specific detail line: the OCR preset's model, or 限时免费，需自备 API Key. */
    val detail: String?,
    /** A stored config exists for this preset (已保存). */
    val saved: Boolean,
    /** This preset holds the config actually in effect (正在使用). */
    val inUse: Boolean,
)

/**
 * The preset picker: one radio row per preset inside a group card, last row
 * without a divider. [onSelect] hands back the preset id; the caller maps it
 * to its own preset object.
 *
 * The row's supporting line carries the state, not the radio: the radio shows
 * where the *draft* points (switch a chip and the highlight follows), so
 * 正在使用 marks the configuration genuinely in effect — which is a different
 * thing the moment the user peeks at another provider.
 */
@Composable
fun ProviderPresetList(
    items: List<ProviderPresetItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        items.forEachIndexed { index, item ->
            SettingsRadioRow(
                title = item.title,
                supporting = listOfNotNull(
                    item.detail,
                    // 正在使用 replaces 已保存 rather than joining it: a config
                    // in effect is by definition saved, and both marks on one
                    // row read as noise.
                    if (item.inUse) "正在使用" else null,
                    if (item.saved && !item.inUse) "已保存" else null,
                ).joinToString(" · ").ifEmpty { null },
                selected = selectedId == item.id,
                divider = index < items.lastIndex,
                onClick = { onSelect(item.id) },
            )
        }
    }
}

/** The padded card body both provider forms' controls live in. */
@Composable
fun ProviderFormBody(content: @Composable ColumnScope.() -> Unit) {
    SettingsCard {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            content = content,
        )
    }
}

/**
 * 接口地址（Base URL） field. [readOnly] pins the endpoint for the presets
 * that fix their own (小米 MiMo, every named OCR provider) — only 自定义 is
 * hand-typed, so the URL keyboard is always the right one.
 */
@Composable
fun ProviderBaseUrlField(
    value: String,
    onValueChange: (String) -> Unit,
    readOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        label = { Text("接口地址（Base URL）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * API Key field with its reveal toggle and saved-key hint. [dirty] false with a
 * [savedHint] means the field was seeded from a stored config: the secret stays
 * out of the field entirely — a seeded key is never shown in full, its last
 * four digits are reported instead — and typing replaces it. [purpose]
 * completes the hint sentence ("…仅用于发音请求" / "…仅用于拍照识词请求").
 *
 * The hint lives in supportingText rather than as a placeholder: an empty value
 * keeps the label docked inside the field, which covers the placeholder, so a
 * placeholder hint would be invisible exactly when it matters.
 */
@Composable
fun ProviderApiKeyField(
    apiKey: String,
    onValueChange: (String) -> Unit,
    dirty: Boolean,
    savedHint: String,
    purpose: String,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = if (dirty) apiKey else "",
        onValueChange = onValueChange,
        label = { Text("API Key") },
        supportingText = {
            if (!dirty && savedHint.isNotEmpty()) {
                Text("已保存 ••••$savedHint（输入即替换）· Key 仅保存在本机")
            } else {
                Text("Key 仅保存在本机，仅用于$purpose")
            }
        },
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = { onVisibleChange(!visible) }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "隐藏 API Key" else "显示 API Key",
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        // A newline in the key would land inside `Authorization: Bearer …`
        // and surface as a bogus 网络请求失败.
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    )
}

/** 模型 field (the model id the provider is asked for). */
@Composable
fun ProviderModelField(
    value: String,
    onValueChange: (String) -> Unit,
    readOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        label = { Text("模型") },
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    )
}

/** The provider form's test control (测试并试听 / 测试连接). */
@Composable
fun ProviderTestButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(text)
    }
}

/** The custom-TTS test/preview status line (spinner → ok / failure). */
@Composable
fun ProviderTestStatus(
    state: TtsTestState,
    testingText: String,
    okText: String,
) {
    ProviderStatusLine(
        testing = state == TtsTestState.Testing,
        testingText = testingText,
        text = when (state) {
            TtsTestState.Idle, TtsTestState.Testing -> null
            TtsTestState.Ok -> okText
            is TtsTestState.Failed -> state.message
        },
        success = state == TtsTestState.Ok,
    )
}

/**
 * The OCR form's twin of [ProviderTestStatus] — [OcrTestState] is a separate
 * flow with the same shape, and its ok copy names the tested model.
 */
@Composable
fun ProviderTestStatus(
    state: OcrTestState,
    testingText: String,
    okText: String,
) {
    ProviderStatusLine(
        testing = state == OcrTestState.Testing,
        testingText = testingText,
        text = when (state) {
            OcrTestState.Idle, OcrTestState.Testing -> null
            OcrTestState.Ok -> okText
            is OcrTestState.Failed -> state.message
        },
        success = state == OcrTestState.Ok,
    )
}

/**
 * One Testing/Ok/Failed line: the spinner while a run is in flight, the call
 * site's ok copy in the success accent, the provider or validation message in
 * the error colour. Nothing at all before the first run.
 */
@Composable
private fun ProviderStatusLine(
    testing: Boolean,
    testingText: String,
    text: String?,
    success: Boolean,
) {
    val lineModifier = Modifier.padding(top = 8.dp)
    when {
        testing -> Row(
            modifier = lineModifier,
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
        text == null -> Unit
        else -> Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (success) hearWriteSemantics.success else MaterialTheme.colorScheme.error,
            modifier = lineModifier,
        )
    }
}

/**
 * 清除配置 / 保存并启用 row. The clear button exists only when the selected
 * preset has a stored config ([clearEnabled]); the save button takes the
 * remaining width — it is the row's primary action.
 */
@Composable
fun ProviderActionRow(
    clearEnabled: Boolean,
    onClear: () -> Unit,
    saveEnabled: Boolean,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (clearEnabled) {
            OutlinedButton(onClick = onClear) {
                Text("清除配置")
            }
        }
        Button(
            onClick = onSave,
            enabled = saveEnabled,
            modifier = Modifier.weight(1f),
        ) {
            Text("保存并启用")
        }
    }
}

/**
 * The 清除配置 confirmation, shared by both forms (it used to exist twice,
 * which kept the copies identical only by luck). [onConfirm] owns closing the
 * dialog and the actual clear.
 */
@Composable
fun ProviderClearConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清除该服务商的配置？") },
        text = { Text("将删除已保存的接口地址、Key 与模型，草稿恢复为预设默认值。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("清除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** One choice in a [ProviderVoiceDropdown] menu: an engine id + its display label. */
@Immutable
data class VoiceEntry(val key: String, val label: String)

/** The 默认（引擎选择） choice: the engine picks, no stored key. */
private const val ENGINE_DEFAULT_LABEL = "默认（引擎选择）"

/**
 * The voice picker behind all three voice sections: a read-only field showing
 * the current choice's label, opening a menu of [entries], plus a 试听 button
 * synthesizing a sample in that choice without changing it.
 *
 * [selectedKey] is an opaque engine id. A key the engine no longer offers
 * resolves for display and for the preview to, in order: the entry matching
 * [defaultKey], then — only when [fallbackToFirst] allows it — the first
 * entry, then [fallbackLabel]. 系统语音 passes false: an unmatched key there
 * means 默认（引擎选择） (the engine speaks), and silently showing the first
 * voice of the list would name a selection the user never made.
 * [leadingEntryLabel] prepends an explicit empty-key choice (系统语音's
 * 默认（引擎选择）); [previewEnabled] lets a form block previews while its
 * config is incomplete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderVoiceDropdown(
    label: String,
    entries: List<VoiceEntry>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    defaultKey: String? = null,
    fallbackToFirst: Boolean = true,
    fallbackLabel: String? = null,
    leadingEntryLabel: String? = null,
    previewing: Boolean = false,
    previewEnabled: Boolean = true,
    onPreview: ((String) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val effective = entries.firstOrNull { it.key == selectedKey }
        ?: defaultKey?.let { key -> entries.firstOrNull { it.key == key } }
        ?: if (fallbackToFirst) entries.firstOrNull() else null
    val displayLabel = effective?.label ?: fallbackLabel ?: label

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
                value = effective?.label ?: fallbackLabel.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
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
                if (leadingEntryLabel != null) {
                    DropdownMenuItem(
                        text = { Text(leadingEntryLabel) },
                        onClick = {
                            onSelect("")
                            expanded = false
                        },
                    )
                }
                entries.forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(entry.label) },
                        onClick = {
                            onSelect(entry.key)
                            expanded = false
                        },
                    )
                }
            }
        }
        if (onPreview != null) {
            IconButton(
                // An explicit empty-key choice is itself previewable (the
                // engine's own default voice speaks), so a resolved entry is
                // not required for the button to be live.
                onClick = { onPreview(effective?.key.orEmpty()) },
                enabled = previewEnabled && !previewing &&
                    (effective != null || leadingEntryLabel != null),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "试听 $displayLabel",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 英文使用默认音色 switch row (shared by the three voice sections). The row —
 * not the switch — is the hit target and carries the state; the indicator is
 * purely visual, so TalkBack announces one control with the setting's name and
 * its on/off value instead of a second, unlabelled "开关" next to the label.
 */
@Composable
fun DefaultEnglishVoiceRow(
    useDefaultEn: Boolean,
    onUseDefaultEnChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // toggleable before the padding: the row's spacing belongs to the
            // touch target, not outside it (same order as SettingsRow).
            .toggleable(
                value = useDefaultEn,
                role = Role.Switch,
                onValueChange = onUseDefaultEnChange,
            )
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "英文使用默认音色",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = useDefaultEn, onCheckedChange = null)
    }
}

/**
 * 微软 Edge 音色 picker (shown on the 发音来源 page when the Edge source is
 * selected): the 默认音色 dropdown (zh-CN voices — bilingual, they speak
 * both Chinese and English), an 英文使用默认音色 switch (default on), and —
 * only when the switch is off — the 英文音色 dropdown. Each dropdown carries a
 * 试听 button that synthesizes a sample in the current selection without
 * changing it (the default voice previews a mixed Chinese+English sample;
 * English voices an English sample). Selections apply immediately (cache keys
 * bind voice+rate so clips regenerate) and persist as explicit shortNames: a
 * blank stored value only survives from older versions and resolves to the
 * built-in default for display.
 */
@Composable
fun EdgeVoiceSection(
    defaultVoice: String,
    useDefaultEn: Boolean,
    englishVoice: String,
    previewState: TtsTestState,
    onDefaultVoiceChange: (String) -> Unit,
    onUseDefaultEnChange: (Boolean) -> Unit,
    onEnglishVoiceChange: (String) -> Unit,
    onPreview: (shortName: String, lang: String) -> Unit,
) {
    val previewing = previewState is TtsTestState.Testing

    Column {
        ProviderVoiceDropdown(
            label = "默认音色",
            entries = EDGE_ZH_VOICES,
            selectedKey = defaultVoice,
            defaultKey = edgeDefaultVoiceFor("zh"),
            previewing = previewing,
            onSelect = onDefaultVoiceChange,
            onPreview = { onPreview(it, "zh") },
        )
        DefaultEnglishVoiceRow(
            useDefaultEn = useDefaultEn,
            onUseDefaultEnChange = onUseDefaultEnChange,
        )
        if (!useDefaultEn) {
            ProviderVoiceDropdown(
                label = "英文音色",
                entries = EDGE_EN_VOICES,
                selectedKey = englishVoice,
                defaultKey = edgeDefaultVoiceFor("en"),
                previewing = previewing,
                onSelect = onEnglishVoiceChange,
                onPreview = { onPreview(it, "en") },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // Preview status lives under the dropdowns; a transient per-voice
        // result is shown once here (the dropdowns re-render but state is a
        // single shared flow).
        ProviderTestStatus(
            state = previewState,
            testingText = "试听生成中…",
            okText = "已播放试听",
        )
    }
}

/**
 * The Edge catalog split once, not per recomposition: zh-CN voices (bilingual),
 * and one merged English list — the 美式/英式 prefixes come from the friendly
 * names (美式 Aria / 英式 Sonia…), so a single dropdown reads as one ordered
 * choice list without a separate 英文地区 step.
 */
private val EDGE_ZH_VOICES: List<VoiceEntry> = EDGE_VOICE_CATALOG
    .filter { it.locale == "zh-CN" }
    .map { VoiceEntry(it.shortName, it.friendlyName) }

private val EDGE_EN_VOICES: List<VoiceEntry> = EDGE_VOICE_CATALOG
    .filter { it.locale == "en-US" || it.locale == "en-GB" }
    .map { VoiceEntry(it.shortName, it.friendlyName) }

/** The 8 官方音色, as dropdown entries. */
private val MIMO_VOICE_ENTRIES: List<VoiceEntry> = MIMO_VOICES.map { (id, display) ->
    VoiceEntry(id, display)
}

/**
 * 系统语音音色 picker (shown on the 发音来源 page when 系统语音 is
 * selected): the 默认音色 dropdown (zh voices — a zh-capable voice speaks
 * both Chinese and English), an 英文使用默认音色 switch (default on) and —
 * only when the switch is off — the dedicated 英文音色 dropdown with all
 * English voices merged in one list (美式英语1/英式英语1-style labels,
 * [SystemVoiceInfo]). Each dropdown carries a 试听 button. Voice labels come
 * from [SystemVoiceInfo] (a real engine name when meaningful, otherwise
 * 中文男声1/中文女声1-style naming — engines like Google expose raw ids
 * only). The current selection is shown in the field; a voice switch applies
 * immediately and persists (the system speaker live-follows). While the
 * engine enumerates (a spinner replaces the dropdowns) the selected label
 * stays visible so the picker never blanks mid-session. When the engine
 * exposes no selectable voices the section shows nothing — the source still
 * speaks with the engine's default.
 */
@Composable
fun SystemVoiceSection(
    loading: Boolean,
    zhVoices: List<SystemVoiceInfo>?,
    enVoices: List<SystemVoiceInfo>?,
    useDefaultEn: Boolean,
    zhKey: String,
    enKey: String,
    onZhChange: (String) -> Unit,
    onUseDefaultEnChange: (Boolean) -> Unit,
    onEnChange: (String) -> Unit,
    onPreviewZh: (String) -> Unit,
    onPreviewEn: (String) -> Unit,
    previewState: TtsTestState,
) {
    val enEmpty = enVoices.isNullOrEmpty()
    if (!loading && zhVoices.isNullOrEmpty() && enEmpty && previewState == TtsTestState.Idle) return

    Column {
        if (loading) {
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "正在读取系统音色…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val previewing = previewState == TtsTestState.Testing
        val zhList = zhVoices.orEmpty()
        // Mapped once per engine enumeration, not once per recomposition (the
        // picker's own expanded state recomposes this section).
        val zhEntries = remember(zhList) { zhList.map { VoiceEntry(it.key, it.label) } }
        // A single selectable voice adds nothing over 默认（引擎选择） — hide
        // the picker entirely (e.g. MiBrain exposes one bare zh voice).
        if (zhList.size >= 2 && !loading) {
            ProviderVoiceDropdown(
                label = "默认音色",
                entries = zhEntries,
                selectedKey = zhKey,
                // An unmatched key is 默认（引擎选择）, never the first voice:
                // the field must not name a selection the user did not make.
                fallbackToFirst = false,
                fallbackLabel = ENGINE_DEFAULT_LABEL,
                leadingEntryLabel = ENGINE_DEFAULT_LABEL,
                previewing = previewing,
                onSelect = onZhChange,
                onPreview = onPreviewZh,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // 英文使用默认音色: same switch semantics as the Edge source.
        DefaultEnglishVoiceRow(
            useDefaultEn = useDefaultEn,
            onUseDefaultEnChange = onUseDefaultEnChange,
        )
        if (!useDefaultEn) {
            val enList = enVoices.orEmpty()
            val enEntries = remember(enList) { enList.map { VoiceEntry(it.key, it.label) } }
            // Same single-voice rule as the zh picker: one candidate is not
            // a choice, so no dropdown (engine default already covers it).
            if (enList.size >= 2 && !loading) {
                ProviderVoiceDropdown(
                    label = "英文音色",
                    entries = enEntries,
                    selectedKey = enKey,
                    fallbackToFirst = false,
                    fallbackLabel = ENGINE_DEFAULT_LABEL,
                    leadingEntryLabel = ENGINE_DEFAULT_LABEL,
                    previewing = previewing,
                    onSelect = onEnChange,
                    onPreview = onPreviewEn,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (!loading) {
            Text(
                "默认音色未选择时使用系统默认；音色列表因系统语音引擎而异。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        // Per-voice 试听 feedback (shared by both languages' buttons): a
        // spinner while speaking, then 已播放试听 or the failure message.
        ProviderTestStatus(
            state = previewState,
            testingText = "试听播放中…",
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
@Composable
fun MimoVoiceSection(
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
    ProviderVoiceDropdown(
        label = "默认音色",
        entries = MIMO_VOICE_ENTRIES,
        selectedKey = defaultVoice.ifBlank { "冰糖" },
        previewing = previewing,
        previewEnabled = previewEnabled,
        onSelect = onDefaultVoiceChange,
        onPreview = { onPreview(it, false) },
        modifier = Modifier.padding(top = 4.dp),
    )
    DefaultEnglishVoiceRow(
        useDefaultEn = useDefaultEn,
        onUseDefaultEnChange = onUseDefaultEnChange,
    )
    if (!useDefaultEn) {
        ProviderVoiceDropdown(
            label = "英文音色",
            entries = MIMO_VOICE_ENTRIES,
            selectedKey = englishVoice.ifBlank { "Chloe" },
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
