# Repository Guidelines

## Project Overview

**HearWrite 听写** (package `org.yangtse.hearwrite`) — an Android dictation trainer for Chinese students: paste or photograph a word list → the app speaks each word with a countdown → the student writes it down → mark wrong words → review later. Two dictation modes:

- **English** (英文): word → (optional spoken Chinese meaning) → word, twice per word.
- **Chinese** (汉字): 生字 → 组词 → 生字 (`"月" → "月，月亮的月"`), the standard classroom dictation call.

This is a **from-scratch Kotlin + Jetpack Compose app** for Chinese dictation training. The author's earlier Expo/React Native app **alice** (MIT; original upstream author vvenv) is **superseded and frozen**: nothing in this repo reads, builds against, or consults the alice project, and **this file is the sole behavioral contract**. This is **not** a port or line-by-line translation: idiomatic Kotlin/Compose; predecessor behavior is reflected here **only where this file specifies it**. Data asset provenance and licenses are noted in *Data Assets*.

**Non-goals (do not build):** iOS / Web / desktop; backend server; the paid-features stack of the original (Credits, Recharge, model tiers — **excluded entirely**); Expo/RN code reuse. Platform targets: `minSdk 36`, `targetSdk 37`, `compileSdk 37`. All user-facing UI strings are hardcoded Chinese.

## Toolchain (latest stable at project start, 2026-09)

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 21+ (JDK 26 installed; daemon pinned to 21 via user-level `~/.gradle/gradle.properties`, never committed) | toolchain language level 21 |
| Gradle | 9.7.1 (wrapper) | machine has Gradle 9.7.1 installed |
| AGP | 9.3.0 | max API 37, needs Gradle ≥ 9.5.0 |
| Kotlin | 2.4.10 | Compose compiler via `org.jetbrains.kotlin.plugin.compose` |
| Compose BOM | 2026.08.00 | Material 3 |

- Android SDK at `~/Android/Sdk` (write `local.properties` with `sdk.dir`); the build needs `platforms;android-37` (installed). Machine setup — official cmdline-tools only, the distro-packaged sdkmanager's index lacks android-37 — is covered in `docs/DEVELOPMENT.md`.
- Keep all dependency versions in `gradle/libs.versions.toml` (version catalog). Beyond the pinned four above, pick **latest stable** at scaffold time and record in the catalog. Boring choices: `androidx.core-ktx`, `activity-compose`, `lifecycle-viewmodel-compose`, `navigation-compose`, `datastore-preferences`, `room-runtime/ktx` + KSP, `kotlinx-serialization-json`, `okhttp`, `material3`, `material-icons-extended`.
- Verify toolchain bumps still satisfy the pinning table; never downgrade below it.
- **Version fallback**: if the pinned combination fails to resolve or compile, take the versions from the current Android Studio **Empty Activity (Compose)** template `libs.versions.toml`, note the change in the commit message, and tell the user — never fight incompatibilities to keep a version number.

## Architecture & Data Flow

Single Gradle module `:app` until a real need appears. Layering (keep Android imports out of `domain/`):

- `ui/` — Compose screens (`HomeScreen`, `DictationScreen`, `SettingsScreen` + sub-pages, `LibraryScreen`/`LibraryListsScreen`/`LibraryPreviewScreen`, `OcrScanSheet`), reusable components (`CountdownRing`, sheet hosts in `HomeDrawers`, crop overlay, `ListRows`), `theme/`. Material 3; light + dark themes; Chinese strings inline.
- `domain/` — pure Kotlin: word-list parsing, entry model, speech-text rules (组词朗读, 释义截取), playback phase machine (`DictationEngine`), label ordering. Unit-test target.
- `data/` — asset loader (built-in library), Room (wrong words / history / favorites), DataStore (settings + drafts), OkHttp clients (Youdao TTS, OpenAI-compatible TTS/OCR), system `TextToSpeech` wrapper, `DictationSessionStore` (session staging), `SoundEffects` (tick/chime WAV), `Haptics`.

