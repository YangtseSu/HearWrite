# HearWrite 听写

An Android dictation trainer for Chinese students: paste or photograph a word list → the app speaks each word with a countdown → the student writes it down → mark wrong words → review later. Native Kotlin + Jetpack Compose, rewritten from scratch (behavioral parity only) from the [alice](https://github.com/YangtseSu/alice) React Native app.

- Architecture, toolchain pins, and behavioral contract: [`AGENTS.md`](AGENTS.md)
- Phase plan and progress: [`PROGRESS.md`](PROGRESS.md)
- License: GPL-3.0-or-later (see [`LICENSE`](LICENSE))

## Development setup (new machine)

Everything toolchain-related (Gradle, AGP, Kotlin, Compose BOM) is pinned inside the repo; only three things are machine-local: **JDK 21**, the **Android SDK**, and GitHub auth.

### 1. Clone

The repo is private — authenticate first:

```bash
gh auth login          # or set up an SSH key
gh repo clone YangtseSu/HearWrite && cd HearWrite
```

### 2. JDK 21

`gradle.properties` pins the Gradle daemon to `org.gradle.java.home=/usr/lib/jvm/java-21-openjdk`.

- Arch-based Linux: `sudo pacman -S jdk21-openjdk` — the path matches, nothing to change.
- Any other OS/distro: the path differs (e.g. `java-21-openjdk-amd64` on Debian/Ubuntu). Edit that one line to the local JDK 21 path and **keep the change uncommitted** (it is a per-machine setting).

Any JVM ≥ 21 works for running the daemon; the compile target is pinned at Java 21 in `app/build.gradle.kts`.

### 3. Android SDK

Install to `~/Android/Sdk` (~2 GB). Use **Google's official cmdline-tools** — distro-packaged `sdkmanager` binaries ship a stale package index that cannot find API 37 platforms:

```bash
curl -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
mkdir -p /tmp/clt ~/Android/Sdk/cmdline-tools && unzip -q /tmp/clt.zip -d /tmp/clt
mv /tmp/clt/cmdline-tools ~/Android/Sdk/cmdline-tools/latest
yes | ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$HOME/Android/Sdk" --licenses
yes | ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$HOME/Android/Sdk" \
    "platforms;android-37" "platform-tools" "build-tools;36.0.0"
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # gitignored, machine-local
```

### 4. Build

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

No system Gradle needed: the wrapper downloads the pinned Gradle 9.7.1 once into `~/.gradle/wrapper/dists` and caches it for all projects. The distribution URL points at the Tencent mirror pinned to the official SHA-256 (`services.gradle.org` is unreachable from some networks); all other dependencies resolve from Google Maven / Maven Central. First build takes a few minutes, later ones are incremental.

### 5. Run on a device

There is no committed emulator setup — demos run on a real device with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Daily commands

| Command | Purpose |
| --- | --- |
| `./gradlew :app:assembleDebug` | Debug APK |
| `./gradlew :app:testDebugUnitTest` | Unit tests (domain logic gate) |
| `./gradlew :app:lintDebug` | Android lint |

## Repo layout

| Path | Purpose |
| --- | --- |
| `app/` | The Android application (single `:app` module) |
| `data/` | Source word lists + bundled data assets (shipped verbatim in APK assets; read-only, never regenerate by hand) |
| `scripts/` | Data tooling (Python 3), added when needed |
| `hearwrite.svg` | App icon design source (adaptive icon layers are generated into `app/src/main/res/`) |

## Conventions

- Code, comments, and commit messages in English; all user-facing UI strings hardcoded Chinese (no `strings.xml`).
- One thing per commit (`feat:`/`fix:`/`docs:`/`data:`/`chore:`/`test:`), every commit compiles.
- AGP 9 runs **built-in Kotlin** — do not apply `org.jetbrains.kotlin.android`; the Kotlin version is pinned via the Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`).
