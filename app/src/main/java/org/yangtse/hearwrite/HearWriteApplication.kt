package org.yangtse.hearwrite

import android.app.Application
import android.util.Log
import androidx.room.Room
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import org.yangtse.hearwrite.data.BuiltinLibraryRepository
import org.yangtse.hearwrite.data.CompoundRepository
import org.yangtse.hearwrite.data.DictationSessionStore
import org.yangtse.hearwrite.data.EdgeTts
import org.yangtse.hearwrite.data.FavoritesRepository
import org.yangtse.hearwrite.data.HearWriteDatabase
import org.yangtse.hearwrite.data.HistoryRepository
import org.yangtse.hearwrite.data.KeystoreCipher
import org.yangtse.hearwrite.data.LexiconRepository
import org.yangtse.hearwrite.data.LibraryList
import org.yangtse.hearwrite.data.LibrarySelectionStore
import org.yangtse.hearwrite.data.OcrService
import org.yangtse.hearwrite.data.OpenAiCompatibleTts
import org.yangtse.hearwrite.data.SettingsRepository
import org.yangtse.hearwrite.data.SessionRepository
import org.yangtse.hearwrite.data.SoundEffects
import org.yangtse.hearwrite.data.SystemSpeaker
import org.yangtse.hearwrite.data.TtsChainSpeaker
import org.yangtse.hearwrite.data.WrongWordLineResolver
import org.yangtse.hearwrite.data.WrongWordsRepository
import org.yangtse.hearwrite.data.YoudaoTts
import org.yangtse.hearwrite.domain.ResolvedWord
import org.yangtse.hearwrite.domain.WordRow
import org.yangtse.hearwrite.domain.parseWordRows

/**
 * Application-scoped singleton container (manual DI per AGENTS.md — no
 * framework). Everything is lazy: asset scanning, TTS init, Room and the
 * ECDICT dictionary parse never run on the startup path.
 */
class HearWriteApplication : Application() {
    /**
     * Process-scoped coroutine scope for fire-and-forget writes that must
     * outlive a ViewModel — the draft flush on screen dispose (the
     * ViewModel is already cleared by the time Compose disposes, so
     * viewModelScope would silently drop the last keystrokes). Never used
     * for playback or UI state.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    /**
     * AES-256-GCM secret sealing for the persisted BYOK provider keys
     * (Android Keystore; key never leaves the chip). First touch generates
     * the keystore entry — off the startup path, when a config is read or
     * saved.
     */
    val secretCipher: KeystoreCipher by lazy { KeystoreCipher() }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(this, secretCipher)
    }

    /** System TTS engine; created lazily on the first utterance. Follows the
     *  persisted 系统语音音色 keys live (like the Edge voices). */
    val systemSpeaker: SystemSpeaker by lazy { SystemSpeaker(this, settingsRepository) }

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

    /** Cross-screen 多选词表 selection for 抽词听写 (Roadmap #9). */
    val librarySelection: LibrarySelectionStore by lazy { LibrarySelectionStore() }

    // ---- Room persistence (wrong words / history / favorites) -------------

    val database: HearWriteDatabase by lazy {
        HearWriteDatabase.create(this)
    }

    /** The global 错词本; sessions seed from it and marks persist immediately. */
    val wrongWordsRepository: WrongWordsRepository by lazy {
        WrongWordsRepository(database.wrongWordsDao())
    }

    /** User-pasted list history (cap 50), with favorites orphan pruning. */
    val historyRepository: HistoryRepository by lazy {
        HistoryRepository(database.historyDao(), database.favoritesDao())
    }

    /**
     * Restore 错词本 marks to their original word-list rows (Roadmap #7):
     * built-in sources read the asset library, history sources the stored row.
     * Both are **resolved** against the offline lexicon at read time — a mark
     * keeps its 词性/释义 (and 音标) or 拼音/组词, and 朗读释义 still has
     * something to speak, without a single dictionary column ever being
     * written into a row (AGENTS.md *Built-in library*).
     */
    val wrongWordLineResolver: WrongWordLineResolver by lazy {
        WrongWordLineResolver(
            builtinListRows = { category, label ->
                resolve(libraryRepository.entries(LibraryList(category, label)))
            },
            historyRows = { id ->
                historyRepository.all().firstOrNull { it.id == id }
                    ?.let { resolve(parseWordRows(it.text)) }
                    .orEmpty()
            },
        )
    }

    /**
     * Rows composed with the offline lexicon (row columns win field by field,
     * AGENTS.md *Built-in library*) — the single read-time pass behind
     * every runtime consumer: the dial, the display lists, a staged session,
     * 错词本 restore. The authored rows are never rewritten; nothing is
     * persisted back.
     */
    private suspend fun resolve(rows: List<WordRow>): List<ResolvedWord> =
        lexiconRepository.resolve(rows)

    private val englishLexiconWarmed = AtomicBoolean(false)

    /**
     * Warm the English dictionary ahead of the lookup that would otherwise pay
     * for it: the first `lexicon-en.json` decode costs ~745 ms on a device
     * (measured) and lands today at the moment a user opens their first English
     * list. Fire-and-forget on the application scope — never the startup path,
     * and never a screen waiting on it.
     *
     * Called only when English is actually coming (a browsed library category
     * was seen to hold English words, `LibraryListsViewModel`): the dictionary
     * being *lazy* is a design constraint, not an implementation detail — a
     * Chinese-only list must not parse it (AGENTS.md "Lexicon asset loading").
     * Once per process: a second caller has nothing left to warm.
     */
    fun warmEnglishLexicon() {
        if (!englishLexiconWarmed.compareAndSet(false, true)) return
        applicationScope.launch {
            // Let the screen that asked settle first: the parse is 745 ms of
            // CPU on a background thread, and it must not compete with the
            // first frames of the category the user just opened.
            delay(LEXICON_WARM_DELAY_MS)
            try {
                lexiconRepository.warmEnglish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A warm-up that fails only means the next lookup parses as it
                // always did; nothing to surface.
                Log.w(TAG, "English lexicon warm-up failed", e)
            }
        }
    }

    /** Favorite entry ids (`default_*` or history ids). */
    val favoritesRepository: FavoritesRepository by lazy {
        FavoritesRepository(database.favoritesDao())
    }

    /** Local dictation record (Roadmap #3 听写统计): one row per finished run. */
    val sessionRepository: SessionRepository by lazy {
        SessionRepository(database.sessionDao())
    }

    /** The offline dictionary (义项/IPA + 拼音/组词); parsed lazily on first
     *  lookup, never on the startup path. */
    val lexiconRepository: LexiconRepository by lazy {
        LexiconRepository { path -> assets.open(path) }
    }

    private companion object {
        const val TAG = "HearWriteApplication"

        /**
         * How long a warm-up waits before parsing. Long enough for the category
         * the user just opened to draw its first frames (a device opens a
         * category ~4 s before tapping a list — measured), short enough that
         * the ~745 ms parse still lands before that tap.
         */
        const val LEXICON_WARM_DELAY_MS = 400L
    }
}