State: `ViewModel` + `StateFlow` + `collectAsStateWithLifecycle`. **No DI framework** — Application-scoped singletons (`HearWriteApplication`) with lazy init. Navigation: `navigation-compose` with Home / Dictation / Settings / Library (category → list → preview) routes — Finish is a Dictation-screen end state and every dictation exit lands Home. Session word lists stage in `DictationSessionStore` (in-memory handoff written right before navigating; process death restarts from Home). Only four outbound network call sites: Youdao TTS download, Microsoft Edge Read-Aloud (WebSocket), optional OpenAI-compatible TTS, OpenAI-compatible vision OCR. **No backend.**

### Built-in library (data assets in APK)

- `app/src/main/assets/` ships **verbatim** into the APK — the standard AGP assets dir, no custom `sourceSets`/aapt wiring. Asset paths: `"<category>/<label>.txt"`, plus `dict/ecdict-meta.json`, `compounds/compounds.json`, `audio/tick.wav`, `audio/chime.wav`. The compounds generator and its regeneration source live in `scripts/` (outside the assets dir) and are never packaged: `python3 scripts/generate-compounds.py` (stdlib) rewrites `compounds/compounds.json` from `scripts/data/xiandaihanyuchangyongcibiao.txt` + `app/src/main/assets/人教版小学语文/`.
- Categories = the 10 textbook dirs (`中考1600`, `高考3500`, `初中2182`, `人教版初中`, `人教版小学`, `人教版初中语文`, `人教版小学语文`, `外研版初中`, `闽教版小学`, `仁爱版初中`). Built-in entry id = `default_<category>_<label>` where `<label>` = filename sans `.txt`. **Labels are stable storage keys** — renaming a file orphans persisted history/favorite ids; never rename.
- List order in the library browser (category dirs; labels within a category): `compareLabels` — grade rank 一→九, 上/下/全, Unit/Module number, 第X册. CJK runs compare pinyin syllable → stroke count → code point — the JDK `Collator` disagrees with ICU zh-Hans on 同音字 (仁 before 人 vs 人 before 仁), so it serves Latin runs only; no plain natural-compare fallback for CJK. Implemented in `domain/` (`LabelOrder.kt`), locked by unit tests against the golden `library-label-order.json` fixture (`app/src/test/resources/`).
- **Dictionary/compound asset loading**: `dict/ecdict-meta.json` (3.3 MB → ~53k-entry map) is parsed **lazily on first lookup** on `Dispatchers.IO` and kept in a memory singleton; `compounds/compounds.json` likewise. **Never parse them on the startup path** (Application init / first frame). Cost ≈ 15–25 MB heap + hundreds of ms — acceptable on minSdk 36, but it is the one real weak spot of the raw-assets approach: if measured cold start exceeds 500 ms (the Phase 10 acceptance threshold), move the **dictionary alone** to a prebuilt SQLite (Room `createFromAsset`); word lists stay as assets. Do not pre-optimize before measuring.

### Word-line format (behavioral contract)

- A list is one entry per non-empty line. Line = `word | pos | meaning` (fullwidth `｜` accepted); 1 or 3 columns — the bare word alone is valid. Column semantics by entry kind:
  - **English**: `pos` = part of speech (`n.` `v.` …), `meaning` = 中文释义 (display + optional spoken meaning).
  - **Chinese single char** (识字表/写字表): `pos` = pinyin with tone marks (`yuè`; neutral tones unmarked), `meaning` = 组词 containing the head char (`月亮`).
  - **Chinese words** (词语表): bare word, spoken as-is.
- `you're = you are` expansion: speak only the **left** side.
- POS normalization map (ECDICT ↔ textbook spellings): `interj./exclam.→int.`, `na./un./pla./pn.→n.`, `vbl./pp.→v.`, `pref./suf./suff./comb./stuff.→abbr.`, `a.→adj.`, `pl.→n.`; strip a known POS prefix (`POS_PREFIX_RE` in `dictation.ts`) before treating column 1 as the headword.
- CJK detection: entry is Chinese when its speakable text contains `[\u4e00-\u9fff]` — switches TTS locale to `zh-CN` and hint rendering to pinyin-based.

