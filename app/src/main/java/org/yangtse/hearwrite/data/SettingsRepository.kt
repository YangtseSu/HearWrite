package org.yangtse.hearwrite.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.yangtse.hearwrite.domain.DEFAULT_INTERVAL_SEC
import org.yangtse.hearwrite.domain.DEFAULT_SPEECH_RATE
import org.yangtse.hearwrite.domain.ThemeMode
import org.yangtse.hearwrite.domain.TtsSource

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * DataStore-backed playback settings + the word-input draft (AGENTS.md
 * Persistence). One preferences file, typed accessors, defaults from the
 * domain constants. Built-in library content is never persisted here.
 */
class SettingsRepository(private val context: Context) {

    private val dataStore get() = context.applicationContext.settingsDataStore

    /** Word-input draft on Home; persisted debounced (500 ms) and flushed on dispose. */
    val draft: Flow<String> = dataStore.data.map { it[KEY_DRAFT] ?: "" }

    /** 听写间隔秒数: 1–10 s, step 0.5, default 7. */
    val intervalSec: Flow<Double> =
        dataStore.data.map { it[KEY_INTERVAL_SEC] ?: DEFAULT_INTERVAL_SEC }

    /** 语速: 0.5–1.5, default 0.9. */
    val speechRate: Flow<Float> =
        dataStore.data.map { it[KEY_SPEECH_RATE] ?: DEFAULT_SPEECH_RATE }

    /** 自动播报下一词: default on. */
    val autoNext: Flow<Boolean> = dataStore.data.map { it[KEY_AUTO_NEXT] ?: true }

    /** 朗读释义 (English gloss after the word): default off. */
    val readTranslation: Flow<Boolean> =
        dataStore.data.map { it[KEY_READ_TRANSLATION] ?: false }

