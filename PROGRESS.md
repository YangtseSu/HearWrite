# PROGRESS

Rewrite of [alice](https://github.com/YangtseSu/alice) (Expo/RN) as a native Android app — Kotlin + Jetpack Compose, from scratch. Rules: **one thing per phase**, every phase ends with a project that compiles, installs, and demos; one commit per implemented item; every commit compiles. See `AGENTS.md` for the architecture and behavioral contract.

Status: 🚧 in progress · ✅ done · ⏸ blocked (waiting on decision/input)

| Phase | Status | Demo |
| --- | --- | --- |
| 0. Bootstrap (docs + data) | ✅ 2026-09-02 | — (repo docs, data assets in place) |
| 1. Project scaffold | ✅ 2026-09-02 | App installs, shows named empty screens |
| 2. Domain: word-line parsing | ⬜ | Unit tests green |
| 3. Built-in library | ⬜ | 词库 drawer browses/searches all textbook lists |
| 4. Dictation engine + system TTS | ⬜ | Paste list → EN & 汉字 dictation runs end-to-end |
| 5. 汉字组词朗读 | ⬜ | `"月" → "月，月亮的月"`, 多音字 reads correctly |
| 6. Wrong words / history / favorites | ⬜ | 标记错词 → 错词本 review → export to clipboard |
| 7. Youdao TTS + sound effects | ⬜ | Words voiced by Youdao, tick/chime audible |
| 8. OCR import | ⬜ | Photo of word list → editable text |
| 9. OpenAI-compatible TTS (optional) | ⬜ | MiMo voice plays; outage falls back to system TTS |
| 10. Settings, theme, polish, release | ⬜ | Dark/light, signed release APK |

## Phase 0 — Bootstrap ✅ (2026-09-02)

- [x] `AGENTS.md` — architecture, toolchain pins, behavioral contract from alice.
- [x] Data assets copied from alice (no network fetch): word lists `data/` (10 categories, 403 lists), `data/dict/ecdict-meta.json` (ECDICT offline EN→ZH), `data/compounds/compounds.json` (converted from generated `compounds.ts`, 4724+17 chars), `data/meta/xiandaihanyuchangyongcibiao.txt` (组词 regeneration source), `data/audio/{tick,chime}.wav`.
- [x] Asset provenance (no generation scripts in this repo): word lists + `ecdict-meta.json` (built from ECDICT by `alice/scripts/build-ecdict-meta.py`) + `data/audio/*.wav` are verbatim copies from alice; `data/compounds/compounds.json` was converted from alice's generated `src/lib/compounds.ts` (TS type annotations stripped → JSON, counts verified 4724+17); `data/meta/xiandaihanyuchangyongcibiao.txt` is the MOE frequency table that alice's `scripts/generate-compounds.ts` used to build compounds — port that script only if compounds.json ever needs regeneration. `meta/` is not shipped in the APK.
- [x] `PROMPTS.md` — per-phase prompts for the coding agent.
- Commits: `docs: …`, `data: …`.

## Phase 1 — Project scaffold ✅ (2026-09-02)

- [x] Gradle project: single `:app` module, version catalog, pins from AGENTS.md (Gradle 9.7.1 wrapper, AGP 9.3.0, Kotlin 2.4.10, Compose BOM 2026.08.00), `minSdk 36` / `targetSdk 37` / `compileSdk 37`, package `org.yangtse.hearwrite`, app name "HearWrite 听写". AGP 9 built-in Kotlin: the Kotlin pin rides on the Compose compiler plugin (applying `org.jetbrains.kotlin.android` is forbidden by AGP 9); wrapper distribution URL points at the Tencent mirror pinned to the official SHA-256 (`services.gradle.org` unreachable from this network); daemon on JDK 21 via `org.gradle.java.home`; boring-list deps recorded at latest stable.
- [x] Compose skeleton: Material 3 theme (light/dark follow system), `HomeScreen` / `DictationScreen` / `SettingsScreen` as placeholder routes via navigation-compose, `HearWriteApplication` as the empty manual-singleton container.
- [x] `sdkmanager "platforms;android-37"` done (official cmdline-tools 16111833 — the distro-packaged sdkmanager's index lacks android-37); debug build + install verified on device (Redmi 2407FRK8EC).
- Accept: `./gradlew :app:assembleDebug` + `:app:testDebugUnitTest` green; app cold-starts (1.0 s) and 首页 → 听写 → 设置 switching verified on device with screenshots; dark mode follows system night mode.
- Commits: `chore: bootstrap gradle project`, `feat: compose navigation skeleton`, `docs: mark phase 1 complete`.

## Phase 2 — Domain: word-line parsing ⬜

- [ ] Port pure parsing from `alice/src/lib/dictation.ts`: `parseWordLine` (1/3 columns, fullwidth `｜`), POS prefix strip + normalization map, `speakTextFromEntry` (`you're = you are` → left side), CJK detection, `speakableMeaning` (sense split, width cap 12).
- [ ] JUnit tests for every rule above, including edge cases listed in AGENTS.md.
- Accept: `:app:testDebugUnitTest` green; a temporary debug screen or unit-test evidence shows parsed samples from `data/` fixtures.
- Commit: `feat: word-line parser with pos normalization` + `test: parser edge cases` (or one commit).

## Phase 3 — Built-in library ⬜

- [ ] Ship `data/` as APK assets (`assets.srcDirs(rootProject.file("data"))`, exclude `meta/`); asset loader with in-memory cache.
- [ ] Port `compareLabels` (grade/term/第X册 ordering) + category model; ids `default_<category>_<label>`.
- [ ] 词库 drawer/screen on Home: categories → lists → word preview, with search over labels/words.
- Accept: browse all 10 categories, open e.g. `人教版小学语文 / 二上 写字表 识字 1`, search finds a list.
- Commit: `feat: built-in library from bundled assets`, `feat: library browser with search`.

## Phase 4 — Dictation engine + system TTS ⬜

- [ ] `DictationEngine` (coroutine state machine): speak1 → 700 ms → speakMeaning → speak2 → interval countdown → next; speakMeaning = CJK single-char compound (always; Phase 5) / EN gloss only with 朗读释义 on; cancel via `Job.cancel()` + generation counter; speak1 failure → no retry; auto-next off holds after speak2; deadline re-read every tick (live interval changes); pause/resume; stop on exit. Defaults: interval 7 s (1–10, step 0.5), rate 0.9 (0.5–1.5), auto-next on.
- [ ] System `TextToSpeech` wrapper (`en-US`/`zh-CN` by entry kind) + DataStore-backed settings.
- [ ] Home: paste word list (draft persisted, 500 ms debounce, flushed on dispose) + start options (起始序号, 随机顺序 — slice then Fisher–Yates before navigation, not persisted) → start dictation; Dictation screen: countdown ring (announces remaining seconds), word hidden by default (tap to reveal), POS/meaning hint, 标记错词.
- Accept: full EN dictation (word → word) and 汉字 dictation (bare words) run by voice on device; wrong-word marking visible.
- Commit: `feat: dictation engine`, `feat: system tts`, `feat: dictation screen`, `feat: home paste import`.

## Phase 5 — 汉字组词朗读 ⬜

- [ ] `compounds.json` loader; port `cjkWordSpeech`: learned-first → frequency-ordered fallback, polyphone syllable matching (tone digits, `ü=v`, neutral passes), `NO_COMPOUND_HEADS` stoplist; speak `组词，X的X` pattern per original.
- [ ] Unit tests with real fixture chars: 月 (月亮), 长 (cháng/zhǎng), function char (的/了).
- Accept: single-char dictation speaks `X，组词的X`; 多音字 picks the reading-matching compound.
- Commit: `feat: cjk compound speech for char dictation` + tests.

## Phase 6 — Wrong words / history / favorites ⬜

- [ ] Room: `wrong_words`, `history` (cap 50, user lists only), `favorites`; DataStore drafts already in Phase 4.
- [ ] Finish state: score summary; 错词本 review flow (re-dictate wrong words); export wrong words to clipboard; history management (enriched text attach, delete, clear); favorites toggle in library/history.
- Accept: dictation → mark wrong → review re-runs exactly the wrong set; export puts a pasteable list on the clipboard.
- Commit: `feat: room persistence for wrong words history favorites`, `feat: finish screen and review flow`, `feat: history and favorites drawers`.

## Phase 7 — Youdao TTS + sound effects ⬜

- [ ] Youdao audio fetch (URL forms from AGENTS.md, UA spoof, 256-byte guard), disk cache `cacheDir/tts/`, single-flight, failure → system TTS; TTS source setting (youdao/system).
- [ ] `tick.wav` (last countdown second, vol 0.5) and `chime.wav` (session end, vol 0.6) via assets, sound on/off setting; haptics on marking (parity with original).
- Accept: dictation voices switch to Youdao when network available, falls back in airplane mode; tick/chime audible.
- Commit: `feat: youdao tts with cache and fallback`, `feat: ui sound effects`.

## Phase 8 — OCR import ⬜

- [ ] Photo Picker + camera capture; downscale ≤1600 px, JPEG 0.82; OpenAI-compatible vision call; verbatim EN/中文 prompts; reply → word-line parser; config screen: preset Zhipu glm-4v-flash + BYOK fields (baseUrl/apiKey/model), connection test; disclaimer at entry points; Chinese error surfacing with retry.
- Accept: photo of a textbook list imports as an editable word list (both languages demonstrated).
- Commit: `feat: ocr import via openai-compatible vision`, `feat: ocr settings`.

## Phase 9 — OpenAI-compatible TTS (optional) ⬜

- [ ] Provider config (`api: speech|chat`, baseUrl/apiKey/model, voiceEn/voiceZh, responseFormat), presets incl. Xiaomi MiMo (`chat` shape); binary `/audio/speech` and base64 `chat.completions` clip paths; clip cache keyed `provider|model|voice|rate|text`; full chain custom → youdao/system per AGENTS.md priority.
- Accept: dictation voiced by MiMo; disabling network/provider falls back without blocking.
- Commit: `feat: openai-compatible tts provider`, `feat: tts provider settings`.

## Phase 10 — Settings, theme, polish, release ⬜

- [ ] Settings screen consolidated (rate, interval, auto-next, 朗读释义, sound, TTS source/provider, OCR config, theme); light/dark Material 3; app icon + adaptive icon; empty-state/error-state polish; accessibility pass (content descriptions, touch targets — port the a11y fixes from alice commits).
- [ ] Release config: signing via gitignored `keystore.properties` pattern, `assembleRelease` with R8 minify; version code/name scheme.
- [ ] Dictionary load check (measure first, no pre-optimization): time cold start (`adb shell am start -W`) with the dictionary map loaded on first lookup (Dispatchers.IO, memory singleton — never parse on the startup path). Only if cold start exceeds 500 ms, convert the dictionary alone to prebuilt SQLite (Room `createFromAsset`); word lists remain assets.
- Accept: signed release APK installs and demos the full loop; dark mode coherent.
- Commit: `feat: settings consolidation`, `feat: app icon and theming`, `chore: release signing and minify`.

## Open decisions (not blocking)

- License: new repo licensing TBD (upstream alice is MIT; data assets carry their own provenance — ECDICT MIT, 教育部词表 public document). Decide before first public release.
- GitHub remote (`YangtseSu/HearWrite`) and Play/分发 channel: TBD.
- App icon design: placeholder until Phase 10.