### Speech-text rules

- **组词朗读** (`cjkWordSpeech`, single-char CJK entries only): speak `"组词的X"` — `月|yuè|月亮` → `"月亮的月"`. Multi-char words/sentences return `""` (spoken as-is). Function chars never compound (`的 地 得 着 了 吗 呢 吧 啊 呀 啦 嘛 么` — `NO_COMPOUND_HEADS`). Candidate tiers, first match wins:
  1. The entry's own `meaning` column, split on `；;` then `，,、`, parentheticals stripped — keep 2-char words containing the head char. **Not** pinyin-filtered (the textbook gloss is authoritative).
  2. "Learned" pool: the current list's other 2-char words containing the char (appearance order, not filtered), then `compounds.json` `learned` (filtered); this tier is rank-sorted by the common-word table.
  3. `compounds.json` `compounds` common-word pool (filtered, frequency-ordered).
  Pinyin filter: the candidate's syllable for the head char must match the entry's toned pinyin (tone digits, `ü→v`; unmarked/neutral tone on either side passes — `syllableMatches`). A pinyin-less row (bare char pasted by the user) has no reading anchor, so the head char's syllable is inferred from its most frequent common-pool word (`好` → 良好's `hao3`) and used as the filter — otherwise the learned row 好客(`hao4`) would hijack a bare 好 whose own passes speak the dominant hǎo. Chars with no common pool keep the pass-all fallback.
  ⚠️ **Never dedupe candidates by word — walk the raw array, take the first filter pass.** Real walk for `朝 | zhāo` (no meaning column, no other 朝-words in the list, tier-3 pool): `朝鲜(chao2)✗ → 朝廷(chao2)✗ → 明朝(zhao1)✓ → "明朝的朝"`. Dedupe kills readings: `澄 | dèng` walks `澄清(cheng2)✗ → 澄清(deng4)✓ → "澄清的澄"` — a dedupe keeping only the first 澄清 row (cheng2) leaves the dèng reading with no candidate at all (falls back to the bare char). ~21 words carry dual readings like this (`朝` pool: 朝鲜 chao2, 朝廷 chao2, 明朝 zhao1, 朝阳 zhao1, 王朝 chao2, 朝阳 chao2).
- **朗读释义** (`speakableMeaning`, English entries): per sense (split `；;`) — strip a leading POS prefix (`n.` `vt.` …, else TTS spells it letter by letter), strip parentheticals and edge punctuation, take the first non-empty sense; if it still exceeds visual width 12 (fullwidth = 1, halfwidth = 0.5), cut at the first `，,、` boundary. Empty result = nothing to speak.
- **No pinyin library** (`pinyin4j`/`TinyPinyin`/…): textbook rows carry pinyin, compound data carries per-word syllables, and the parser never needs to generate pinyin.

### Playback engine (`DictationEngine`)

Coroutine-driven state machine on its own `SupervisorJob` scope; per word: `speak1` → 700 ms gap → `speakMeaning` → `speak2` → `interval` countdown → next word. `speakMeaning` text: CJK **single char** → `cjkWordSpeech` output, **always** (the traditional classroom call — the 朗读释义 toggle only gates English glosses); multi-char CJK → nothing (word spoken as-is, twice); English → `speakableMeaning` only when 朗读释义 is on. Settings: interval 1–10 s (default 7, step 0.5), auto-next on/off, speech rate. Pause/resume; leaving the screen stops playback.