    /** 发音来源: Youdao dict voice (default) or the system TTS. */
    val ttsSource: Flow<TtsSource> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_SOURCE]?.let { stored ->
            TtsSource.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) }
        } ?: TtsSource.YOUDAO
    }

    /** Edge 音色 (微软 Edge source): default voice shortName; blank = built-in default. */
    val edgeVoiceZh: Flow<String> =
        dataStore.data.map { it[KEY_EDGE_VOICE_ZH] ?: "" }
    /** Edge dedicated English voice shortName; blank = its region default (Aria/Sonia). */
    val edgeVoiceEn: Flow<String> =
        dataStore.data.map { it[KEY_EDGE_VOICE_EN] ?: "" }
    /** 英文使用默认音色: default on (no dedicated English voice). */
    val edgeUseDefaultEn: Flow<Boolean> =
        dataStore.data.map { it[KEY_EDGE_USE_DEFAULT_EN] ?: true }

    /** 提示音 (countdown tick + completion chime): default on. */
    val soundEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_SOUND_ENABLED] ?: true }

    /** App theme: system/light/dark (default system). */
    val theme: Flow<ThemeMode> = dataStore.data.map { prefs ->
        ThemeMode.fromStored(prefs[KEY_THEME])
    }

    // ---- Per-provider BYOK configs ----------------------------------------
    //
    // TTS and OCR provider configs are stored **per preset id** — each
    // 服务商 chip owns its full config (URL, key, model, voices), so
    // switching or disabling a provider never carries another provider's
    // key around and never loses a stored config. The active preset id only
    // changes on 保存并启用; it drives which config the runtime uses.
    //
    // Releases before per-provider storage kept a single JSON blob per
    // feature; those blobs are still read (matched to a preset by
    // baseUrl+model, else the 自定义 slot) and removed on the first write.

    private val ocrStoreFlow: Flow<Pair<Map<String, OcrProviderConfig>, String>> =
        dataStore.data.map(::ocrStore)

    /** Stored OCR config per preset id (拍照识词); empty when never configured. */
    val ocrProviderConfigs: Flow<Map<String, OcrProviderConfig>> =
        ocrStoreFlow.map { it.first }

    /** Preset id of the active OCR config ("" when none saved). */
    val ocrActivePresetId: Flow<String> = ocrStoreFlow.map { it.second }

    /** The active OCR provider config, or null when unset/incomplete. */
    val ocrProviderConfig: Flow<OcrProviderConfig?> =
        ocrStoreFlow.map { (map, id) -> map[id]?.takeIf { it.isComplete } }

    /** Store [cfg] under [presetId] and make it the active OCR provider. */
    suspend fun setOcrProviderConfig(presetId: String, cfg: OcrProviderConfig) {
        dataStore.edit {
            it.remove(KEY_OCR_PROVIDER_CONFIG) // legacy single blob
            val map = it[KEY_OCR_PROVIDER_CONFIGS]?.let(::decodeOcrConfigMap).orEmpty()
            it[KEY_OCR_PROVIDER_CONFIGS] = encodeOcrConfigMap(map + (presetId to cfg))
            it[KEY_OCR_ACTIVE_PRESET] = presetId
        }
    }

    /** Drop one preset's stored OCR config (explicit 清除配置; others survive). */
    suspend fun clearOcrProviderConfig(presetId: String) {
        dataStore.edit {
            it.remove(KEY_OCR_PROVIDER_CONFIG)
            val map = it[KEY_OCR_PROVIDER_CONFIGS]?.let(::decodeOcrConfigMap).orEmpty() - presetId
            if (map.isEmpty()) it.remove(KEY_OCR_PROVIDER_CONFIGS) else {
                it[KEY_OCR_PROVIDER_CONFIGS] = encodeOcrConfigMap(map)
            }
            if (it[KEY_OCR_ACTIVE_PRESET] == presetId) it.remove(KEY_OCR_ACTIVE_PRESET)
        }
    }

    private val ttsStoreFlow: Flow<Pair<Map<String, TtsProviderConfig>, String>> =
        dataStore.data.map(::ttsStore)

    /** Stored custom-TTS config per preset id (自定义音源); empty when never configured. */
    val ttsProviderConfigs: Flow<Map<String, TtsProviderConfig>> =
        ttsStoreFlow.map { it.first }

    /** Preset id of the active custom-TTS config ("" when none saved). */
    val ttsActivePresetId: Flow<String> = ttsStoreFlow.map { it.second }

    /** The active custom-TTS provider config, or null when unset/incomplete. */
    val ttsProviderConfig: Flow<TtsProviderConfig?> =
        ttsStoreFlow.map { (map, id) -> map[id]?.takeIf { it.isComplete } }

    /** Store [cfg] under [presetId] and make it the active custom-TTS provider. */
    suspend fun setTtsProviderConfig(presetId: String, cfg: TtsProviderConfig) {
        dataStore.edit {
            it.remove(KEY_TTS_PROVIDER_CONFIG) // legacy single blob
            val map = it[KEY_TTS_PROVIDER_CONFIGS]?.let(::decodeTtsConfigMap).orEmpty()
            it[KEY_TTS_PROVIDER_CONFIGS] = encodeTtsConfigMap(map + (presetId to cfg))
            it[KEY_TTS_ACTIVE_PRESET] = presetId
        }
    }

    /** Drop one preset's stored TTS config (explicit 清除配置; others survive). */
    suspend fun clearTtsProviderConfig(presetId: String) {
        dataStore.edit {
            it.remove(KEY_TTS_PROVIDER_CONFIG)
            val map = it[KEY_TTS_PROVIDER_CONFIGS]?.let(::decodeTtsConfigMap).orEmpty() - presetId
            if (map.isEmpty()) it.remove(KEY_TTS_PROVIDER_CONFIGS) else {
                it[KEY_TTS_PROVIDER_CONFIGS] = encodeTtsConfigMap(map)
            }
            if (it[KEY_TTS_ACTIVE_PRESET] == presetId) it.remove(KEY_TTS_ACTIVE_PRESET)
        }
    }

    suspend fun setDraft(value: String) {
        dataStore.edit { it[KEY_DRAFT] = value }
    }

    suspend fun setIntervalSec(sec: Double) {
        dataStore.edit { it[KEY_INTERVAL_SEC] = sec }
    }

    suspend fun setSpeechRate(rate: Float) {
        dataStore.edit { it[KEY_SPEECH_RATE] = rate }
    }

    suspend fun setAutoNext(on: Boolean) {
        dataStore.edit { it[KEY_AUTO_NEXT] = on }
    }

    suspend fun setReadTranslation(on: Boolean) {
        dataStore.edit { it[KEY_READ_TRANSLATION] = on }
    }

    suspend fun setTheme(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME] = mode.name.lowercase() }
    }

    suspend fun setTtsSource(source: TtsSource) {
        dataStore.edit { it[KEY_TTS_SOURCE] = source.name.lowercase() }
    }

    suspend fun setEdgeVoiceZh(shortName: String) {
        dataStore.edit { it[KEY_EDGE_VOICE_ZH] = shortName }
    }

    suspend fun setEdgeVoiceEn(shortName: String) {
        dataStore.edit { it[KEY_EDGE_VOICE_EN] = shortName }
    }

    suspend fun setEdgeUseDefaultEn(useDefault: Boolean) {
        dataStore.edit { it[KEY_EDGE_USE_DEFAULT_EN] = useDefault }
    }

    suspend fun setSoundEnabled(on: Boolean) {
        dataStore.edit { it[KEY_SOUND_ENABLED] = on }
    }

    /** One-shot snapshot used when a dictation session starts. */
    suspend fun snapshot(): SettingsSnapshot = SettingsSnapshot(
        intervalSec = intervalSec.first(),
        speechRate = speechRate.first(),
        autoNext = autoNext.first(),
        readTranslation = readTranslation.first(),
        ttsSource = ttsSource.first(),
        soundEnabled = soundEnabled.first(),
    )

    /** The DataStore defaults; used when the persisted read itself fails. */
    fun defaultSnapshot(): SettingsSnapshot = SettingsSnapshot(
        intervalSec = DEFAULT_INTERVAL_SEC,
        speechRate = DEFAULT_SPEECH_RATE,
        autoNext = true,
        readTranslation = false,
        ttsSource = TtsSource.YOUDAO,
        soundEnabled = true,
    )

    /** Per-preset map + active preset id, parsed from one DataStore snapshot. */
    private fun ocrStore(
        prefs: Preferences,
    ): Pair<Map<String, OcrProviderConfig>, String> {
        val legacy = prefs[KEY_OCR_PROVIDER_CONFIG]?.let(::decodeOcrConfig)
        val map = prefs[KEY_OCR_PROVIDER_CONFIGS]?.let(::decodeOcrConfigMap)
            ?: legacy?.let { mapOf(legacyOcrPresetId(it) to it) }
            ?: emptyMap()
        val activeId = prefs[KEY_OCR_ACTIVE_PRESET]?.takeIf { it.isNotBlank() }
            ?: legacy?.let(::legacyOcrPresetId).orEmpty()
        return map to activeId
    }

    private fun ttsStore(
        prefs: Preferences,
    ): Pair<Map<String, TtsProviderConfig>, String> {
        val legacy = prefs[KEY_TTS_PROVIDER_CONFIG]?.let(::decodeTtsProviderConfig)
        val map = prefs[KEY_TTS_PROVIDER_CONFIGS]?.let(::decodeTtsConfigMap)
            ?: legacy?.let { mapOf(legacyTtsPresetId(it) to it) }
            ?: emptyMap()
        val activeId = prefs[KEY_TTS_ACTIVE_PRESET]?.takeIf { it.isNotBlank() }
            ?: legacy?.let(::legacyTtsPresetId).orEmpty()
        return map to activeId
    }

    private companion object {
        val KEY_DRAFT = stringPreferencesKey("word_input_draft")
        val KEY_INTERVAL_SEC = doublePreferencesKey("interval_sec")
        val KEY_SPEECH_RATE = floatPreferencesKey("speech_rate")
        val KEY_AUTO_NEXT = booleanPreferencesKey("auto_next")
        val KEY_READ_TRANSLATION = booleanPreferencesKey("read_translation")
        val KEY_TTS_SOURCE = stringPreferencesKey("tts_source")
        val KEY_EDGE_VOICE_ZH = stringPreferencesKey("edge_voice_zh")
        val KEY_EDGE_VOICE_EN = stringPreferencesKey("edge_voice_en")
        val KEY_EDGE_USE_DEFAULT_EN = booleanPreferencesKey("edge_use_default_en")
        val KEY_SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_OCR_PROVIDER_CONFIG = stringPreferencesKey("ocr_provider_config")
        val KEY_TTS_PROVIDER_CONFIG = stringPreferencesKey("tts_provider_config")
        val KEY_OCR_PROVIDER_CONFIGS = stringPreferencesKey("ocr_provider_configs")
        val KEY_TTS_PROVIDER_CONFIGS = stringPreferencesKey("tts_provider_configs")
        val KEY_OCR_ACTIVE_PRESET = stringPreferencesKey("ocr_active_preset")
        val KEY_TTS_ACTIVE_PRESET = stringPreferencesKey("tts_active_preset")
    }
}

/** Immutable copy of the settings at dictation start. */
data class SettingsSnapshot(
    val intervalSec: Double,
    val speechRate: Float,
    val autoNext: Boolean,
    val readTranslation: Boolean,
    val ttsSource: TtsSource,
    val soundEnabled: Boolean,
)
