# Repository Guidelines

## Project Overview

**HearWrite 听写** (package `org.yangtse.hearwrite`) — an Android dictation trainer for Chinese students: paste or photograph a word list → the app speaks each word with a countdown → the student writes it down → mark wrong words → review later. Two dictation modes:

- **English** (英文): word → (optional spoken Chinese meaning) → word, twice per word.
- **Chinese** (汉字): 生字 → 组词 → 生字 (`"月" → "月，月亮的月"`), the standard classroom dictation call.

This is a **from-scratch Kotlin + Jetpack Compose rewrite** of [`YangtseSu/alice`](https://github.com/YangtseSu/alice) (`/home/yangtse/projects/alice`, Expo/React Native). It is **not** a port or line-by-line translation: reimplement in idiomatic Kotlin/Compose, keeping **behavioral parity only where this file specifies it**. Fork provenance: upstream commits are by others; `Yangtse` commits are ours. License of upstream data assets noted in *Data Assets*.

**Non-goals (do not build):** iOS / Web / desktop; backend server; the paid-features stack of the original (Credits, Recharge, model tiers — **excluded entirely**); Expo/RN code reuse. Platform targets: `minSdk 36`, `targetSdk 37`, `compileSdk 37`. All user-facing UI strings are hardcoded Chinese.

## Toolchain (latest stable at project start, 2026-09)

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 21+ (JDK 26 installed; Gradle daemon runs on 21) | toolchain language level 21 |
| Gradle | 9.7.1 (wrapper) | machine has Gradle 9.7.1 installed |
| AGP | 9.3.0 | max API 37, needs Gradle ≥ 9.5.0 |
| Kotlin | 2.4.10 | Compose compiler via `org.jetbrains.kotlin.plugin.compose` |
| Compose BOM | 2026.08.00 | Material 3 |

- Android SDK at `~/Android/Sdk` (write `local.properties` with `sdk.dir`); **only `android-36` platform is installed** — run `sdkmanager "platforms;android-37"` (accept licenses) before the first build.
- Keep all dependency versions in `gradle/libs.versions.toml` (version catalog). Beyond the pinned four above, pick **latest stable** at scaffold time and record in the catalog. Boring choices: `androidx.core-ktx`, `activity-compose`, `lifecycle-viewmodel-compose`, `navigation-compose`, `datastore-preferences`, `room-runtime/ktx` + KSP, `kotlinx-serialization-json`, `okhttp`, `material3`, `material-icons-extended`.
- Verify toolchain bumps still satisfy the pinning table; never downgrade below it.
- **Version fallback**: if the pinned combination fails to resolve or compile, take the versions from the current Android Studio **Empty Activity (Compose)** template `libs.versions.toml`, note the change in the commit message, and tell the user — never fight incompatibilities to keep a version number.

## Architecture & Data Flow

Single Gradle module `:app` until a real need appears. Layering (keep Android imports out of `domain/`):

- `ui/` — Compose screens (`HomeScreen`, `DictationScreen`, `SettingsScreen`), reusable components (`CountdownRing`, bottom sheets, drawers), `theme/`. Material 3; light + dark themes; Chinese strings inline.
- `domain/` — pure Kotlin: word-list parsing, entry model, speech-text rules (组词朗读, 释义截取), playback phase machine (`DictationEngine`), label ordering. Unit-test target.
- `data/` — asset loader (built-in library), Room (wrong words / history / favorites), DataStore (settings + drafts), OkHttp clients (Youdao TTS, OpenAI-compatible TTS/OCR), system `TextToSpeech` wrapper.

State: `ViewModel` + `StateFlow` + `collectAsStateWithLifecycle`. **No DI framework** — Application-scoped singletons (`HearWriteApplication`) with lazy init. Navigation: `navigation-compose` with Home / Dictation / Settings routes (Finish is a Dictation-screen end state). Only three outbound network call sites: Youdao TTS download, optional OpenAI-compatible TTS, OpenAI-compatible vision OCR. **No backend.**

### Built-in library (data assets in APK)

- `data/` at repo root ships **verbatim** into APK assets via `android.sourceSets["main"].assets.srcDir(rootProject.file("data"))`. Asset paths: `"<category>/<label>.txt"`, plus `dict/ecdict-meta.json`, `compounds/compounds.json`, `audio/tick.wav`, `audio/chime.wav`. Exclude `meta/` from packaging (aapt `ignoreAssetsPattern`) — it is regeneration source only.
- Categories = the 10 textbook dirs (`中考1600`, `高考3500`, `初中2182`, `人教版初中`, `人教版小学`, `人教版初中语文`, `人教版小学语文`, `外研版初中`, `闽教版小学`, `仁爱版初中`). Built-in entry id = `default_<category>_<label>` where `<label>` = filename sans `.txt`. **Labels are stable storage keys** — renaming a file orphans persisted history/favorite ids; never rename.
- List order for the 词库 drawer: port `compareLabels` from `scripts/generate-library.ts` (grade rank 一→九, 上/下/全, Unit/Module number, 第X册; natural compare fallback). Implement in `domain/` with unit tests.
- **Dictionary/compound asset loading**: `dict/ecdict-meta.json` (3.3 MB → ~53k-entry map) is parsed **lazily on first lookup** on `Dispatchers.IO` and kept in a memory singleton; `compounds/compounds.json` likewise. **Never parse them on the startup path** (Application init / first frame). Cost ≈ 15–25 MB heap + hundreds of ms — acceptable on minSdk 36, but it is the one real weak spot of the raw-assets approach: if measured cold start exceeds 500 ms (PROGRESS Phase 10 item), move the **dictionary alone** to a prebuilt SQLite (Room `createFromAsset`); word lists stay as assets. Do not pre-optimize before measuring.

### Word-line format (behavioral contract, from `alice/src/lib/dictation.ts`)

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
  Pinyin filter: the candidate's syllable for the head char must match the entry's toned pinyin (tone digits, `ü→v`; unmarked/neutral tone on either side passes — `syllableMatches`).
  ⚠️ **Never dedupe candidates by word — walk the raw array, take the first filter pass.** Real walk for `朝 | zhāo` (no meaning column, no other 朝-words in the list, tier-3 pool): `朝鲜(chao2)✗ → 朝廷(chao2)✗ → 明朝(zhao1)✓ → "明朝的朝"`. Dedupe kills readings: `澄 | dèng` walks `澄清(cheng2)✗ → 澄清(deng4)✓ → "澄清的澄"` — a dedupe keeping only the first 澄清 row (cheng2) leaves the dèng reading with no candidate at all (falls back to the bare char). ~21 words carry dual readings like this (`朝` pool: 朝鲜 chao2, 朝廷 chao2, 明朝 zhao1, 朝阳 zhao1, 王朝 chao2, 朝阳 chao2).
- **朗读释义** (`speakableMeaning`, English entries): per sense (split `；;`) — strip a leading POS prefix (`n.` `vt.` …, else TTS spells it letter by letter), strip parentheticals and edge punctuation, take the first non-empty sense; if it still exceeds visual width 12 (fullwidth = 1, halfwidth = 0.5), cut at the first `，,、` boundary. Empty result = nothing to speak.
- **No pinyin library** (`pinyin4j`/`TinyPinyin`/…): textbook rows carry pinyin, compound data carries per-word syllables, and the parser never needs to generate pinyin.

### Playback engine (`DictationEngine`)

Coroutine-driven state machine on its own `SupervisorJob` scope; per word: `speak1` → 700 ms gap → `speakMeaning` → `speak2` → `interval` countdown → next word. `speakMeaning` text: CJK **single char** → `cjkWordSpeech` output, **always** (the traditional classroom call — the 朗读释义 toggle only gates English glosses); multi-char CJK → nothing (word spoken as-is, twice); English → `speakableMeaning` only when 朗读释义 is on. Settings: interval 1–10 s (default 7, step 0.5), auto-next on/off, speech rate. Pause/resume; leaving the screen stops playback.

Start options live on Home (both session-local, not persisted): **随机顺序** (Fisher–Yates over a copy of the parsed list; toggle in the playback controls, not switchable mid-dictation) and **起始序号** (start index in the word-input section, clamped when the list shrinks). Applied in order slice → shuffle before navigating to Dictation — the engine plays the list it is given and knows nothing about either.

Implementation constraints (each line below mirrors a real upstream bug — do not regress):
- Cancel via `Job.cancel()` + a **generation counter** bumped on every start/pause/stop/skip/prev; re-check `gen` after every suspension. Never boolean flags.
- The countdown deadline lives in a `StateFlow`/`@Volatile` field, **re-read every tick** — an upstream loop captured the deadline once and live interval changes silently did nothing. Interval changes apply mid-countdown.
- `speak1` failure → **no retry**, skip the gap, advance to the next phase (`speak2` is the natural second attempt); retry loops froze the app.
- Auto-next off: after `speak2` clear the scheduler but keep the session alive; re-enabling resumes from the current word at the `interval` phase.
- `speak()` never waits for a download — ready-cached audio only, else the next link in the chain; prefetch the current word, the next word, and the English gloss in the background.
- End of list → completion state + chime; system back during dictation asks for confirmation, never exits silently.
UI: countdown ring (last-second tick; `clearAndSetSemantics` announcing remaining seconds), current word **hidden by default** — tap to reveal, the core interaction — POS/meaning hints, 标记错词 button, prev/pause/next/stop, progress `n / total`.

### TTS priority chain

1. **Youdao** (default): `GET https://dict.youdao.com/dictvoice?audio=<urlencoded>&le=zh` for CJK (`type=2`/`type=1` cannot speak Chinese); `&type=2` then `&type=1` for English. Send a mobile-browser `User-Agent`; reject responses < 256 bytes; cache MP3s under `cacheDir/tts/` keyed by text+lang; single-flight per text.
2. **System TTS** (fallback, always available): `android.speech.tts`, locale `en-US`/`zh-CN` by entry kind, speech rate from settings (0.5–1.5, default 0.9). Wrap async init in `suspendCoroutine` resumed from `onInit`; resume the pending continuation from **both** `onDone` and `onError` of `UtteranceProgressListener` (missing `onError` = permanent hang); play cached MP3 clips with `MediaPlayer` guarded by a completion listener **plus a 10 s timeout** (Media3 only if a concrete need appears).
3. **Optional OpenAI-compatible TTS** (BYOK, Settings). Two wire shapes, picked by the `api` field — both send `Authorization: Bearer <apiKey>` + `Content-Type: application/json`:
   - **`speech`** (standard binary — OpenAI TTS, 智谱 GLM-TTS, 硅基流动…): `POST {base}/audio/speech`, body:
     `{ "model": "<model>", "input": "<text>", "response_format": "mp3", "speed": 0.9 }` — `speed` = speech rate clamped to [0.25, 4], `response_format` from config (default `mp3`). Response = **binary audio bytes**.
   - **`chat`** (小米 MiMo synthesis via chat completions): `POST {base}/chat/completions`, body:
     `{ "model": "<model>", "messages": [{ "role": "assistant", "content": "<text>" }], "audio": { "format": "wav" } }` — format is fixed `wav` on this shape. Response = JSON with **base64 audio at `choices[0].message.audio.data`** (missing/empty → error).
   - **Voice by text language**: CJK text → `voiceZh`, English → `voiceEn`; an empty voice means **omit the `voice` field** so the provider default applies.
   - **Clip cache key = 8-hex hash of `api|model|voice|format|rate`** (rate ×10 rounded) + text — every component is hashed so a voice/format/rate change regenerates clips instead of replaying stale audio. Non-OK responses surface `error.message` when present, else `HTTP <status>`. Presets live in `alice/src/lib/ttsConfig.ts`.

`Speaker` contract: `suspend fun speak(text, lang): Boolean` + `stop()` — returns `false` on failure, never throws into the caller. The 组词 phrase (`"月亮的月"`) **always** speaks via system zh-CN TTS — Youdao dict voice cannot serve sentences. Any failure falls through to the next link; dictation never blocks on a download or an outage.

### OCR import (拍照识词)

- Photo Picker (`ActivityResultContracts.PickVisualMedia`) or camera capture; downscale to longest edge ≤ 1600 px, JPEG quality ≈ 0.82, base64 data URL.
- `POST {base}/chat/completions` with image content; parse reply line-by-line with the standard word-line parser (strip surrounding code fence if present). Prompts — keep **verbatim**, Chinese, from `alice/src/lib/ocr.ts` (English-list and 生字/词语 variants; language picked in the scan sheet).
- Default preset: Zhipu `https://open.bigmodel.cn/api/paas/v4`, model `glm-4v-flash` (free). BYOK only — the user enters their own key in Settings; no built-in key, no credits, no quota accounting (the paid stack is excluded). Surface the disclaimer `"AI 识图可能存在误差，请核对识别结果"` at OCR entry points; errors surface as Chinese messages with a retry.

### Persistence

- **Room**: `wrong_words` (word, addedAt), `history` (user lists only — id, text, enriched text, createdAt; **cap 50**, drop oldest), `favorites` (entry ids: `default_*` or history ids). Wrong words and favorites are keyed by the speakable headword / entry id exactly as `alice/src/lib/storage.ts`.
- **DataStore Preferences**: word-input draft (debounced 500 ms), speech rate, interval sec, auto-next, read-translation, sound effects on/off, TTS source (`youdao|custom`) + provider config JSON, OCR provider config JSON, theme (light/dark/system).
- Built-in library content is **never** persisted — read from assets each launch (cache in memory).

### Ported pitfalls (upstream fixed these in RN — write them correctly from day one)

- **Debounce flush**: the word-input draft persists debounced (500 ms) — flush pending input in `DisposableEffect.onDispose`; upstream cleared the timer without flushing and lost the last keystrokes on exit.
- **Re-entry guards before the first suspension**: claim via `Mutex.tryLock()` (or a `@Volatile` flag) **before** the first await; checks placed after a suspension point let double-taps through (upstream double-charged OCR).
- **Every `launch` catches**: async blocks `try/catch` and surface failures in the UI; a swallowed error made a failed request look successful upstream.
- **List keys**: stable ids or indices — never content that changes while editing (remounts inputs mid-typing).
- **Sliders**: native Compose `Slider` only (TalkBack increment/decrement comes free), each with a Chinese `contentDescription` ("听写间隔秒数" etc.) — a bare "滑块" announcement is useless. Never hand-roll Canvas sliders. Icon buttons carry `contentDescription`.
- **Threading**: DB/file/network off the main thread (`Dispatchers.IO`); no `Thread.sleep`/`Handler.postDelayed`/`Timer` for playback timing — coroutines + `delay()`.
- No `LiveData`, no `SharedPreferences`, no XML layouts, no `GlobalScope`; business state in `StateFlow` (`mutableStateOf` only for ephemeral UI state like text-field values).

## Key Directories

| Path | Purpose |
| --- | --- |
| `app/` | The Android application (Compose UI, domain, data) |
| `data/` | Source word lists + bundled data assets (APK assets; see formats below) |
| `data/meta/` | 《现代汉语常用词表（草案）》 frequency table — regeneration source for compounds.json, not shipped |
| `scripts/` | Data tooling (Python 3, stdlib-first), added when needed |
| `docs/` | `PHASES.md` phase log, `DEVELOPMENT.md` build/signing/packaging guide; README screenshots added when needed |
| `/home/yangtse/projects/alice/` | Read-only reference (RN implementation); consult when this file is ambiguous — `src/lib/dictation.ts`, `src/hooks/usePlayback.ts`, `src/lib/tts.ts`, `src/lib/ocr.ts` are the behavioral ground truth |

## Data Assets (formats & provenance — never hand-edit derived files)

| Asset | Format | Provenance |
| --- | --- | --- |
| `data/<category>/<label>.txt` | word lines, see *Word-line format* | Original alice word lists (user's own extraction from textbooks) |
| `data/dict/ecdict-meta.json` | flat `{word: "pos.|gloss"}` (senses `；`-split) | Built from [ECDICT](https://github.com/skywind3000/ECDICT) (MIT) by `alice/scripts/build-ecdict-meta.py`; copied as-is to avoid re-downloading. Offline EN→ZH lookup: POS, senses, exam tags |
| `data/compounds/compounds.json` | `{compounds: {char: [[word, syllable], …]}, learned: {…}}`; syllable = tone digits, `ü=v`, neutral unmarked | Converted from `alice/src/lib/compounds.ts` (generated from the frequency table + 人教版小学语文 识字表). 4724 + 17 char keys |
| `data/meta/xiandaihanyuchangyongcibiao.txt` | `word\tpinyin\tlevel` | 《现代汉语常用词表（草案）》(教育部), 56008 words |
| `data/audio/tick.wav`, `chime.wav` | countdown tick (last second, vol 0.5), session-finish chime (vol 0.6) | Synthesized in-house for alice |

`alice/assets/silent.wav` (iOS background-audio keep-alive) is deliberately **not** ported — Android has no need.

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
- **Style**: official Kotlin coding conventions (4-space indent, LF, UTF-8); `ktlint`/`detekt` not configured yet — do not add tooling mid-phase without noting it in docs/PHASES.md. No `!!`; use `require`/`check` for programmer errors, and defensive catch-to-fallback only at network/audio boundaries.
- **Naming**: `*Screen.kt` composables, `*ViewModel.kt`, `*Repository.kt`, `*Dao.kt`; pure functions in `domain/` are top-level and testable.
- **Errors**: user-facing failures become Chinese message strings; network/audio layers never throw into UI — they degrade (TTS chain, OCR retry).
- **Commit discipline**: one thing per commit (`feat: …`, `fix: …`, `docs: …`, `data: …`, `chore: …`); **every commit must compile**. A phase closes with a commit that updates docs/PHASES.md.

## Testing & QA

- Unit tests (JUnit4 + `kotlinx-coroutines-test`) cover the `domain/` behavioral contract: line parsing (1/3 columns, fullwidth pipe, `you're = you are`), POS normalization, CJK detection, `cjkWordSpeech` (tier order, polyphone filtering with the `朝|zhāo → 朝阳` case, learned-first), `speakableMeaning` (POS strip + sense split + width cap), `compareLabels` ordering, and the `DictationEngine` — tested with a fake `Speaker` and `runTest` virtual time: phase order, no-retry on speak failure, cancel leaves no stray speaks, auto-next hold, generation races, and ★ live interval change verified via `advanceTimeBy`.
- Assets/dict data are **read-only fixtures** — tests may load from `data/` but never modify it.
- Verification workflow per phase: `assembleDebug` + `testDebugUnitTest` green → install → exercise the changed surface on device → demo described in docs/PHASES.md → commit. Temporary debug UI built for a demo is removed before the phase's final commit.