Start options are session-local (not persisted) and live on Home's playback panel **and** the library preview launchpad (`LibraryPreviewScreen`): **随机顺序** (Fisher–Yates over a copy of the parsed list; not switchable mid-dictation) and **起始序号** (Home picks it from the display-state word list; clamped when the list shrinks). Both pages funnel `prepareStartLines` (`domain/StartLines.kt`) — slice from `startIndex`, then shuffle — and stage the finished list in `DictationSessionStore` before navigating to Dictation; the engine plays the list it is given and knows nothing about either option.

Implementation constraints (each line below was a real bug in the RN predecessor — do not regress):
- Cancel via `Job.cancel()` + a **generation counter** bumped on every start/pause/stop/skip/prev; re-check `gen` after every suspension. Never boolean flags.
- The countdown deadline lives in a `StateFlow`/`@Volatile` field, **re-read every tick** — a predecessor loop captured the deadline once and live interval changes silently did nothing. Interval changes apply mid-countdown.
- `speak1` failure → **no retry**, skip the gap, advance to the next phase (`speak2` is the natural second attempt); retry loops froze the app.
- Auto-next off: after `speak2` clear the scheduler but keep the session alive; re-enabling resumes from the current word at the `interval` phase.
- `speak()` plays the active source's own ready cached clip; on a cold-start cache miss it waits a bounded time (≈4 s, joining the source's single-flight download) so one dictation keeps a single voice, then falls back to the system voice — playback never blocks unboundedly; prefetch the current word, the next word and the current line's meaning pass (English gloss; 组词 phrase under EDGE/CUSTOM) in the background.
- End of list → completion state + chime; system back during dictation asks for confirmation, never exits silently.
UI: countdown ring (last-second tick; `clearAndSetSemantics` announcing remaining seconds), current word **hidden by default** — tap to reveal, the core interaction — POS/meaning hints, 标记错词 button (buzzes a warning haptic, `Haptics.notifyWarning`), prev/pause/next/stop, progress `n / total`.

### TTS priority chain

