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
import org.yangtse.hearwrite.domain.MAX_SPEECH_RATE
import org.yangtse.hearwrite.domain.MIN_SPEECH_RATE
import org.yangtse.hearwrite.domain.TtsSource
import kotlin.math.roundToInt

/**
 * Settings screen state: 语速 and 朗读释义 persist through the DataStore
 * settings repository; the rate applies live to the shared system speaker.
 * TTS 来源 (youdao/system) and 提示音 toggle ride the same repository and take
 * effect from the next dictation session. Interval/auto-next live on the
 * dictation screen; Phase 10 consolidates the full settings screen.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = (application as HearWriteApplication).settingsRepository
    private val speaker = (application as HearWriteApplication).systemSpeaker

    private val _speechRate = MutableStateFlow(MIN_SPEECH_RATE)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _readTranslation = MutableStateFlow(false)
    val readTranslation: StateFlow<Boolean> = _readTranslation.asStateFlow()

    private val _ttsSource = MutableStateFlow(TtsSource.YOUDAO)
    val ttsSource: StateFlow<TtsSource> = _ttsSource.asStateFlow()

    private val _soundEnabled = MutableStateFlow(true)
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    init {
        viewModelScope.launch { _speechRate.value = settings.speechRate.first() }
        viewModelScope.launch { _readTranslation.value = settings.readTranslation.first() }
        viewModelScope.launch { _ttsSource.value = settings.ttsSource.first() }
        viewModelScope.launch { _soundEnabled.value = settings.soundEnabled.first() }
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
}
