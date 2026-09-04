package org.yangtse.hearwrite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.data.DEFAULT_OCR_PRESET
import org.yangtse.hearwrite.data.DEFAULT_TTS_PRESET
import org.yangtse.hearwrite.data.OCR_PROVIDER_PRESETS
import org.yangtse.hearwrite.data.OcrProviderConfig
import org.yangtse.hearwrite.data.OcrProviderPreset
import org.yangtse.hearwrite.data.TTS_PROVIDER_PRESETS
import org.yangtse.hearwrite.data.TtsApiKind
import org.yangtse.hearwrite.data.TtsProviderConfig
import org.yangtse.hearwrite.data.TtsProviderException
import org.yangtse.hearwrite.data.TtsProviderPreset
import org.yangtse.hearwrite.domain.MAX_SPEECH_RATE
import org.yangtse.hearwrite.domain.MIN_SPEECH_RATE
import org.yangtse.hearwrite.domain.ThemeMode
import org.yangtse.hearwrite.domain.TtsSource
import kotlin.math.roundToInt

/** Result of the OCR 测试连接 button. */
sealed interface OcrTestState {
    data object Idle : OcrTestState
    data object Testing : OcrTestState
    data object Ok : OcrTestState
    data class Failed(val message: String) : OcrTestState
}

/** Result of the custom-TTS 测试并试听 button. */
sealed interface TtsTestState {
    data object Idle : TtsTestState
    data object Testing : TtsTestState
    data object Ok : TtsTestState
    data class Failed(val message: String) : TtsTestState
}

/** Editable custom-TTS provider form for the selected preset (draft per chip). */
data class TtsFormDraft(
    val api: TtsApiKind,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val voiceEn: String,
    val voiceZh: String,
    val responseFormat: String,
)