1. **Youdao** (default): `GET https://dict.youdao.com/dictvoice?audio=<urlencoded>&le=zh` for CJK (`type=2`/`type=1` cannot speak Chinese); `&type=2` then `&type=1` for English. Send a mobile-browser `User-Agent`; reject responses < 256 bytes; cache MP3s under `cacheDir/tts/` keyed by text+lang; single-flight per text. Verified 2026-09-04 with curl (same URL + UA): 月/月亮 → `200 audio/mpeg`, valid MP3 (0.43 s / 0.62 s); 月亮的月 → `HTTP 500 application/json` with `{"msg":"returned null audio"}` — the endpoint is a per-word dictionary voice and refuses sentence input outright (hence the 组词 phrase is system-pinned; see `Speaker` below).
2. **Microsoft Edge Read-Aloud** (微软 Edge source, keyless): the neural voices behind Edge 朗读 — `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1` with `TrustedClientToken`, `Sec-MS-GEC` (SHA-256 of `<%.0f winTicks><token>`, winTicks = unix + 11644473600 floored to 300 s epoch windows ×10⁷, uppercase hex), `Sec-MS-GEC-Version=1-<chromium build>`, an Edge/Chromium Windows `User-Agent`, `Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold` and a fresh 32-hex `muid` cookie per connection. One WebSocket turn per clip: `Path:speech.config` JSON frame (outputFormat `audio-24khz-48kbitrate-mono-mp3`), then a `Path:ssml` frame (voice per language, prosody `rate` = 语速 0.5–1.5 → -50%..+50%); binary frames = 2-byte big-endian header length + `Path:audio`/`Content-Type:audio/mpeg` header block (its trailing `\r\n` included in the length) with the MP3 chunk starting immediately after it, `Path:turn.end` ends the turn. **音色选择**: the 发音来源 page offers a curated catalog (`data/EdgeTts.kt` `EDGE_VOICE_CATALOG` — Mainland zh-CN voices for Chinese, en-US for English, zh-HK/zh-TW and dialects excluded because they cannot serve simplified 普通话 lists) with per-voice 试听 (synthesizes the sample without selecting). Per-language selections persist under DataStore (`edge_voice_zh` / `edge_voice_en`; blank = built-in default 晓晓/Aria) and live-follow into playback; the SSML voice uses the long `Microsoft Server Speech Text to Speech Voice (…)` name from the catalog, and the clip cache keys bind voice + rate so a switch regenerates instead of replaying stale audio. A 403 with a `Date` header = clock skew: adjust once and retry. Clips cached under `cacheDir/tts/edge-…`, single-flight per clip, whole turn bounded by a 30 s watchdog. ⚠️ **Maintenance ritual**: Microsoft has broken this endpoint repeatedly (2023 Sec-MS-GEC era; 2025-12 MUID/UA/chunk changes) — on 401/403, sync the constants/algorithm in `data/EdgeTts.kt` with rany2/edge-tts (MIT) master and bump the pinned unit tests.
3. **System TTS** (fallback, always available): `android.speech.tts`, locale `en-US`/`zh-CN` by entry kind, speech rate from settings (0.5–1.5, default 0.9). Wrap async init in `suspendCoroutine` resumed from `onInit`; resume the pending continuation from **both** `onDone` and `onError` of `UtteranceProgressListener` (missing `onError` = permanent hang) **or** a watchdog (`max(4000, text.length × 250)` ms) that assumes success — a mute utterance must never freeze playback; play cached MP3 clips with `MediaPlayer` guarded by a completion listener **plus a 10 s watchdog** (Media3 only if a concrete need appears).
4. **Optional OpenAI-compatible TTS** (BYOK, Settings). Two wire shapes, picked by the `api` field — both send `Authorization: Bearer <apiKey>` + `Content-Type: application/json`:
   - **`speech`** (standard binary — OpenAI TTS, 智谱 GLM-TTS, 硅基流动…): `POST {base}/audio/speech`, body:
     `{ "model": "<model>", "input": "<text>", "response_format": "mp3", "speed": 0.9 }` — `speed` = speech rate clamped to [0.25, 4], `response_format` from config (default `mp3`). Response = **binary audio bytes**.
   - **`chat`** (小米 MiMo synthesis via chat completions): `POST {base}/chat/completions`, body:
     `{ "model": "<model>", "messages": [{ "role": "assistant", "content": "<text>" }], "audio": { "format": "wav" } }` — format is fixed `wav` on this shape. Response = JSON with **base64 audio at `choices[0].message.audio.data`** (missing/empty → error).
   - **Voice by text language**: CJK text → `voiceZh`, English → `voiceEn`; an empty voice means **omit the `voice` field** so the provider default applies.
   - **Clip cache key = 8-hex hash of `api|model|voice|format|rate`** (rate ×10 rounded) + text — every component is hashed so a voice/format/rate change regenerates clips instead of replaying stale audio. Non-OK responses surface `error.message` when present, else `HTTP <status>`. The preset list (mimo 小米 MiMo — the free default — and custom 自定义; the RN predecessor's zhipu / siliconflow / openai presets were dropped by the author's choice, their endpoints remain reachable via 自定义) lives in `data/TtsProviderConfig.kt` — **the single source of truth** (originally mirrored from the RN predecessor's preset list); unit tests pin it.

`Speaker` contract: `suspend fun speak(text, lang): Boolean` + `stop()` — returns `false` on failure, never throws into the caller. The sources are **peers**: the active source plays only its own cached clips (never another source's cache — a leftover Youdao clip must not surface under Edge/custom), and `system.speak` is the **sole fallback**. Cold start: a cache miss waits a bounded time (≈4 s, joining the source's single-flight prefetch) for the active source's own download before falling back, so one dictation keeps a single voice instead of opening on the system voice; the wait is cancellable and playback never blocks unboundedly. The 组词 phrase (`"月亮的月"`) follows the same routing **except** under Youdao: the dict voice cannot serve sentences, so there the phrase goes straight to system zh-CN TTS with **no network attempt** (a Youdao-only special case; under Edge/custom the phrase rides the chain in that source's voice, and is prefetched there too — never prefetched under Youdao). Any failure falls through to the system link; dictation never blocks unboundedly on a download or an outage.

