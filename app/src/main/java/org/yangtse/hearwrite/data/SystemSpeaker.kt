package org.yangtse.hearwrite.data

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.yangtse.hearwrite.domain.DEFAULT_SPEECH_RATE
import org.yangtse.hearwrite.domain.MAX_SPEECH_RATE
import org.yangtse.hearwrite.domain.MIN_SPEECH_RATE
import org.yangtse.hearwrite.domain.Speaker
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

// ---- System-voice enumeration and naming helpers (pure, unit-tested) ----
//
// Android gives no display name and no gender field for a `Voice` — engines
// expose raw ids (`cmn-cn-x-ssa-local`, `zh-cn-x-txc-network`) or short
// English names, and the gender is at best an engine-specific token
// (`cmn-cn-x-ssa#female_1-local`, `<name>-male`). Selection keys are the
// raw ids persisted as-is; the label shown in 设置 is derived here: a
// readable engine name is kept, a meaningless generated id falls back to
// 中文男声1 / 中文女声1 / 中文语音1-style naming (英文 for English) so the
// list stays navigable on any engine.
//
// These helpers are pure — they run on a plain [SystemEngineVoice] model,
// not the android `Voice` class, so the JVM unit tests can pin them
// without the mockable-android stub jar ([SystemSpeaker] maps `Voice` →
// [SystemEngineVoice] once at the boundary).

/** True when [lang] targets the Chinese TTS locale (zh-CN). */
fun systemLangIsZh(lang: String): Boolean =
    lang.startsWith("zh", ignoreCase = true)

/** English-region ids for the 英文地区 picker (美式/英式). */
const val SYSTEM_EN_REGION_US = "us"
const val SYSTEM_EN_REGION_GB = "gb"

/** Region of a stored English voice key (`en-gb-*` → gb, else us). */
fun systemEnRegionOf(name: String): String =
    if (name.startsWith("en-gb", ignoreCase = true)) SYSTEM_EN_REGION_GB else SYSTEM_EN_REGION_US

/** The region of an English [voice] by its locale country, or null when it
 *  has no country (a bare `en` engine voice — the engine does not say
 *  which accent it is, so labeling it 美式 would be a lie). */
fun systemEnVoiceRegion(voice: SystemEngineVoice): String? = when (voice.country.uppercase()) {
    "US" -> SYSTEM_EN_REGION_US
    "GB" -> SYSTEM_EN_REGION_GB
    else -> null
}

/**
 * System-voice routing, mirroring the Edge source ([EdgeTts.resolveEdgeVoice]):
 * CJK text always uses the default (zh) voice — a zh-capable system voice
 * speaks both Chinese and English (the en-US/en-GB English-only voices
 * cannot speak Chinese). English text uses the dedicated English voice only
 * when 英文使用默认音色 is off ([useDefaultEn] false).
 */
internal fun resolveSystemVoice(
    text: String,
    voiceZh: String,
    voiceEn: String,
    useDefaultEn: Boolean,
): String {
    val trimmed = text.trim()
    val cjk = trimmed.any { it.code in 0x4e00..0x9fff }
    if (cjk) return voiceZh
    return if (useDefaultEn) voiceZh else voiceEn
}

/**
 * The engine-independent description of one selectable TTS voice ([Voice]
 * fields that matter to the picker, minus the android type).
 */
data class SystemEngineVoice(
    val name: String,
    val language: String,
    val country: String,
    val networkRequired: Boolean,
)

/** One-way bridge: android [Voice] → the pure [SystemEngineVoice] model. */
private fun systemEngineVoice(voice: Voice): SystemEngineVoice = SystemEngineVoice(
    name = voice.name,
    language = voice.locale.language,
    country = voice.locale.country,
    networkRequired = voice.isNetworkConnectionRequired,
)

/**
 * A system voice row in the 系统语音 picker. [key] is the raw engine
 * [SystemEngineVoice.name] persisted as the selection; [label] is what the
 * user sees; [gender] is "男"/"女" when the engine marks it, else "".
 */
data class SystemVoiceInfo(
    val key: String,
    val label: String,
    val gender: String,
)

