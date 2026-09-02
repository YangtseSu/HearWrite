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
import org.yangtse.hearwrite.domain.DEFAULT_INTERVAL_SEC
import org.yangtse.hearwrite.domain.DEFAULT_SPEECH_RATE

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

    /** One-shot snapshot used when a dictation session starts. */
    suspend fun snapshot(): SettingsSnapshot = SettingsSnapshot(
        intervalSec = intervalSec.first(),
        speechRate = speechRate.first(),
        autoNext = autoNext.first(),
        readTranslation = readTranslation.first(),
    )

    private companion object {
        val KEY_DRAFT = stringPreferencesKey("word_input_draft")
        val KEY_INTERVAL_SEC = doublePreferencesKey("interval_sec")
        val KEY_SPEECH_RATE = floatPreferencesKey("speech_rate")
        val KEY_AUTO_NEXT = booleanPreferencesKey("auto_next")
        val KEY_READ_TRANSLATION = booleanPreferencesKey("read_translation")
    }
}

/** Immutable copy of the settings at dictation start. */
data class SettingsSnapshot(
    val intervalSec: Double,
    val speechRate: Float,
    val autoNext: Boolean,
    val readTranslation: Boolean,
)
