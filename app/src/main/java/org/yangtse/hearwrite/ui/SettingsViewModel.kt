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
import org.yangtse.hearwrite.data.OcrProviderConfig
import org.yangtse.hearwrite.data.OcrProviderPreset
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