/** True when [voice] can serve a simplified-Chinese dictation list. */
private fun systemVoiceSpeaksZh(voice: SystemEngineVoice): Boolean {
    val lang = voice.language.lowercase()
    val country = voice.country.uppercase()
    // Mainland voices only (mandarin zh/cmn); zh-TW/zh-HK/yue are
    // traditional-script voices that cannot serve simplified 生字.
    return (lang == "zh" || lang == "cmn") && (country.isEmpty() || country == "CN")
}

/** True when [voice] can serve an English dictation list (US English). */
private fun systemVoiceSpeaksEn(voice: SystemEngineVoice): Boolean {
    val lang = voice.language.lowercase()
    val country = voice.country.uppercase()
    return lang == "en" && (country.isEmpty() || country == "US")
}

/** True when [voice] serves the English region [region] (us/gb). */
private fun systemVoiceSpeaksEnRegion(voice: SystemEngineVoice, region: String): Boolean {
    val lang = voice.language.lowercase()
    val country = voice.country.uppercase()
    val want = if (region == SYSTEM_EN_REGION_GB) "GB" else "US"
    return lang == "en" && (country.isEmpty() || country == want)
}

/**
 * The voice's stem: Google exposes the same voice character twice, once
 * on-device and once network-synthesized, as `…-local`/`…-network` twins
 * of one stem (`cmn-cn-x-ssa-local`/`cmn-cn-x-ssa-network`). The picker
 * must offer one entry per actual voice, not per transport.
 */
internal fun systemVoiceStem(name: String): String {
    val lower = name.lowercase()
    return when {
        lower.endsWith("-local") -> name.dropLast("-local".length)
        lower.endsWith("-network") -> name.dropLast("-network".length)
        else -> name
    }
}

/**
 * True for the engine's locale-default pseudo entries (`en-US-language`,
 * `zh-CN-language`) — they are the same voice `setLanguage` picks for
 * 默认（引擎选择）, so they add nothing to the picker.
 */
private fun systemVoiceIsLanguagePseudo(voice: SystemEngineVoice): Boolean =
    voice.name.lowercase().endsWith("-language")

/**
 * The selectable voices of an engine's set that serve [lang], deterministically
 * sorted: exact-country locale first, then embedded voices before network
 * ones, then by name — deduped per [systemVoiceStem] (the local twin wins)
 * and without the `-language` pseudo entries. The stable order is what
 * makes the fallback numbers (中文男声1…) reproducible.
 */
fun systemVoicesFor(voices: Set<SystemEngineVoice>, lang: String): List<SystemEngineVoice> {
    val matches = if (systemLangIsZh(lang)) ::systemVoiceSpeaksZh else ::systemVoiceSpeaksEn
    return voices.asSequence()
        .filter(matches)
        .filterNot(::systemVoiceIsLanguagePseudo)
        .sortedWith(systemVoiceSort)
        // Sorted puts the local twin first per stem — distinctBy keeps it.
        .distinctBy { systemVoiceStem(it.name) }
        .toList()
}

/**
 * The voices that serve the English region [region] (设置 英文地区
 * 美式/英式): en-US voices for 美式, en-GB voices for 英式. The engine also
 * ships en-AU/en-IN/en-NG — those serve neither textbook list (US accent),
 * so only the chosen region's voices are selectable. Other sorting is the
 * same as [systemVoicesFor].
 */
fun systemEnVoicesForRegion(voices: Set<SystemEngineVoice>, region: String): List<SystemEngineVoice> =
    voices.asSequence()
        .filter { systemVoiceSpeaksEnRegion(it, region) }
        .filterNot(::systemVoiceIsLanguagePseudo)
        .sortedWith(systemVoiceSort)
        .distinctBy { systemVoiceStem(it.name) }
        .toList()

/**
 * All English voices in one picker list: en-US and en-GB together (the
 * textbook serve set), sorted so countryful voices come first (US then GB
 * by name) with countryless ones (e.g. a bare `en` engine voice) last —
 * labels carry 美式英语/英式英语 prefixes so one dropdown reads as a
 * single ordered list. Countryless voices appear only here (never in the
 * per-region lists — those would double-count them in both regions).
 */
