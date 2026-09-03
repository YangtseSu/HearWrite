package org.yangtse.hearwrite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.data.DEFAULT_OCR_PRESET
import org.yangtse.hearwrite.data.DEFAULT_TTS_PRESET
import org.yangtse.hearwrite.data.OcrProviderConfig
import org.yangtse.hearwrite.data.OcrProviderPreset
import org.yangtse.hearwrite.data.TTS_PROVIDER_PRESETS
import org.yangtse.hearwrite.data.TtsApiKind
import org.yangtse.hearwrite.data.TtsProviderConfig
import org.yangtse.hearwrite.data.TtsProviderException
import org.yangtse.hearwrite.data.TtsProviderPreset
import org.yangtse.hearwrite.domain.MAX_SPEECH_RATE
import org.yangtse.hearwrite.domain.MIN_SPEECH_RATE
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

/**
 * Settings screen state: 语速 and 朗读释义 persist through the DataStore
 * settings repository; the rate applies live to the shared system speaker.
 * TTS 来源 (youdao/system) and 提示音 toggle ride the same repository and take
 * effect from the next dictation session. The OCR 识别 section edits the BYOK
 * vision-provider config (default preset 智谱 GLM-4V-Flash), with a 测试连接
 * button against the entered fields; 保存 writes it to DataStore. Interval/
 * auto-next live on the dictation screen; Phase 10 consolidates the screen.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = (application as HearWriteApplication).settingsRepository
    private val speaker = (application as HearWriteApplication).systemSpeaker
    private val ocrService = (application as HearWriteApplication).ocrService
    private val ttsChain = (application as HearWriteApplication).ttsChain
    private val openAiTts = (application as HearWriteApplication).openAiCompatibleTts

    private val _speechRate = MutableStateFlow(MIN_SPEECH_RATE)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _readTranslation = MutableStateFlow(false)
    val readTranslation: StateFlow<Boolean> = _readTranslation.asStateFlow()

    private val _ttsSource = MutableStateFlow(TtsSource.YOUDAO)
    val ttsSource: StateFlow<TtsSource> = _ttsSource.asStateFlow()

    private val _soundEnabled = MutableStateFlow(true)
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    // ---- OCR 识别 (拍照识词) BYOK config fields ----------------------------

    private val _ocrBaseUrl = MutableStateFlow("")
    val ocrBaseUrl: StateFlow<String> = _ocrBaseUrl.asStateFlow()

    private val _ocrApiKey = MutableStateFlow("")
    val ocrApiKey: StateFlow<String> = _ocrApiKey.asStateFlow()

    private val _ocrModel = MutableStateFlow("")
    val ocrModel: StateFlow<String> = _ocrModel.asStateFlow()

    private val _ocrTestState = MutableStateFlow<OcrTestState>(OcrTestState.Idle)
    val ocrTestState: StateFlow<OcrTestState> = _ocrTestState.asStateFlow()

    // ---- 自定义音源 (OpenAI-compatible TTS) form fields --------------------

    private val _ttsPresetId = MutableStateFlow(DEFAULT_TTS_PRESET.id)
    val ttsPresetId: StateFlow<String> = _ttsPresetId.asStateFlow()

    private val _ttsApi = MutableStateFlow(DEFAULT_TTS_PRESET.api)
    val ttsApi: StateFlow<TtsApiKind> = _ttsApi.asStateFlow()

    private val _ttsBaseUrl = MutableStateFlow(DEFAULT_TTS_PRESET.baseUrl)
    val ttsBaseUrl: StateFlow<String> = _ttsBaseUrl.asStateFlow()

    private val _ttsApiKey = MutableStateFlow("")
    val ttsApiKey: StateFlow<String> = _ttsApiKey.asStateFlow()

    private val _ttsModel = MutableStateFlow(DEFAULT_TTS_PRESET.model)
    val ttsModel: StateFlow<String> = _ttsModel.asStateFlow()

    private val _ttsVoiceEn = MutableStateFlow(DEFAULT_TTS_PRESET.voiceEn)
    val ttsVoiceEn: StateFlow<String> = _ttsVoiceEn.asStateFlow()

    private val _ttsVoiceZh = MutableStateFlow(DEFAULT_TTS_PRESET.voiceZh)
    val ttsVoiceZh: StateFlow<String> = _ttsVoiceZh.asStateFlow()

    private val _ttsResponseFormat = MutableStateFlow(DEFAULT_TTS_PRESET.responseFormat.orEmpty())
    val ttsResponseFormat: StateFlow<String> = _ttsResponseFormat.asStateFlow()

    /** A saved config exists (enables 清除配置; also the "active" status line). */
    private val _ttsConfigSaved = MutableStateFlow(false)
    val ttsConfigSaved: StateFlow<Boolean> = _ttsConfigSaved.asStateFlow()

    private val _ttsTestState = MutableStateFlow<TtsTestState>(TtsTestState.Idle)
    val ttsTestState: StateFlow<TtsTestState> = _ttsTestState.asStateFlow()

    init {
        viewModelScope.launch { _speechRate.value = settings.speechRate.first() }
        viewModelScope.launch { _readTranslation.value = settings.readTranslation.first() }
        viewModelScope.launch { _ttsSource.value = settings.ttsSource.first() }
        viewModelScope.launch { _soundEnabled.value = settings.soundEnabled.first() }
        // Prefill with the stored config; the default preset 智谱 GLM-4V-Flash
        // when none is saved yet (the apiKey stays blank until the user
        // pastes their own — BYOK only).
        viewModelScope.launch {
            val cfg = settings.ocrProviderConfig.first()
            if (cfg == null) {
                _ocrBaseUrl.value = DEFAULT_OCR_PRESET.baseUrl
                _ocrModel.value = DEFAULT_OCR_PRESET.model
            } else {
                _ocrBaseUrl.value = cfg.baseUrl
                _ocrApiKey.value = cfg.apiKey
                _ocrModel.value = cfg.model
            }
        }
        // The custom-TTS form mirrors the saved config (preset matched by
        // baseUrl+model like alice); a fresh form defaults to the MiMo
        // preset with a blank key.
        viewModelScope.launch {
            val cfg = settings.ttsProviderConfig.first()
            if (cfg != null) {
                _ttsConfigSaved.value = true
                _ttsPresetId.value = TTS_PROVIDER_PRESETS.firstOrNull {
                    it.id != "custom" && it.baseUrl == cfg.baseUrl && it.model == cfg.model
                }?.id ?: "custom"
                _ttsApi.value = cfg.api
                _ttsBaseUrl.value = cfg.baseUrl
                _ttsApiKey.value = cfg.apiKey
                _ttsModel.value = cfg.model
                _ttsVoiceEn.value = cfg.voiceEn
                _ttsVoiceZh.value = cfg.voiceZh
                _ttsResponseFormat.value = cfg.responseFormat.orEmpty()
            } else {
                applyTtsPreset(DEFAULT_TTS_PRESET)
            }
        }
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

    // --------------------------------- 自定义音源 (OpenAI-compatible TTS)

    /** Fill the form from a preset; the key is never touched (alice behavior). */
    fun onTtsPresetChange(preset: TtsProviderPreset) {
        _ttsPresetId.value = preset.id
        applyTtsPreset(preset)
        _ttsTestState.value = TtsTestState.Idle
    }

    private fun applyTtsPreset(preset: TtsProviderPreset) {
        _ttsApi.value = preset.api
        _ttsBaseUrl.value = preset.baseUrl
        _ttsModel.value = preset.model
        _ttsVoiceEn.value = preset.voiceEn
        _ttsVoiceZh.value = preset.voiceZh
        _ttsResponseFormat.value = preset.responseFormat.orEmpty()
    }

    fun onTtsApiChange(kind: TtsApiKind) {
        _ttsApi.value = kind
        _ttsTestState.value = TtsTestState.Idle
    }

    fun onTtsBaseUrlChange(value: String) {
        _ttsBaseUrl.value = value
        _ttsTestState.value = TtsTestState.Idle
    }

    fun onTtsApiKeyChange(value: String) {
        _ttsApiKey.value = value
        _ttsTestState.value = TtsTestState.Idle
    }

    fun onTtsModelChange(value: String) {
        _ttsModel.value = value
        _ttsTestState.value = TtsTestState.Idle
    }

    fun onTtsVoiceEnChange(value: String) {
        _ttsVoiceEn.value = value
        _ttsTestState.value = TtsTestState.Idle
    }

    fun onTtsVoiceZhChange(value: String) {
        _ttsVoiceZh.value = value
        _ttsTestState.value = TtsTestState.Idle
    }

    fun onTtsResponseFormatChange(value: String) {
        _ttsResponseFormat.value = value
        _ttsTestState.value = TtsTestState.Idle
    }

    /**
     * Persist the entered provider config and activate the custom source
     * (the 保存并启用 button; alice `handleSave` also switches the source).
     */
    fun saveTtsConfig() {
        val cfg = currentTtsConfig() ?: return
        viewModelScope.launch {
            try {
                settings.setTtsProviderConfig(cfg)
                _ttsConfigSaved.value = true
                _ttsSource.value = TtsSource.CUSTOM
                settings.setTtsSource(TtsSource.CUSTOM)
            } catch (e: Exception) {
                // DataStore failures must never crash the screen (AGENTS.md).
            }
        }
    }

    /** Clear the provider config and revert to the Youdao source. */
    fun clearTtsConfig() {
        viewModelScope.launch {
            try {
                settings.setTtsProviderConfig(null)
                _ttsConfigSaved.value = false
                _ttsSource.value = TtsSource.YOUDAO
                settings.setTtsSource(TtsSource.YOUDAO)
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

    /** The entered config (trimmed), or null when any required field is blank. */
    private fun currentTtsConfig(): TtsProviderConfig? = TtsProviderConfig(
        api = _ttsApi.value,
        baseUrl = _ttsBaseUrl.value.trim(),
        apiKey = _ttsApiKey.value.trim(),
        model = _ttsModel.value.trim(),
        voiceEn = _ttsVoiceEn.value.trim(),
        voiceZh = _ttsVoiceZh.value.trim(),
        responseFormat = _ttsResponseFormat.value.trim().ifEmpty { null },
    ).takeIf { it.isComplete }

    // ------------------------------------------------- OCR 识别 (拍照识词)

    fun onOcrBaseUrlChange(value: String) {
        _ocrBaseUrl.value = value
    }

    fun onOcrApiKeyChange(value: String) {
        _ocrApiKey.value = value
    }

    fun onOcrModelChange(value: String) {
        _ocrModel.value = value
    }

    /** Fill baseUrl + model from a preset (custom keeps whatever is typed). */
    fun onOcrPresetChange(preset: OcrProviderPreset) {
        if (preset.id == "custom") return
        _ocrBaseUrl.value = preset.baseUrl
        _ocrModel.value = preset.model
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

    /** Persist the entered config (trimmed) as the OCR provider. */
    fun saveOcrConfig() {
        val cfg = currentOcrConfig() ?: return
        viewModelScope.launch {
            try {
                settings.setOcrProviderConfig(cfg)
            } catch (e: Exception) {
                // DataStore failures must never crash the screen (AGENTS.md).
            }
        }
    }

    private fun currentOcrConfig(): OcrProviderConfig? =
        OcrProviderConfig(
            baseUrl = _ocrBaseUrl.value.trim(),
            apiKey = _ocrApiKey.value.trim(),
            model = _ocrModel.value.trim(),
        ).takeIf { it.isComplete }
}
