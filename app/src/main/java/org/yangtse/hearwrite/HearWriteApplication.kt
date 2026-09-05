package org.yangtse.hearwrite

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.yangtse.hearwrite.data.BuiltinLibraryRepository
import org.yangtse.hearwrite.data.CompoundRepository
import org.yangtse.hearwrite.data.DictationSessionStore
import org.yangtse.hearwrite.data.DictionaryRepository
import org.yangtse.hearwrite.data.EdgeTts
import org.yangtse.hearwrite.data.FavoritesRepository
import org.yangtse.hearwrite.data.HearWriteDatabase
import org.yangtse.hearwrite.data.HistoryRepository
import org.yangtse.hearwrite.data.OcrService
import org.yangtse.hearwrite.data.OpenAiCompatibleTts
import org.yangtse.hearwrite.data.SettingsRepository
import org.yangtse.hearwrite.data.SoundEffects
import org.yangtse.hearwrite.data.SystemSpeaker
import org.yangtse.hearwrite.data.TtsChainSpeaker
import org.yangtse.hearwrite.data.WrongWordsRepository
import org.yangtse.hearwrite.data.YoudaoTts

/**
 * Application-scoped singleton container (manual DI per AGENTS.md — no
 * framework). Everything is lazy: asset scanning, TTS init, Room and the
 * ECDICT dictionary parse never run on the startup path.
 */
class HearWriteApplication : Application() {
    private val _pendingDraftImport = MutableStateFlow<String?>(null)

    /**
     * One-shot draft import for Home (the 词库 preview's 载入草稿): the preview
     * sets this before popping back to Home; HomeScreen consumes and clears it
     * (applyEntry + toast). Process-scoped, so a stale import can never surface
     * on a later cold start.
     */
    val pendingDraftImport: StateFlow<String?> = _pendingDraftImport.asStateFlow()

    fun requestDraftImport(linesText: String) {
        _pendingDraftImport.value = linesText
    }

    fun consumeDraftImport() {
        _pendingDraftImport.value = null
    }

    val libraryRepository: BuiltinLibraryRepository by lazy { BuiltinLibraryRepository(assets) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    /** System TTS engine; created lazily on the first utterance. */
    val systemSpeaker: SystemSpeaker by lazy { SystemSpeaker(this) }

    /**
     * Youdao dict-voice downloads (disk cache + single flight); the phrase
     * pass never uses it — 组词 always speaks via the system TTS link.
     */
    val youdaoTts: YoudaoTts by lazy { YoudaoTts(this) }

    /**
     * Optional OpenAI-compatible TTS (自定义音源): config + rate follow the
     * DataStore settings; downloads are disk-cached with a per-clip single
     * flight. Never touched on the startup path.
     */
    val openAiCompatibleTts: OpenAiCompatibleTts by lazy {
        OpenAiCompatibleTts(this, settingsRepository)
    }

    /**
     * Microsoft Edge Read-Aloud clips (微软 Edge source): keyless neural
     * voices; disk-cached with a per-clip single flight and the persisted
     * 语速 baked into the clip hash. Never touched on the startup path.
     */
    val edgeTts: EdgeTts by lazy { EdgeTts(this, settingsRepository) }

    /**
     * The word-pass voice chain: the persisted source's own clips (Youdao /
     * Edge / custom provider) with a bounded cold-start fetch, system TTS as
     * the sole fallback (AGENTS.md "TTS priority chain").
     */
    val ttsChain: TtsChainSpeaker by lazy {
        TtsChainSpeaker(youdaoTts, openAiCompatibleTts, edgeTts, systemSpeaker)
    }

    /** tick/chime UI sounds (提示音 setting). */
    val soundEffects: SoundEffects by lazy { SoundEffects(this) }

    /** OpenAI-compatible vision OCR (拍照识词) — BYOK config from settings. */
    val ocrService: OcrService by lazy { OcrService(this, settingsRepository) }

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