/** Editable OCR provider form for the selected preset (draft per chip). */
data class OcrFormDraft(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

/**
 * Settings screen state: 听写 (语速, 朗读释义), 语音
 * (发音来源 incl. the OpenAI-compatible custom provider form, 提示音),
 * OCR 识别 (BYOK vision provider) and 外观 (主题 system/light/dark) all
 * persist through the DataStore settings repository; the rate applies live
 * to the shared system speaker. Interval and auto-next are not duplicated
 * here: the Home playback panel and the dictation screen adjust them live
 * and write the same DataStore keys. The provider forms (TTS + OCR) keep
 * one draft and one stored config **per 服务商 preset** — switching chips
 * loads that provider's own saved config (or its preset defaults), so no
 * key ever bleeds into another provider's form; disabling the custom TTS
 * source (有道/系统语音) never deletes any stored config.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = (application as HearWriteApplication).settingsRepository
    private val speaker = (application as HearWriteApplication).systemSpeaker
    private val ocrService = (application as HearWriteApplication).ocrService
    private val ttsChain = (application as HearWriteApplication).ttsChain
    private val openAiTts = (application as HearWriteApplication).openAiCompatibleTts
    private val edgeTts = (application as HearWriteApplication).edgeTts

    private val _speechRate = MutableStateFlow(MIN_SPEECH_RATE)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _readTranslation = MutableStateFlow(false)
    val readTranslation: StateFlow<Boolean> = _readTranslation.asStateFlow()

    private val _ttsSource = MutableStateFlow(TtsSource.YOUDAO)
    val ttsSource: StateFlow<TtsSource> = _ttsSource.asStateFlow()

    private val _soundEnabled = MutableStateFlow(true)
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    private val _theme = MutableStateFlow(ThemeMode.SYSTEM)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    // ---- OCR 识别 (拍照识词) BYOK config fields ----------------------------
    // One draft + one stored config per 服务商 preset; the chip switch loads
    // the target provider's own state instead of reusing typed values.

    private val _ocrPresetId = MutableStateFlow(DEFAULT_OCR_PRESET.id)
    val ocrPresetId: StateFlow<String> = _ocrPresetId.asStateFlow()

    private val _ocrForm = MutableStateFlow(ocrDraftFor(DEFAULT_OCR_PRESET, null))
    val ocrForm: StateFlow<OcrFormDraft> = _ocrForm.asStateFlow()

    /** Unsaved drafts per preset id, so a peek at another chip keeps edits. */
    private val ocrDrafts = mutableMapOf<String, OcrFormDraft>()

    /** Mirror of the stored per-preset configs (chip marks + draft seeding). */
    private val _ocrStored = MutableStateFlow<Map<String, OcrProviderConfig>>(emptyMap())
    val ocrStoredConfigs: StateFlow<Map<String, OcrProviderConfig>> = _ocrStored.asStateFlow()

    /** The runtime-active stored config (hub row shows its model then). */
    private val _ocrActive = MutableStateFlow<OcrProviderConfig?>(null)
    val ocrActive: StateFlow<OcrProviderConfig?> = _ocrActive.asStateFlow()

    /** Preset id of the active OCR config (hub row label; "" when none). */
    private val _ocrActiveId = MutableStateFlow("")
    val ocrActivePresetId: StateFlow<String> = _ocrActiveId.asStateFlow()

    private val _ocrTestState = MutableStateFlow<OcrTestState>(OcrTestState.Idle)
    val ocrTestState: StateFlow<OcrTestState> = _ocrTestState.asStateFlow()

    // ---- 自定义音源 (OpenAI-compatible TTS) form fields --------------------

    private val _ttsPresetId = MutableStateFlow(DEFAULT_TTS_PRESET.id)
    val ttsPresetId: StateFlow<String> = _ttsPresetId.asStateFlow()

    private val _ttsForm = MutableStateFlow(ttsDraftFor(DEFAULT_TTS_PRESET, null))
    val ttsForm: StateFlow<TtsFormDraft> = _ttsForm.asStateFlow()

    private val ttsDrafts = mutableMapOf<String, TtsFormDraft>()

    private val _ttsStored = MutableStateFlow<Map<String, TtsProviderConfig>>(emptyMap())
    val ttsStoredConfigs: StateFlow<Map<String, TtsProviderConfig>> = _ttsStored.asStateFlow()

    /** The runtime-active stored config (status line + hub row). */
    private val _ttsActive = MutableStateFlow<TtsProviderConfig?>(null)
    val ttsActive: StateFlow<TtsProviderConfig?> = _ttsActive.asStateFlow()

    private val _ttsTestState = MutableStateFlow<TtsTestState>(TtsTestState.Idle)
    val ttsTestState: StateFlow<TtsTestState> = _ttsTestState.asStateFlow()

    // ---- 微软 Edge 音色 (Edge source voice picker) -------------------------

    /** Currently selected voice shortName per language (zh/en), live from settings. */
    private val _edgeVoiceZh = MutableStateFlow("")
    val edgeVoiceZh: StateFlow<String> = _edgeVoiceZh.asStateFlow()
    private val _edgeVoiceEn = MutableStateFlow("")
    val edgeVoiceEn: StateFlow<String> = _edgeVoiceEn.asStateFlow()

    /**
     * Preview state for one Edge voice (a single sample per voice). Reuses
     * the [TtsTestState] shape: Idle/Testing/Ok/Failed(message).
     */
    private val _edgePreviewState = MutableStateFlow<TtsTestState>(TtsTestState.Idle)
    val edgePreviewState: StateFlow<TtsTestState> = _edgePreviewState.asStateFlow()

    init {
        // Edge 音色: per-language selection (blank stored voice = the app
        // default) live-follows the DataStore settings.
        viewModelScope.launch {
            combine(settings.edgeVoiceZh, settings.edgeVoiceEn) { zh, en -> zh to en }
                .collect { (zh, en) ->
                    _edgeVoiceZh.value = zh
                    _edgeVoiceEn.value = en
                }
        }
    }

    /** Change the Edge voice for [lang] ("" = default) and persist it. */
    fun onEdgeVoiceChange(lang: String, shortName: String) {
        val value = shortName.trim()
        viewModelScope.launch {
            try {
                if (lang.startsWith("zh", ignoreCase = true)) {
                    _edgeVoiceZh.value = value
                    settings.setEdgeVoiceZh(value)
                } else {
                    _edgeVoiceEn.value = value
                    settings.setEdgeVoiceEn(value)
                }
            } catch (e: Exception) {
                // DataStore failures must never crash the screen (AGENTS.md).
            }
        }
    }

    /**
     * Preview one Edge voice (shortName) with a sample in its language.
     * Plays through the chain's [ttsChain.playTestClip] on the caller's
     * thread (the clip is written to a temp file first).
     */
    fun previewEdgeVoice(shortName: String, lang: String) {
        if (_edgePreviewState.value is TtsTestState.Testing) return
        val sample = if (lang.startsWith("zh", ignoreCase = true)) "苹果" else "apple"
        _edgePreviewState.value = TtsTestState.Testing
        viewModelScope.launch(Dispatchers.IO) {
            val error = try {
                val mp3 = edgeTts.previewVoice(shortName, sample)
                if (mp3 == null) {
                    "音频生成失败，请检查网络"
                } else {
                    val file = File.createTempFile("edge_preview_", ".mp3")
                    file.writeBytes(mp3)
                    val played = ttsChain.playTestClip(file)
                    file.delete()
                    if (played) null else "音频已生成，但本机播放失败"
                }
            } catch (e: Exception) {
                "网络请求失败，请检查网络"
            }
            _edgePreviewState.value =
                if (error == null) TtsTestState.Ok else TtsTestState.Failed(error)
        }
    }

    // ---- 发音缓存 (downloaded clips under cacheDir/tts) --------------------

    /** Snapshot of the cached pronunciation files (Youdao + provider clips). */
    data class TtsCacheInfo(val fileCount: Int, val bytes: Long)

    private val _ttsCacheInfo = MutableStateFlow<TtsCacheInfo?>(null)
    val ttsCacheInfo: StateFlow<TtsCacheInfo?> = _ttsCacheInfo.asStateFlow()

    /** Bumped after each successful 清空发音缓存 (hub shows a toast). */
    private val _ttsCacheCleared = MutableStateFlow(0)
    val ttsCacheCleared: StateFlow<Int> = _ttsCacheCleared.asStateFlow()

    init {
        viewModelScope.launch { _speechRate.value = settings.speechRate.first() }
        viewModelScope.launch { _readTranslation.value = settings.readTranslation.first() }
        viewModelScope.launch { _ttsSource.value = settings.ttsSource.first() }
        viewModelScope.launch { _soundEnabled.value = settings.soundEnabled.first() }
        viewModelScope.launch { _theme.value = settings.theme.first() }
        // Per-provider storage: mirror the stored configs and the active
        // preset; the selected chip starts on the active provider (preset
        // defaults with a blank key when none is saved yet — BYOK only).
        viewModelScope.launch { settings.ocrProviderConfigs.collect { _ocrStored.value = it } }
        viewModelScope.launch {
            settings.ocrProviderConfig.collect { _ocrActive.value = it }
        }
        viewModelScope.launch {
            settings.ocrActivePresetId.collect { _ocrActiveId.value = it }
        }
        viewModelScope.launch {
            val activeId = settings.ocrActivePresetId.first()
            // Restore only presets still selectable — an id of a preset
            // removed from the list must not become an invisible active
            // slot with no chip to return to.
            if (activeId.isNotBlank() && OCR_PROVIDER_PRESETS.any { it.id == activeId }) {
                _ocrPresetId.value = activeId
                _ocrForm.value =
                    ocrDraftFor(ocrPresetById(activeId), settings.ocrProviderConfigs.first()[activeId])
            }
        }
        viewModelScope.launch { settings.ttsProviderConfigs.collect { _ttsStored.value = it } }
        viewModelScope.launch {
            settings.ttsProviderConfig.collect { _ttsActive.value = it }
        }
        viewModelScope.launch {
            val activeId = settings.ttsActivePresetId.first()
            // Restore only presets still selectable — an id of a preset
            // removed from the list (智谱 GLM / 硅基流动 / OpenAI) must not
            // become an invisible active slot with no chip to return to.
            if (activeId.isNotBlank() && TTS_PROVIDER_PRESETS.any { it.id == activeId }) {
                _ttsPresetId.value = activeId
                _ttsForm.value =
                    ttsDraftFor(ttsPresetById(activeId), settings.ttsProviderConfigs.first()[activeId])
            }
        }
        // Cache summary is computed off the main thread; clips only appear
        // from dictation/preview, so a one-shot scan at open is enough (the
        // hub re-scans when returning from a sub-page that may have tested
        // a provider).
        viewModelScope.launch(Dispatchers.IO) { _ttsCacheInfo.value = scanTtsCache() }
    }

    fun onSpeechRateChange(rate: Float) {
        // Slider drags emit float noise; snap to the 0.1 step for storage.
        val snapped = (rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE) * 10).roundToInt() / 10f
        _speechRate.value = snapped
        speaker.setSpeechRate(snapped)
        viewModelScope.launch { settings.setSpeechRate(snapped) }
    }

    fun onReadTranslationChange(on: Boolean) {
        _readTranslation.value = on
        viewModelScope.launch { settings.setReadTranslation(on) }
    }

    fun onTtsSourceChange(source: TtsSource) {
        _ttsSource.value = source
        viewModelScope.launch { settings.setTtsSource(source) }
    }

    fun onSoundEnabledChange(on: Boolean) {
        _soundEnabled.value = on
        viewModelScope.launch { settings.setSoundEnabled(on) }
    }

    fun onThemeChange(mode: ThemeMode) {
        _theme.value = mode
        viewModelScope.launch { settings.setTheme(mode) }
    }

    private fun ttsPresetById(id: String): TtsProviderPreset =
        TTS_PROVIDER_PRESETS.firstOrNull { it.id == id } ?: DEFAULT_TTS_PRESET

    /** Seed a preset's draft: its stored config, else preset defaults + blank key. */
    private fun ttsDraftFor(preset: TtsProviderPreset, cfg: TtsProviderConfig?): TtsFormDraft =
        cfg?.let {
            TtsFormDraft(
                api = it.api,
                baseUrl = it.baseUrl,
                apiKey = it.apiKey,
                model = it.model,
                voiceEn = it.voiceEn,
                voiceZh = it.voiceZh,
                responseFormat = it.responseFormat.orEmpty(),
            )
        } ?: TtsFormDraft(
            api = preset.api,
            baseUrl = preset.baseUrl,
            apiKey = "",
            model = preset.model,
            voiceEn = preset.voiceEn,
            voiceZh = preset.voiceZh,
            responseFormat = preset.responseFormat.orEmpty(),
        )

    /** Switch chips: stash this form's draft, load the target provider's own. */
    fun onTtsPresetChange(preset: TtsProviderPreset) {
        if (preset.id == _ttsPresetId.value) return
        ttsDrafts[_ttsPresetId.value] = _ttsForm.value
        _ttsPresetId.value = preset.id
        _ttsForm.value = ttsDrafts[preset.id]
            ?: ttsDraftFor(preset, _ttsStored.value[preset.id])
        _ttsTestState.value = TtsTestState.Idle
    }

    private fun updateTtsForm(transform: (TtsFormDraft) -> TtsFormDraft) {
        _ttsForm.value = transform(_ttsForm.value)
        _ttsTestState.value = TtsTestState.Idle
    }

    fun onTtsApiChange(kind: TtsApiKind) = updateTtsForm { it.copy(api = kind) }

    fun onTtsBaseUrlChange(value: String) = updateTtsForm { it.copy(baseUrl = value) }

    fun onTtsApiKeyChange(value: String) = updateTtsForm { it.copy(apiKey = value) }

    fun onTtsModelChange(value: String) = updateTtsForm { it.copy(model = value) }

    fun onTtsVoiceEnChange(value: String) = updateTtsForm { it.copy(voiceEn = value) }

    fun onTtsVoiceZhChange(value: String) = updateTtsForm { it.copy(voiceZh = value) }

    fun onTtsResponseFormatChange(value: String) =
        updateTtsForm { it.copy(responseFormat = value) }

    /**
     * Persist the entered config under the selected preset and activate the
     * custom source (the 保存并启用 button). Other presets' stored configs
     * are untouched.
     */
    fun saveTtsConfig() {
        val presetId = _ttsPresetId.value
        val cfg = currentTtsConfig() ?: return
        viewModelScope.launch {
            try {
                settings.setTtsProviderConfig(presetId, cfg)
                _ttsSource.value = TtsSource.CUSTOM
                settings.setTtsSource(TtsSource.CUSTOM)
            } catch (e: Exception) {
                // DataStore failures must never crash the screen (AGENTS.md).
            }
        }
    }

    /**
     * 清除配置: drop only the selected preset's stored config. Disabling the
     * custom source (switching to 有道/系统语音) never calls this — stored
     * configs survive; reverting to Youdao happens only when the cleared
     * preset was the active one.
     */
    fun clearTtsConfig() {
        val presetId = _ttsPresetId.value
        viewModelScope.launch {
            try {
                val wasActive = settings.ttsActivePresetId.first() == presetId
                settings.clearTtsProviderConfig(presetId)
                if (wasActive) {
                    _ttsSource.value = TtsSource.YOUDAO
                    settings.setTtsSource(TtsSource.YOUDAO)
                }
                ttsDrafts.remove(presetId)
                _ttsForm.value = ttsDraftFor(ttsPresetById(presetId), null)
            } catch (e: Exception) {
                // DataStore failures must never crash the screen (AGENTS.md).
            }
        }
    }

    /**
     * Generate and play both test samples with the entered (unsaved) config:
     * English + Chinese so both voices are exercised (alice `testTtsConfig`).
     * Failure messages come from the provider (error.message / HTTP status /
     * Chinese network fallbacks).
     */
    fun testTtsVoice() {
        val cfg = currentTtsConfig() ?: return
        if (_ttsTestState.value is TtsTestState.Testing) return
        _ttsTestState.value = TtsTestState.Testing
        viewModelScope.launch {
            val error = try {
                var played = false
                for (sample in listOf("apple", "苹果，一种很常见的水果")) {
                    val clip = openAiTts.generateClip(sample, cfg)
                    if (ttsChain.playTestClip(clip)) played = true
                }
                if (played) null else "音频已生成，但本机播放失败"
            } catch (e: TtsProviderException) {
                e.message ?: "无法生成试听音频，请检查接口地址、密钥和模型"
            } catch (e: Exception) {
                // Network/JSON failures degrade to a Chinese message.
                "网络请求失败，请检查 URL 与网络"
            }
            _ttsTestState.value =
                if (error == null) TtsTestState.Ok else TtsTestState.Failed(error)
        }
    }

    /** The selected preset's entered config (trimmed), null when incomplete. */
    private fun currentTtsConfig(): TtsProviderConfig? = _ttsForm.value.let {
        TtsProviderConfig(
            api = it.api,
            baseUrl = it.baseUrl.trim(),
            apiKey = it.apiKey.trim(),
            model = it.model.trim(),
            voiceEn = it.voiceEn.trim(),
            voiceZh = it.voiceZh.trim(),
            responseFormat = it.responseFormat.trim().ifEmpty { null },
        )
    }.takeIf { it.isComplete }

    // ------------------------------------------------- OCR 识别 (拍照识词)

    private fun ocrPresetById(id: String): OcrProviderPreset =
        OCR_PROVIDER_PRESETS.firstOrNull { it.id == id } ?: DEFAULT_OCR_PRESET

    /** Seed a preset's draft: its stored config, else preset defaults + blank key. */
    private fun ocrDraftFor(preset: OcrProviderPreset, cfg: OcrProviderConfig?): OcrFormDraft =
        cfg?.let { OcrFormDraft(baseUrl = it.baseUrl, apiKey = it.apiKey, model = it.model) }
            ?: OcrFormDraft(baseUrl = preset.baseUrl, apiKey = "", model = preset.model)

    /** Switch chips: stash this form's draft, load the target provider's own. */
    fun onOcrPresetChange(preset: OcrProviderPreset) {
        if (preset.id == _ocrPresetId.value) return
        ocrDrafts[_ocrPresetId.value] = _ocrForm.value
        _ocrPresetId.value = preset.id
        _ocrForm.value = ocrDrafts[preset.id]
            ?: ocrDraftFor(preset, _ocrStored.value[preset.id])
    }

    fun onOcrBaseUrlChange(value: String) {
        _ocrForm.value = _ocrForm.value.copy(baseUrl = value)
    }

    fun onOcrApiKeyChange(value: String) {
        _ocrForm.value = _ocrForm.value.copy(apiKey = value)
    }

    fun onOcrModelChange(value: String) {
        _ocrForm.value = _ocrForm.value.copy(model = value)
    }

    /** True when every field is non-blank (enables 测试连接/保存). */
    fun isOcrConfigComplete(): Boolean = currentOcrConfig()?.isComplete == true

    /** Run the text-only connection test against the entered fields. */
    fun testOcrConnection() {
        val cfg = currentOcrConfig() ?: return
        if (_ocrTestState.value is OcrTestState.Testing) return
        _ocrTestState.value = OcrTestState.Testing
        viewModelScope.launch {
            val error = try {
                ocrService.testConnection(cfg)
            } catch (e: Exception) {
                // Network/JSON failures degrade to a Chinese message.
                "网络请求失败，请检查 URL 与网络"
            }
            _ocrTestState.value =
                if (error == null) OcrTestState.Ok else OcrTestState.Failed(error)
        }
    }

    /** Persist the entered config under the selected preset (保存并启用). */
    fun saveOcrConfig() {
        val presetId = _ocrPresetId.value
        val cfg = currentOcrConfig() ?: return
        viewModelScope.launch {
            try {
                settings.setOcrProviderConfig(presetId, cfg)
            } catch (e: Exception) {
                // DataStore failures must never crash the screen (AGENTS.md).
            }
        }
    }

    /** 清除配置: drop only the selected preset's stored OCR config. */
    fun clearOcrConfig() {
        val presetId = _ocrPresetId.value
        viewModelScope.launch {
            try {
                settings.clearOcrProviderConfig(presetId)
                ocrDrafts.remove(presetId)
                _ocrForm.value = ocrDraftFor(ocrPresetById(presetId), null)
            } catch (e: Exception) {
                // DataStore failures must never crash the screen (AGENTS.md).
            }
        }
    }

    // ------------------------------------------------- 发音缓存 (清空发音缓存)

    private fun ttsCacheDir(): File = File(getApplication<Application>().cacheDir, "tts")

    /** Recursive scan of `cacheDir/tts` (clips are flat; keep it robust). */
    private fun scanTtsCache(): TtsCacheInfo {
        var files = 0
        var bytes = 0L
        ttsCacheDir().walkTopDown().forEach { f ->
            if (f.isFile) {
                files += 1
                bytes += f.length()
            }
        }
        return TtsCacheInfo(files, bytes)
    }

    /** Recompute the 清空发音缓存 summary (after page returns / test plays). */
    fun refreshTtsCacheInfo() {
        viewModelScope.launch(Dispatchers.IO) { _ttsCacheInfo.value = scanTtsCache() }
    }

    /**
     * Delete every cached pronunciation file (Youdao + custom-provider clips,
     * keyed under cacheDir/tts). Clips re-download on demand afterwards;
     * writers mkdirs the directory again, so deleting the node itself is safe.
     */
    fun clearTtsCache() {
        viewModelScope.launch(Dispatchers.IO) {
            ttsCacheDir().deleteRecursively()
            _ttsCacheInfo.value = scanTtsCache()
            _ttsCacheCleared.value += 1
        }
    }

    private fun currentOcrConfig(): OcrProviderConfig? = _ocrForm.value.let {
        OcrProviderConfig(
            baseUrl = it.baseUrl.trim(),
            apiKey = it.apiKey.trim(),
            model = it.model.trim(),
        )
    }.takeIf { it.isComplete }
}
