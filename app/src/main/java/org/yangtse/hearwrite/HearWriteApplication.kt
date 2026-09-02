package org.yangtse.hearwrite

import android.app.Application
import androidx.room.Room
import org.yangtse.hearwrite.data.BuiltinLibraryRepository
import org.yangtse.hearwrite.data.CompoundRepository
import org.yangtse.hearwrite.data.DictationSessionStore
import org.yangtse.hearwrite.data.DictionaryRepository
import org.yangtse.hearwrite.data.FavoritesRepository
import org.yangtse.hearwrite.data.HearWriteDatabase
import org.yangtse.hearwrite.data.HistoryRepository
import org.yangtse.hearwrite.data.SettingsRepository
import org.yangtse.hearwrite.data.SystemSpeaker
import org.yangtse.hearwrite.data.WrongWordsRepository

/**
 * Application-scoped singleton container (manual DI per AGENTS.md — no
 * framework). Everything is lazy: asset scanning, TTS init, Room and the
 * ECDICT dictionary parse never run on the startup path.
 */
class HearWriteApplication : Application() {
    val libraryRepository: BuiltinLibraryRepository by lazy { BuiltinLibraryRepository(assets) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    /** System TTS engine; created lazily on the first utterance. */
    val systemSpeaker: SystemSpeaker by lazy { SystemSpeaker(this) }

    /** 组词 candidate tables (compounds.json); parsed lazily on first lookup. */
    val compoundRepository: CompoundRepository by lazy { CompoundRepository(assets) }

    /** Word-list handoff for starting a dictation session. */
    val dictationSession: DictationSessionStore by lazy { DictationSessionStore() }

    // ---- Room persistence (wrong words / history / favorites) -------------

    val database: HearWriteDatabase by lazy {
        Room.databaseBuilder(this, HearWriteDatabase::class.java, "hearwrite.db").build()
    }

    /** The global 错词本; sessions seed from it and marks persist immediately. */
    val wrongWordsRepository: WrongWordsRepository by lazy {
        WrongWordsRepository(database.wrongWordsDao())
    }

    /** User-pasted list history (cap 50), with favorites orphan pruning. */
    val historyRepository: HistoryRepository by lazy {
        HistoryRepository(database.historyDao(), database.favoritesDao())
    }

    /** Favorite entry ids (`default_*` or history ids). */
    val favoritesRepository: FavoritesRepository by lazy {
        FavoritesRepository(database.favoritesDao())
    }

    /** Offline ECDICT pos/meaning; parsed lazily on the first lookup. */
    val dictionaryRepository: DictionaryRepository by lazy { DictionaryRepository(assets) }
}