fun systemEnVoicesAll(voices: Set<SystemEngineVoice>): List<SystemEngineVoice> {
    val countryful = voices.asSequence()
        .filter { it.language.lowercase() == "en" && it.country.isNotEmpty() }
        .filterNot(::systemVoiceIsLanguagePseudo)
        .sortedWith(systemVoiceSort)
        .distinctBy { systemVoiceStem(it.name) }
        .toList()
    val countryless = voices.asSequence()
        .filter { it.language.lowercase() == "en" && it.country.isEmpty() }
        .filterNot(::systemVoiceIsLanguagePseudo)
        .sortedWith(compareByDescending<SystemEngineVoice> { !it.networkRequired }.thenBy { it.name })
        .distinctBy { systemVoiceStem(it.name) }
        .toList()
    // US before GB within the countryful set: the sort is by country, but
    // reorder so the merged picker reads 美式… then 英式… then 英文…
    return countryful.filter { it.country.uppercase() == "US" } +
        countryful.filter { it.country.uppercase() == "GB" } +
        countryless
}

/** Shared deterministic order: exact-country first, embedded before network, then name. */
private val systemVoiceSort = compareByDescending<SystemEngineVoice> {
    it.country.uppercase() == "CN" || it.country.uppercase() == "US" || it.country.uppercase() == "GB"
}.thenByDescending { !it.networkRequired }.thenBy { it.name }

/**
 * The engine's own readable display string for [voice], or "" when the id
 * is machine-generated. Google-style synthetic ids carry the `-x-` variant
 * separator (`cmn-cn-x-ssa-local`, `zh-cn-x-txc-network`, `…#female_1`)
 * and expose no human name — those get the numbered fallback naming.
 * Reverse-domain ids (`com.apple.voice.compact.en-US.Samantha`) yield the
 * final segment as the voice name.
 */
fun systemVoiceDisplayName(voice: SystemEngineVoice): String {
    val raw = voice.name.trim()
    if (raw.isBlank()) return ""
    // Google/Samsung-style synthetic voice ids: `-x-` + engine code.
    if (raw.contains("-x-")) return ""
    val segments = raw.split('.')
    val reverseDomain = segments.size >= 2 && segments.first().all { it.isLowerCase() }
    val candidate = if (reverseDomain) segments.last().trim() else raw
    if (candidate.isEmpty()) return ""
    // Opaque short engine code (`cmn-cn`, `yue-CN`) is not a human name.
    if (candidate.length <= 12 && candidate.count { it == '-' } >= 2) return ""
    return candidate
}

/**
 * Voice gender for 男声/女声 naming, else "" when the engine gives no
 * marker. Google marks it after `#` (`cmn-cn-x-ssa#female_1-local`);
 * engines that name voices in the clear fall back to the name itself.
 */
fun systemVoiceGender(voice: SystemEngineVoice): String {
    val name = voice.name.lowercase()
    return when {
        name.contains("female") -> "女"
        name.contains("male") -> "男"
        else -> ""
    }
}

/**
 * Build the picker rows for [voices] (already filtered + sorted for
 * [lang]). Voices with a readable engine name keep that name; the rest are
 * numbered within their own kind — 中文男声1/中文女声1 first, then
 * 中文语音1 for unmarked ids (英文 prefix for English) — so a Google-style
 * raw-id set still reads as an ordered choice list.
 */
fun systemVoiceInfos(voices: List<SystemEngineVoice>, lang: String): List<SystemVoiceInfo> {
    val base = if (systemLangIsZh(lang)) "中文" else "英文"
    var male = 0
    var female = 0
    var other = 0
    return voices.map { voice ->
        val gender = systemVoiceGender(voice)
        val label = systemVoiceDisplayName(voice).ifEmpty {
            when (gender) {
                "男" -> "${base}男声${++male}"
                "女" -> "${base}女声${++female}"
                else -> "${base}语音${++other}"
            }
        }
        SystemVoiceInfo(voice.name, label, gender)
    }
}

/**
 * Build the English picker rows as one ordered list with region prefixes:
 * countryful voices (US then GB, sorted) are numbered within their region
 * (美式英语1…, 英式英语1…); a readable engine name keeps the region prefix
 * in front (美式 Samantha). Countryless voices (a bare `en` engine voice)
 * get the neutral 英文语音N naming — the engine does not say which accent
 * it is, so labeling it 美式 would be a lie.
 */
