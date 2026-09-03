package org.yangtse.hearwrite.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

    /** 提示音 (countdown tick + completion chime): default on. */
    val soundEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_SOUND_ENABLED] ?: true }

    /** App theme: system/light/dark (default system). */
    val theme: Flow<ThemeMode> = dataStore.data.map { prefs ->
        ThemeMode.fromStored(prefs[KEY_THEME])
    }

    /**
     * BYOK OCR provider config (拍照识词) stored as one JSON blob — null when
     * never configured or unparseable (AGENTS.md Persistence). JSON is
     * handled dynamically — this module has no serialization codegen.
     */
    val ocrProviderConfig: Flow<OcrProviderConfig?> = dataStore.data.map { prefs ->
        prefs[KEY_OCR_PROVIDER_CONFIG]?.let(::decodeOcrConfig)
    }

    suspend fun setOcrProviderConfig(cfg: OcrProviderConfig) {
        dataStore.edit { it[KEY_OCR_PROVIDER_CONFIG] = encodeOcrConfig(cfg) }
    }

    /** Custom OpenAI-compatible TTS provider config (自定义音源) — null when unset/unparseable. */
    val ttsProviderConfig: Flow<TtsProviderConfig?> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_PROVIDER_CONFIG]?.let(::decodeTtsProviderConfig)
    }

    /** Persist (or clear, with null) the custom TTS provider config. */
    suspend fun setTtsProviderConfig(cfg: TtsProviderConfig?) {
        dataStore.edit {
            if (cfg == null) {
                it.remove(KEY_TTS_PROVIDER_CONFIG)
            } else {
                it[KEY_TTS_PROVIDER_CONFIG] = encodeTtsProviderConfig(cfg)
            }
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

    /** `{"baseUrl":…,"apiKey":…,"model":…}` — same shape as alice's stored config. */
    private fun encodeOcrConfig(cfg: OcrProviderConfig): String = buildJsonObject {
        put("baseUrl", cfg.baseUrl)
        put("apiKey", cfg.apiKey)
        put("model", cfg.model)
    }.toString()

    private fun decodeOcrConfig(raw: String): OcrProviderConfig? = try {
        val obj = ocrJson.parseToJsonElement(raw).jsonObject
        OcrProviderConfig(
            baseUrl = obj["baseUrl"]?.jsonPrimitive?.contentOrNull?.trim() ?: "",
            apiKey = obj["apiKey"]?.jsonPrimitive?.contentOrNull?.trim() ?: "",
            model = obj["model"]?.jsonPrimitive?.contentOrNull?.trim() ?: "",
        )
    } catch (e: Exception) {
        null
    }

    private companion object {
        val KEY_DRAFT = stringPreferencesKey("word_input_draft")
        val KEY_INTERVAL_SEC = doublePreferencesKey("interval_sec")
        val KEY_SPEECH_RATE = floatPreferencesKey("speech_rate")
        val KEY_AUTO_NEXT = booleanPreferencesKey("auto_next")
        val KEY_READ_TRANSLATION = booleanPreferencesKey("read_translation")
        val KEY_TTS_SOURCE = stringPreferencesKey("tts_source")
        val KEY_SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_OCR_PROVIDER_CONFIG = stringPreferencesKey("ocr_provider_config")
        val KEY_TTS_PROVIDER_CONFIG = stringPreferencesKey("tts_provider_config")

        val ocrJson = Json { ignoreUnknownKeys = true }
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
