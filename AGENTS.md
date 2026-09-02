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

### Word-line format (behavioral contract, from `alice/src/lib/dictation.ts`)

- A list is one entry per non-empty line. Line = `word | pos | meaning` (fullwidth `｜` accepted); 1 or 3 columns — the bare word alone is valid. Column semantics by entry kind:
  - **English**: `pos` = part of speech (`n.` `v.` …), `meaning` = 中文释义 (display + optional spoken meaning).
  - **Chinese single char** (识字表/写字表): `pos` = pinyin with tone marks (`yuè`; neutral tones unmarked), `meaning` = 组词 containing the head char (`月亮`).
  - **Chinese words** (词语表): bare word, spoken as-is.
- `you're = you are` expansion: speak only the **left** side.
- POS normalization map (ECDICT ↔ textbook spellings): `interj./exclam.→int.`, `na./un./pla./pn.→n.`, `vbl./pp.→v.`, `pref./suf./suff./comb./stuff.→abbr.`, `a.→adj.`, `pl.→n.`; strip a known POS prefix (`POS_PREFIX_RE` in `dictation.ts`) before treating column 1 as the headword.
- CJK detection: entry is Chinese when its speakable text contains `[\u4e00-\u9fff]` — switches TTS locale to `zh-CN` and hint rendering to pinyin-based.

### Speech-text rules

- **组词朗读** (`cjkWordSpeech`, for single-char entries): speak `"组词的X"` — e.g. `月|yuè|月亮` → `"月亮的月"`. Candidate pool = textbook "learned" compounds first (`compounds.json` `learned`), then common-word fallback (`compounds` map, frequency-ordered). Filter by reading: the candidate's syllable for the head char must match the entry's toned pinyin (tone-digit comparison, `ü→v`; unmarked/neutral tone passes — see `syllableMatches`). A small stoplist of function chars reads the bare char (`dictation.ts` `NO_COMPOUND_HEADS`).
- **朗读释义** (`speakableMeaning`, English entries with 朗读中文释义 enabled): speak only the first sense; sense split on `；;`, gloss split on `，,、`; strip parentheticals and edge punctuation; cap at visual width 12 (fullwidth = 1, halfwidth = 0.5).

### Playback engine (`DictationEngine`)

Coroutine-driven state machine; per word: `speak1` → 700 ms gap → `speakMeaning` (only if entry has a meaning **and** 朗读释义 enabled) → `speak2` → `interval` countdown → next word. Settings: interval 1–10 s (default 7, step 0.5), auto-next on/off, speech rate. Pause/resume; changing the interval applies **mid-countdown** (read deadline from state each tick, not captured at schedule time); leaving the screen stops playback. UI: countdown ring, show/hide current word, POS/meaning hints, 标记错词 button; final-second countdown tick sound; chime at session end.

### TTS priority chain

1. **Youdao** (default): `GET https://dict.youdao.com/dictvoice?audio=<urlencoded>&le=zh` for CJK; `&type=2` then `&type=1` for English. Send a mobile-browser `User-Agent`; reject responses < 256 bytes; cache MP3s under `cacheDir/tts/` keyed by text+lang.
2. **System TTS** (fallback, always available): `android.speech.tts`, locale `en-US`/`zh-CN` by entry kind, speech rate from settings (0.5–1.5, default 0.9).
3. **Optional OpenAI-compatible TTS** (BYOK, Settings): wire shapes — `speech`: `POST {base}/audio/speech` returning binary audio (default format `mp3`); `chat`: `POST {base}/chat/completions` with an `audio` option returning base64 in `choices[0].message.audio.data` (小米 MiMo free tier). Config: `api`, `baseUrl`, `apiKey`, `model`, `voiceEn`, `voiceZh`, optional `responseFormat`. Clip cache keyed by `provider|model|voice|rate|text` hash in the same TTS dir. **Any failure falls back to the system TTS** — dictation never blocks on a provider outage. Original presets live in `alice/src/lib/ttsConfig.ts`.

All audio downloads: single-flight per text, cancellation-friendly, defensive degrade (bare-catch → next link in the chain).

### OCR import (拍照识词)

- Photo Picker (`ActivityResultContracts.PickVisualMedia`) or camera capture; downscale to longest edge ≤ 1600 px, JPEG quality ≈ 0.82, base64 data URL.
- `POST {base}/chat/completions` with image content; parse reply line-by-line with the standard word-line parser (strip surrounding code fence if present). Prompts — keep **verbatim**, Chinese, from `alice/src/lib/ocr.ts` (English-list and 生字/词语 variants; language picked in the scan sheet).
- Default preset: Zhipu `https://open.bigmodel.cn/api/paas/v4`, model `glm-4v-flash` (free). BYOK only — the user enters their own key in Settings; no built-in key, no credits, no quota accounting (the paid stack is excluded). Surface the disclaimer `"AI 识图可能存在误差，请核对识别结果"` at OCR entry points; errors surface as Chinese messages with a retry.

### Persistence

- **Room**: `wrong_words` (word, addedAt), `history` (user lists only — id, text, enriched text, createdAt; **cap 50**, drop oldest), `favorites` (entry ids: `default_*` or history ids). Wrong words and favorites are keyed by the speakable headword / entry id exactly as `alice/src/lib/storage.ts`.
- **DataStore Preferences**: word-input draft (debounced 500 ms), speech rate, interval sec, auto-next, read-translation, sound effects on/off, TTS source (`youdao|custom`) + provider config JSON, OCR provider config JSON, theme (light/dark/system).
- Built-in library content is **never** persisted — read from assets each launch (cache in memory).

## Key Directories

| Path | Purpose |
| --- | --- |
| `app/` | The Android application (Compose UI, domain, data) |
| `data/` | Source word lists + bundled data assets (APK assets; see formats below) |
| `data/meta/` | 《现代汉语常用词表（草案）》 frequency table — regeneration source for compounds.json, not shipped |
| `scripts/` | Data tooling (Python 3, stdlib-first), added when needed |
| `docs/` | Screenshots for README (added when UI exists) |
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

## Code Conventions

- **Language**: identifiers, comments, docstrings (KDoc), and commit messages in **English**; user-facing UI strings and spoken sample text hardcoded **Chinese**.
- **Style**: official Kotlin coding conventions (4-space indent, LF, UTF-8); `ktlint`/`detekt` not configured yet — do not add tooling mid-phase without noting it in PROGRESS.md. No `!!`; use `require`/`check` for programmer errors, and defensive catch-to-fallback only at network/audio boundaries.
- **Naming**: `*Screen.kt` composables, `*ViewModel.kt`, `*Repository.kt`, `*Dao.kt`; pure functions in `domain/` are top-level and testable.
- **Errors**: user-facing failures become Chinese message strings; network/audio layers never throw into UI — they degrade (TTS chain, OCR retry).
- **Commit discipline**: one thing per commit (`feat: …`, `fix: …`, `docs: …`, `data: …`, `chore: …`); **every commit must compile**. A phase closes with a commit that updates PROGRESS.md.

## Testing & QA

- Unit tests (JUnit4 + `kotlinx-coroutines-test`) cover the `domain/` behavioral contract: line parsing (1/3 columns, fullwidth pipe, `you're = you are`), POS normalization, CJK detection, `cjkWordSpeech` (polyphone filtering, learned-first), `speakableMeaning` (sense split + width cap), `compareLabels` ordering, playback phase transitions.
- Assets/dict data are **read-only fixtures** — tests may load from `data/` but never modify it.
- Verification workflow per phase: `assembleDebug` + `testDebugUnitTest` green → install → exercise the changed surface on device → demo described in PROGRESS.md → commit.