fun systemEnVoiceInfosAll(voices: Set<SystemEngineVoice>): List<SystemVoiceInfo> {
    var us = 0
    var gb = 0
    var other = 0
    return systemEnVoicesAll(voices).map { voice ->
        val display = systemVoiceDisplayName(voice)
        val name = when (systemEnVoiceRegion(voice)) {
            SYSTEM_EN_REGION_GB -> if (display.isNotEmpty()) "英式 $display" else "英式英语${++gb}"
            SYSTEM_EN_REGION_US -> if (display.isNotEmpty()) "美式 $display" else "美式英语${++us}"
            else -> if (display.isNotEmpty()) display else "英文语音${++other}"
        }
        SystemVoiceInfo(voice.name, name, systemVoiceGender(voice))
    }
}

/**
 * System `android.speech.tts.TextToSpeech` wrapper — the always-available
 * fallback link of the TTS priority chain (AGENTS.md). All Android-only
 * concerns live here, behind the domain [Speaker] contract.
 *
 * 音色 selection (设置 → 系统语音): the engine's [Voice] set is enumerated
 * once after a successful init and cached in [availableVoices]; the
 * persisted per-language selection (设置 → 系统语音音色, raw [Voice.name]
 * keys) live-follows into [zhVoice]/[enVoice] exactly like the Edge voices,
 * so changing a voice in 设置 takes effect without a session restart.
 *
 * Contract details that must not regress:
 * - Async init (`onInit`) is wrapped in a suspension resumed from the init
 *   callback; the first [speak] waits for it. Init failure is reported as
 *   `false`, never thrown, and retried after a short cooldown on a later
 *   [speak] (an engine installed or enabled mid-session must recover).
 * - `UtteranceProgressListener` releases the pending continuation from both
 *   `onDone` and `onError` (missing onError = permanent hang).
 * - A watchdog (`max(4000, text.length * 250)` ms, same as upstream) releases
 *   the continuation even if the engine never reports — assume success, the
 *   next utterance flushes the queue. Playback must never freeze on a mute
 *   utterance.
 * - Cancelling the awaiting coroutine (pause/skip/stop/leave) stops the
 *   current utterance via `invokeOnCancellation`.
 * - Unsupported locale (missing data/language) is a failure, never a
 *   silently wrong-voice utterance.
 */