### OCR import (拍照识词)

- Photo Picker (`ActivityResultContracts.PickVisualMedia`) or camera capture. Every pick/capture first goes through the **选定识别区域 crop step** (`ui/OcrCropOverlay.kt`: the RN predecessor got this from the system crop via `allowsEditing`, but Android has no guaranteed system crop, so HearWrite ships a Compose full-screen overlay): the source is decoded EXIF-rotated and power-of-two sampled to ≤ `OCR_CROP_SOURCE_EDGE` (4096) so a small region still carries text resolution; a draggable/resizable selection (sides floored at `OCR_CROP_MIN_SIDE_PX` 96 source px) defaults to the whole frame, so confirming without dragging keeps whole-page OCR; confirm crops the region (`OcrService.cropToDataUrl`, normalized-rect passthrough when it covers the whole image) and continues with the standard encode — downscale to longest edge ≤ 1600 px, JPEG quality ≈ 0.82, base64 data URL. Crop bitmap lifecycle is ViewModel-owned (`cropBitmap`/`cropLoading`, session id + job cancel against stale decodes, single recycle on confirm/cancel).
- `POST {base}/chat/completions` with image content. The reply first goes through the language-specific extractor (`OcrService.extractOcrLines`): English mode keeps ASCII word lines only — 音标/Chinese never become entries and mixed rows (word + 音标/词性/中文) yield their leading headword; Chinese mode keeps 汉字 runs only — pinyin/latin/digits are dropped and 生字/词语表-style headers are skipped. Markdown fences are then stripped and the remaining lines go through the standard word-line parser; an empty result surfaces a per-language message. Prompts — **verbatim** Chinese strings hardcoded in `OcrService.kt` (English-list and 生字/词语 variants; language picked in the scan sheet); unit tests pin them.
- Default preset: Zhipu `https://open.bigmodel.cn/api/paas/v4`, model `glm-4v-flash` (free). The preset list (zhipu 智谱 GLM / zen OpenCode Zen `mimo-v2.5-free` / vercel Vercel `xiaomi/mimo-v2.5` / commandcode Command Code `xiaomi/mimo-v2.5` / openrouter OpenRouter `qwen/qwen3.8-flash` / custom) lives in `data/OcrProviderConfig.kt` — **single source of truth** (originally mirrored from the RN predecessor's preset list; the openai / qwen / moonshot / siliconflow / ollama presets were dropped by the author's choice, their endpoints remain reachable via 自定义). BYOK only — the user enters their own key in Settings; no built-in key, no credits, no quota accounting (the paid stack is excluded). Surface the disclaimer `"AI 识图可能存在误差，请核对识别结果"` at OCR entry points; errors surface as Chinese messages with a retry.

### Persistence