class SystemSpeaker(
    context: Context,
    settings: SettingsRepository? = null,
) : Speaker {

    private val appContext = context.applicationContext

    /** Speech rate (0.5–1.5, default 0.9); applied before every utterance. */
    @Volatile
    private var speechRate: Float = DEFAULT_SPEECH_RATE

    private val lock = Any()
    private var engine: TextToSpeech? = null

    /** Elapsed-realtime ms of the last failed init; 0 = never failed. */
    private var initFailedAt = 0L

    private val initMutex = kotlinx.coroutines.sync.Mutex()
    private val utteranceCounter = AtomicLong(0)

    /**
     * The engine's [SystemEngineVoice] set (mapped from [Voice]), cached
     * after the first successful init and replaced on re-init (an engine
     * change mid-session must refresh the picker). Read for the 系统语音
     * picker; a failed/absent enumeration leaves it null and the picker
     * falls back to locale-only entries.
     */
    @Volatile
    private var availableVoices: List<SystemEngineVoice>? = null

    /**
     * The live engine's raw [Voice] objects keyed by name, for setVoice on
     * utterance. Populated with [availableVoices] so selection and playback
     * always see the same enumeration.
     */
    @Volatile
    private var engineVoicesByName: Map<String, Voice> = emptyMap()

    /** The persisted 中文默认音色 voice key; blank = engine default. */
    @Volatile
    private var zhVoice: String = ""
    /** The persisted English voice key (used only when useDefaultEn is off). */
    @Volatile
    private var enVoice: String = ""
    /** 英文使用默认音色 (default on): English uses [zhVoice], not [enVoice]. */
    @Volatile
    private var useDefaultEn: Boolean = true

    init {
        if (settings != null) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch { settings.systemVoiceZh.collect { zhVoice = it } }
            scope.launch { settings.systemVoiceEn.collect { enVoice = it } }
            scope.launch { settings.systemUseDefaultEn.collect { useDefaultEn = it } }
        }
    }

    /** Set the speech rate for subsequent utterances (persisted setting). */
    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
    }

    /**
     * The engine's cached selectable voices for [lang] (设置 → 系统语音音色),
     * or null when the engine exposes none for that language. The picker
     * reads this after [ensureVoiceList] so the list reflects the live
     * engine.
     */
    fun voicesFor(lang: String): List<SystemVoiceInfo>? {
        val voices = availableVoices ?: return null
        val langVoices = systemVoicesFor(voices.toSet(), lang)
        if (langVoices.isEmpty()) return null
        return systemVoiceInfos(langVoices, lang)
    }

    /**
     * The engine's cached voices for the English region [region] (us/gb),
     * labeled 英文男声N-style. Null when the engine exposes none for that
     * region.
     */
    fun enVoicesForRegion(region: String): List<SystemVoiceInfo>? {
        val voices = availableVoices ?: return null
        val regionVoices = systemEnVoicesForRegion(voices.toSet(), region)
        if (regionVoices.isEmpty()) return null
        return systemVoiceInfos(regionVoices, SYSTEM_LANG_EN)
    }

    /**
     * The engine's cached English voices as one merged list (美式英语N /
     * 英式英语N / 英文语音N labels), or null when none exist.
     */
    fun enVoicesAll(): List<SystemVoiceInfo>? {
        val voices = availableVoices ?: return null
        val all = systemEnVoiceInfosAll(voices.toSet())
        if (all.isEmpty()) return null
        return all
    }

    /**
     * Ensure the engine is initialized (and the [Voice] set enumerated),
     * then return the selectable voices for [lang]. The 设置 系统语音音色
     * picker calls this when it opens — the enumeration happens on the
     * first init, so a settings visit without any prior dictation must not
     * show an empty list. Returns null when no engine/voice serves [lang].
     */
    suspend fun ensureVoiceList(lang: String): List<SystemVoiceInfo>? {
        val tts = ensureEngine() ?: return null
        if (availableVoices == null) refreshVoices(tts)
        return voicesFor(lang)
    }

    /**
     * Ensure the engine is initialized, then return the English voices as
     * one merged list (美式英语N/英式英语N/英文语音N).
     */
    suspend fun ensureEnVoicesAll(): List<SystemVoiceInfo>? {
        val tts = ensureEngine() ?: return null
        if (availableVoices == null) refreshVoices(tts)
        return enVoicesAll()
    }

    /**
     * The current selection key for [lang]: the persisted custom voice when
     * it is still in the engine's set, else the top of the sorted list (the
     * locale-exact, embedded voice the engine itself prefers), else "".
     * Never persisted here — 设置 writes the chosen key.
     */
    fun currentVoiceKey(lang: String): String {
        val voices = availableVoices?.toSet() ?: return ""
        val zh = systemLangIsZh(lang)
        val persisted = if (zh) zhVoice else enVoice
        val langVoices = if (zh) {
            systemVoicesFor(voices, lang)
        } else {
            systemEnVoicesAll(voices)
        }
        if (langVoices.isEmpty()) return ""
        return langVoices.firstOrNull { it.name == persisted }?.name
            ?: langVoices.first().name
    }

    /** Apply [key] to the live engine (setVoice, else setLanguage fallback). */
    private fun applyVoiceSelection(tts: TextToSpeech, key: String, lang: String) {
        val target = engineVoicesByName[key]
        if (target != null) {
            val result = tts.setVoice(target)
            if (result == TextToSpeech.SUCCESS) return
            Log.w(TAG, "setVoice $key failed ($result), falling back to locale")
        } else if (key.isNotBlank()) {
            Log.w(TAG, "voice $key not in the engine set; using locale voice")
        }
        val locale = if (systemLangIsZh(lang)) SYSTEM_LOCALE_ZH else SYSTEM_LOCALE_EN
        tts.setLanguage(locale)
    }

    /** Update the cached enumeration after a successful init (re-init refreshes). */
    private fun refreshVoices(tts: TextToSpeech) {
        val set = try {
            tts.voices?.toSet().orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "voices() unavailable", e)
            emptySet()
        }
        val mapped = set.map(::systemEngineVoice)
        engineVoicesByName = set.associateBy { it.name }
        // Cache the whole engine voice set; the per-language/region filters
        // apply at query time (voicesFor/enVoicesForRegion) — caching only
        // zh+US would starve the 英式 list.
        availableVoices = mapped
    }

    /**
     * Wait for the shared engine, creating it when needed. A failed init is
     * retried on a later call after a short cooldown (an engine installed or
     * enabled while the app runs must recover without a process restart —
     * first Google TTS install on a Xiaomi reproduced 2026-09); a bound
     * engine that never reports init is recreated only when the bind itself
     * failed. The wait is bounded by a watchdog so playback never blocks on a
     * hung framework bind; the engine is created on the main thread as the
     * framework requires.
     */
    private suspend fun ensureEngine(): TextToSpeech? {
        var current = synchronized(lock) { engine }
        if (current == null) {
            val failedRecently = synchronized(lock) {
                initFailedAt != 0L &&
                    SystemClock.elapsedRealtime() - initFailedAt < INIT_RETRY_COOLDOWN_MS
            }
            if (!failedRecently) {
                initMutex.withLock {
                    current = synchronized(lock) { engine }
                    if (current == null) {
                        current = tryInit()
                    }
                }
            }
        }
        return current
    }

    /**
     * One init attempt: create the engine (framework requires the calling
     * thread — [speak] runs on main) and wait for `onInit`. Returns the
     * engine on success. On failure or a watchdog timeout the instance is
     * shut down and the failure timestamp recorded so the retry cooldown in
     * [ensureEngine] applies. Each attempt carries its own deferred — a late
     * `onInit` from an abandoned attempt can never resume a newer attempt's
     * wait (a shared continuation made a stale callback double-resume).
     */
    private suspend fun tryInit(): TextToSpeech? {
        var holder: TextToSpeech? = null
        val attempt = CompletableDeferred<Boolean>()
        holder = TextToSpeech(appContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            Log.i(TAG, "init ${if (ok) "ok" else "failed ($status)"}")
            if (ok) {
                synchronized(lock) { engine = holder }
                holder?.let { refreshVoices(it) }
            }
            attempt.complete(ok)
        }
        val ok = withTimeoutOrNull(INIT_WATCHDOG_MS) { attempt.await() } ?: run {
            // onInit never fired (hung bind) — playback must not block.
            Log.w(TAG, "init timed out")
            false
        }
        if (!ok) {
            synchronized(lock) {
                if (engine !== holder) {
                    initFailedAt = SystemClock.elapsedRealtime()
                    holder.shutdown()
                }
                // else: a late SUCCESS onInit already claimed this instance
                // right after the timeout — keep the live engine.
            }
        }
        return synchronized(lock) { engine }
    }

    /**
     * Configure [tts] for one utterance: language locale, then the [voiceKey]
     * (setVoice — after setLanguage, which resets the voice to the locale
     * default; a key missing from the engine set falls back to the locale
     * default). Returns false when the language itself is unsupported.
     */
    private suspend fun doSpeak(
        tts: TextToSpeech,
        trimmed: String,
        lang: String,
        voiceKey: String,
    ): Boolean {
        val zh = systemLangIsZh(lang)
        val locale = if (zh) SYSTEM_LOCALE_ZH else SYSTEM_LOCALE_EN
        val langResult = tts.setLanguage(locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA ||
            langResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.w(TAG, "locale $lang unsupported ($langResult) for \"$trimmed\"")
            return false
        }
        applyVoiceSelection(tts, voiceKey, lang)
        tts.setSpeechRate(speechRate)
        return speakUtterance(tts, trimmed)
    }

    override suspend fun speak(text: String, lang: String): Boolean = try {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val tts = ensureEngine() ?: return false
        val key = resolveSystemVoice(trimmed, zhVoice, enVoice, useDefaultEn)
        doSpeak(tts, trimmed, lang, key)
    } catch (e: CancellationException) {
        // The playback run was cancelled; propagate, never swallow.
        throw e
    } catch (e: Exception) {
        // Defensive audio boundary: never throw into the playback engine.
        Log.w(TAG, "speak failed", e)
        false
    }

    /**
     * Preview [sample] in the engine voice [key] (设置 系统语音音色 试听)
     * without changing the persisted selection. Same failure contract as
     * [speak].
     */
    suspend fun previewVoice(key: String, lang: String, sample: String): Boolean = try {
        val trimmed = sample.trim()
        if (trimmed.isEmpty()) return false
        val tts = ensureEngine() ?: return false
        doSpeak(tts, trimmed, lang, key)
    } catch (e: CancellationException) {
        // The playback run was cancelled; propagate, never swallow.
        throw e
    } catch (e: Exception) {
        // Defensive audio boundary: never throw into the playback engine.
        Log.w(TAG, "voice preview failed", e)
        false
    }

    /** One utterance through the ready [tts]: watchdog + listener plumbing. */
    private suspend fun speakUtterance(tts: TextToSpeech, trimmed: String): Boolean {
        val utteranceId = "hw-${utteranceCounter.incrementAndGet()}"
        val watchdogMs = maxOf(4_000L, trimmed.length * 250L)
        val settled = AtomicBoolean(false)

        val outcome = coroutineScope {
            var continuation: Continuation<Boolean>? = null

            val watchdog = launch {
                delay(watchdogMs)
                continuation?.let { settle(it, settled, true, "watchdog") }
            }

            val result = suspendCancellableCoroutine { cont ->
                continuation = cont
                val listener = object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(id: String?) {
                        if (id == utteranceId) {
                            settle(cont, settled, true, "onDone")
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        if (id == utteranceId) {
                            settle(cont, settled, false, "onError")
                        }
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        if (id == utteranceId) {
                            settle(cont, settled, false, "onError($errorCode)")
                        }
                    }

                    override fun onStop(id: String?, interrupted: Boolean) {
                        // Stopped by our own stop()/a newer utterance; release
                        // so the chain never hangs on a stop.
                        if (id == utteranceId) {
                            settle(cont, settled, true, "onStop")
                        }
                    }
                }
                tts.setOnUtteranceProgressListener(listener)

                // Cancel the awaiting run (pause/skip/stop/leave): stop audio.
                cont.invokeOnCancellation {
                    try {
                        tts.stop()
                    } catch (e: Exception) {
                        Log.w(TAG, "stop-on-cancel failed", e)
                    }
                }

                val result = tts.speak(trimmed, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (result == TextToSpeech.ERROR) {
                    settle(cont, settled, false, "speak returned ERROR")
                }
            }
            watchdog.cancel()
            result
        }
        return outcome
    }

    /** Kill the current utterance (pause/skip/leave screen). Idempotent. */
    override fun stop() {
        synchronized(lock) {
            try {
                engine?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "stop failed", e)
            }
        }
    }

    /** Single-shot resume guarded against listener/cancel/watchdog races. */
    private fun settle(
        cont: Continuation<Boolean>,
        settled: AtomicBoolean,
        ok: Boolean,
        why: String,
    ) {
        if (settled.compareAndSet(false, true)) {
            try {
                cont.resume(ok)
            } catch (e: IllegalStateException) {
                // The awaiting run was cancelled concurrently; nothing to do.
            }
        }
    }

    companion object {
        private const val TAG = "SystemSpeaker"

        /** The Chinese dictation locale (simplified; 识字/词语 textbook lists). */
        private val SYSTEM_LOCALE_ZH = Locale.SIMPLIFIED_CHINESE
        /** The English dictation locale (US textbook lists). */
        private val SYSTEM_LOCALE_EN = Locale.US

        /** BCP-47 tags for the voice picker / engine locale checks. */
        private const val SYSTEM_LANG_ZH = "zh-CN"
        private const val SYSTEM_LANG_EN = "en-US"

        /** Wait for onInit at most this long; a hung bind then releases the
         *  instance and the next call retries. */
        private const val INIT_WATCHDOG_MS = 10_000L

        /** Minimum gap between init attempts after a failure — avoids a
         *  tight retry loop when no engine is available. */
        private const val INIT_RETRY_COOLDOWN_MS = 2_000L
    }
}