- **Room**: `wrong_words` (word, addedAt), `history` (user lists only — id, text, enriched text, createdAt; **cap 50**, drop oldest), `favorites` (entry ids: `default_*` or history ids). Wrong words and favorites are keyed by the speakable headword / entry id (`default_*` built-in ids, history ids for user lists).
- **DataStore Preferences**: word-input draft (debounced 500 ms; the library preview's 载入草稿 feeds it via a one-shot import bus, `HearWriteApplication.requestDraftImport`), speech rate, interval sec, auto-next, read-translation, sound effects on/off, TTS source (`youdao|edge|system|custom`), one provider config JSON per preset (map keyed by preset id; legacy single-blob keys are migrated away on write) for TTS and OCR alike, theme (light/dark/system).
- Built-in library content is **never** persisted — read from assets each launch (cache in memory).

### Ported pitfalls (each fixed a real bug in the RN predecessor — write them correctly from day one)

- **Debounce flush**: the word-input draft persists debounced (500 ms) — flush pending input in `DisposableEffect.onDispose`; the RN predecessor cleared the timer without flushing and lost the last keystrokes on exit.
- **Re-entry guards before the first suspension**: claim via `Mutex.tryLock()` (or a `@Volatile` flag) **before** the first await; checks placed after a suspension point let double-taps through (the RN predecessor double-charged OCR).
- **Every `launch` catches**: async blocks `try/catch` and surface failures in the UI; a swallowed error made a failed request look successful in the RN predecessor.
- **List keys**: stable ids or indices — never content that changes while editing (remounts inputs mid-typing).
- **Sliders**: native Compose `Slider` only (TalkBack increment/decrement comes free), each with a Chinese `contentDescription` ("听写间隔秒数" etc.) — a bare "滑块" announcement is useless. Never hand-roll Canvas sliders. Icon buttons carry `contentDescription`.
- **Threading**: DB/file/network off the main thread (`Dispatchers.IO`); no `Thread.sleep`/`Handler.postDelayed`/`Timer` for playback timing — coroutines + `delay()`.
- No `LiveData`, no `SharedPreferences`, no XML layouts, no `GlobalScope`; business state in `StateFlow` (`mutableStateOf` only for ephemeral UI state like text-field values).

## Key Directories

| Path | Purpose |
| --- | --- |
| `app/` | The Android application (Compose UI, domain, data); bundled assets under `app/src/main/assets/` ship verbatim into the APK |
| `scripts/` | `generate-compounds.py` — compounds.json generator (Python stdlib) + `build-ecdict-meta.py` — dict asset builder (Python stdlib; downloads ECDICT to `.cache/` on first run) + `scripts/data/` regeneration sources (frequency table) — never shipped |
| `docs/` | `DEVELOPMENT.md` build/signing/packaging guide (maintained) |

## Data Assets (formats & provenance — never hand-edit derived files)

| Asset | Format | Provenance |
| --- | --- | --- |
| `app/src/main/assets/<category>/<label>.txt` | word lines, see *Word-line format* | Original alice word lists (user's own extraction from textbooks) |
| `app/src/main/assets/dict/ecdict-meta.json` | flat `{word: "pos.|gloss"}` (senses `；`-split) | Regenerable via `scripts/build-ecdict-meta.py` from [ECDICT](https://github.com/skywind3000/ECDICT) (MIT) csv (auto-downloaded to `.cache/` on first run). Offline EN→ZH lookup: POS, senses, exam tags |
| `app/src/main/assets/compounds/compounds.json` | `{compounds: {char: [[word, syllable], …]}, learned: {…}}`; syllable = tone digits, `ü=v`, neutral unmarked | Regenerated in-repo by `scripts/generate-compounds.py` from the frequency table + 人教版小学语文 lists (ported from the author's RN-predecessor generator). 4724 + 524 char keys |
| `scripts/data/xiandaihanyuchangyongcibiao.txt` | `word\tpinyin\tlevel` | 《现代汉语常用词表（草案）》(教育部, 商务印书馆 2008), 56008 words — downloaded from [`liangqi/chinese-frequency-word-list`](https://github.com/liangqi/chinese-frequency-word-list) (file `xiandaihaiyuchangyongcibiao.txt`, renamed to fix the pinyin typo); regeneration source for `scripts/generate-compounds.py`, never shipped |
| `app/src/main/assets/audio/tick.wav`, `chime.wav` | countdown tick (last second, vol 0.5), session-finish chime (vol 0.6) | Synthesized in-house |

The RN predecessor's `assets/silent.wav` (iOS background-audio keep-alive) is deliberately **not** ported — Android has no need.

## Development Commands

```bash
sdkmanager "platforms;android-37"        # once, before first build (accept licenses)

./gradlew :app:assembleDebug             # build APK
./gradlew :app:testDebugUnitTest         # unit tests — domain logic gate
./gradlew :app:lintDebug                 # Android lint
adb install -r app/build/outputs/apk/debug/app-debug.apk   # deploy to device/emulator
```

No emulator is guaranteed — verify on a connected device or emulator via `adb`; every phase's demo must run on a real surface.

### adb device-driving notes (real-device verification)

- **The screen sleeps**: a black screenshot, an empty `uiautomator dump`, or silently swallowed `input text` usually means the screen timed out mid-session — not a crash. Check `adb shell dumpsys window | grep mCurrentFocus` and logcat before debugging. During long drives keep the device awake with `adb shell svc power stayon true`, then restore `adb shell svc power stayon false`.
- **The default IME is a Chinese keyboard**: on zh-CN devices the input method (Gboard pinyin mode) intercepts `adb shell input text` — injected ASCII letters land in the composition/candidate buffer and never reach the field. Disable the IME first (`adb shell ime disable <ime-id>` from `adb shell ime list -s`; optionally switch to a non-keyboard IME) so input goes through the hardware-keyboard path, then `adb shell ime enable <ime-id>` to restore.
- **Swipe direction**: `adb shell input swipe x y1 x y2` dragging from a top area **downward** opens the notification shade / lock screen — a top-down swipe meant to scroll a list up near its top hides the app behind the shade and later inputs get eaten. List-scroll swipes go bottom-to-top (e.g. `540 1900 540 500`).
- **`unzip -l` garbles CJK asset names**: inspecting the APK with `unzip` shows mojibake for Chinese entries (terminal charset decoding) and `grep -c 'assets/.*\.txt$'` counts zero — the entries themselves are correct UTF-8. Verify asset packaging with Python instead: `python3 -c "import zipfile; z=zipfile.ZipFile('app/build/outputs/apk/debug/app-debug.apk'); print([n for n in z.namelist() if n.startswith('assets/')])"`.

## Code Conventions

- **Language**: identifiers, comments, docstrings (KDoc), and commit messages in **English**; user-facing UI strings and spoken sample text hardcoded **Chinese**, inline in code — no `strings.xml`, no i18n.
- **Style**: official Kotlin coding conventions (4-space indent, LF, UTF-8); `ktlint`/`detekt` not configured yet — do not add tooling mid-phase without noting it in the commit message. No `!!`; use `require`/`check` for programmer errors, and defensive catch-to-fallback only at network/audio boundaries.
- **Naming**: `*Screen.kt` composables, `*ViewModel.kt`, `*Repository.kt`, `*Dao.kt`; pure functions in `domain/` are top-level and testable.
- **Errors**: user-facing failures become Chinese message strings; network/audio layers never throw into UI — they degrade (TTS chain, OCR retry).
- **Commit discipline**: one thing per commit (`feat: …`, `fix: …`, `docs: …`, `data: …`, `chore: …`); **every commit must compile**. Verification evidence (device demo, measurements) goes in the commit message body.

## Testing & QA

- Unit tests (JUnit4 + `kotlinx-coroutines-test`) cover the `domain/` behavioral contract — line parsing (1/3 columns, fullwidth pipe, `you're = you are`), POS normalization, CJK detection, `cjkWordSpeech` (tier order, polyphone filtering with the `朝|zhāo → 朝阳` case, learned-first), `speakableMeaning` (POS strip + sense split + width cap), `compareLabels` ordering (against the `library-label-order.json` parity fixture), and the `DictationEngine` — plus the `data/` pure logic: Youdao URL/cache-file forms, TTS/OCR provider-config codecs and wire bodies (clip hash, `speech`/`chat` bodies), OCR reply extractors and fence stripping. Engine tests use a fake `Speaker` and `runTest` virtual time: phase order, no-retry on speak failure, cancel leaves no stray speaks, auto-next hold, generation races, and ★ live interval change verified via `advanceTimeBy`.
- Assets/dict data are **read-only fixtures** — tests may load from `app/src/main/assets/` but never modify it.
- Verification workflow: `assembleDebug` + `testDebugUnitTest` green → install → exercise the changed surface on device → commit (demo result described in the commit message). Temporary debug UI built for a demo is removed before the final commit.
