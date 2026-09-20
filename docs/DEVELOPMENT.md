# 开发指南（构建 · 签名 · 打包）

面向开发者。架构、工具链版本策略与行为契约见 [`../AGENTS.md`](../AGENTS.md)。本文记录：机器环境搭建、日常命令、以及**签名与打包发布**的完整流程。

## 1. 环境搭建（新机器）

工具链相关的一切（Gradle 9.7.1 wrapper、AGP 9.4.0、Kotlin 2.4.20、Compose BOM 2026.09.00）都已锁定在仓库内；只有三样东西是机器本地的：**JDK 26**、**Android SDK** 和 GitHub 认证。

### 1.1 克隆仓库

```bash
git clone https://github.com/YangtseSu/HearWrite.git && cd HearWrite
```

仓库公开，克隆无需认证；推送代码前完成 GitHub 认证即可（`gh auth login` 或 SSH 密钥）。

### 1.2 JDK 26

守护进程 JDK 的选择**不入库**（仓库的 `gradle.properties` 不提交 `org.gradle.java.home`），在用户级 `~/.gradle/gradle.properties` 固定——每台机器各自的设置：

```bash
# ~/.gradle/gradle.properties（本机已配置）
org.gradle.java.home=/usr/lib/jvm/java-26-openjdk
```

- Arch 系 Linux：`sudo pacman -S jdk-openjdk`（当前 latest = 26；升级 JDK 后同步改上面的路径，路径形如 `/usr/lib/jvm/java-26-openjdk`）。CI 与本地守护进程都用 JDK 26：CI 经 `actions/setup-java` 装 Temurin 26（`build.yml`/`release.yml`）。
- 其他操作系统/发行版：路径不同，改用户级配置里的路径即可；也可以用环境变量 `JAVA_HOME` 指向对应 JDK。

任何 JVM ≥ 21 都能运行守护进程；编译目标在 `app/build.gradle.kts` 中固定为 Java 21。

### 1.3 Android SDK

安装到 `~/Android/Sdk`（约 2 GB），使用最新版 cmdline-tools：

```bash
curl -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
mkdir -p /tmp/clt ~/Android/Sdk/cmdline-tools && unzip -q /tmp/clt.zip -d /tmp/clt
mv /tmp/clt/cmdline-tools ~/Android/Sdk/cmdline-tools/latest
```

已有旧版（如 20.0）时，直接替换 `cmdline-tools/latest/` 即完成升级——cmdline-tools 自包含，换目录无副作用：

```bash
yes | ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$HOME/Android/Sdk" --licenses
yes | ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$HOME/Android/Sdk" \
    "platforms;android-37.2" "platform-tools" "build-tools;37.0.0"
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # 已被 gitignore，机器本地文件
```

- 本章安装 `platforms;android-37.2`（最新稳定平台，`app/build.gradle.kts` 的 `compileSdk` 块选中该次版本）；`build-tools;37.0.0` 为最新稳定版，可省略——不写时 AGP 用自带默认版本（当前 36.0.0）。

### 1.4 首次构建

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

无需系统安装 Gradle：wrapper 会一次性把 Gradle 9.7.1（当前版本，wrapper 文件锁 SHA-256 防篡改）下载到 `~/.gradle/wrapper/dists` 并缓存复用（默认从官方 `services.gradle.org` 下载；若官方地址不可达——例如国内网络——把 `gradle-wrapper.properties` 注释中的腾讯镜像 URL 换上去即可）。其余依赖均从 Google Maven / Maven Central 解析。首次构建需要几分钟，之后都是增量构建。

### 1.5 在设备上运行

仓库不提交模拟器配置。**先查本机有没有可用的 AVD**：

```bash
~/Android/Sdk/emulator/emulator -list-avds        # 列出 ~/.android/avd/ 下的全部 AVD
```

有输出就启动其中一个（本机模拟器为 API 37 / x86_64 / google_apis），没有输出才回到真机：

```bash
~/Android/Sdk/emulator/emulator -avd <AVD 名> &    # 名字用上一步 -list-avds 列出的
adb wait-for-device                              # 启动等到 boot_completed 才截图/输入
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

模拟器覆盖不依赖真实硬件的验证：Room 迁移 instrumentation 测试（`./gradlew :app:connectedDebugAndroidTest`）、进程死亡恢复、UI 走查。**迁移测试必须在应用的 SQLite 下限上跑一次**——框架 SQLite 版本随 API 等级走（API 33 → 3.32、API 34 → 3.39），而迁移的正确性取决于最老的那个引擎：`MIGRATION_4_5` 曾用 `ALTER TABLE … DROP COLUMN`（要 3.35），在 API 33 上直接抛错、库再也打不开，而当时 CI 跑在 API 36 上全绿（CI 现已固定为 **API 33**）。本机只有一个 API 37 的 AVD 时，用 `sdkmanager "system-images;android-33;google_apis;x86_64"` + `avdmanager create avd -n <名字> -k "system-images;android-33;google_apis;x86_64"` 临时建一个。音频焦点 / 来电中断 / 各家 TTS 音色仍必须真机验证——模拟器常常没有可用的 TTS 引擎与音色。

真机则直接（实机调试的常见坑见 §7）：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 2. 日常命令

| 命令 | 用途 |
| --- | --- |
| `./gradlew :app:assembleDebug` | 构建 Debug APK |
| `./gradlew :app:testDebugUnitTest` | 单元测试（domain 逻辑门禁） |
| `./gradlew :app:lintDebug` | Android lint |
| `./gradlew :app:assembleRelease` | 构建签名 Release APK（见下节） |
| `python3 scripts/generate-compounds.py` | 重新生成 `compounds/compounds.json`（输入：`scripts/data/` 频率表 + `app/src/main/assets/人教版小学语文/`） |
| `python3 scripts/build-lexicon.py` | 重新生成 `dict/lexicon-en.json`（首次自动下载 ECDICT csv 与 ipa-dict 到 `.cache/`；仁爱音标读 `scripts/data/renai-ipa.tsv`） |
| `python3 scripts/build-hanzi-lexicon.py` | 重新生成 `dict/lexicon-hanzi.json`（输入：词表汉字行 + `scripts/data/` 频率表与人工 override） |

## 3. 仓库结构

| 路径 | 用途 |
| --- | --- |
| `app/` | Android 应用（单一 `:app` 模块：`ui/` Compose 界面、`domain/` 纯 Kotlin 逻辑、`data/` 仓库与网络）；词表与内置资源本体在 `app/src/main/assets/`（原样打包，**只读**，禁止手工重新生成） |
| `docs/` | 文档与设计源：本指南；`WORDLIST.md`（词库数据规范与贡献指南）；`TTS.md`（发音链路协议/音色/缓存）；`OCR.md`（拍照识词与拍照批改）；`ROADMAP.md`（**只记未完成项**）；`ERRATA.md`（已发布提交信息的更正）；`implemented/`（归档文档：`2026-09-12-UI-AUDIT.md`、`2026-09-18-DATA-MODEL.md`（行/词典分离 + 音标 spec，规则已提炼进 AGENTS.md / WORDLIST.md）、`2026-09-18-ROADMAP-DONE.md`、`2026-09-20-REVIEW-0.9.0.md`（`v0.8.0..HEAD` 提交复核）、`2026-09-04-PHASES.md`）；README 截图（按需）；图标设计源 `hearwrite.svg`（自适应图标各层由它生成到 `app/src/main/res/`） |
| `scripts/` | 数据再生成工具与源：`generate-compounds.py`（组词表）、`build-lexicon.py` / `build-hanzi-lexicon.py`（两份词典表）、`extract-renai-ipa.py`（仁爱教材音标，一次性）、`check-assets.py`（资产树校验，CI 门禁）、`import-wordlist.py`（外部词表规范化）、`verify-kebiao-scan.py`（课标字表 vs 影印件位次核对）、`scripts/data/` 频率表与音标源（均不随 APK 打包） |

## 4. 签名与打包发布

Release 包使用**仓库根目录的 gitignored `keystore.properties`** 签名（模板：[`keystore.properties.example`](../keystore.properties.example)）。`keystore.properties`、`*.jks`、`*.keystore` 均已 gitignore——**签名材料永不入库**。

### 4.1 生成密钥（每台发布机一次）

```bash
keytool -genkeypair -v -keystore hearwrite-release.jks -alias hearwrite \
    -keyalg RSA -keysize 4096 -validity 10000
```

- `-validity 10000`：Google Play 要求证书有效期覆盖到 2033 年之后。
- **务必备份 `hearwrite-release.jks` 与口令**：密钥丢失后无法对已安装用户升级同一应用（签名不一致只能卸载重装）。

### 4.2 写入 keystore.properties（不入库）

复制模板并填入真实值：

```bash
cp keystore.properties.example keystore.properties
```

```properties
storeFile=hearwrite-release.jks     # 相对仓库根目录解析，也可写绝对路径
storePassword=…
keyAlias=hearwrite
keyPassword=…
```

`app/build.gradle.kts` 的行为：文件存在 → 创建 `release` 签名配置，同时应用于 **release 与 debug 构建类型**（本地 debug 包与正式发布包同证书，`adb install -r` 可互相覆盖升级，不会因签名不一致被迫卸载丢数据）；文件不存在 → Release 构建保持未签名（有意设计：发布包必须用发布密钥签名），debug 退回默认 debug 密钥。

### 4.3 打包

```bash
./gradlew :app:assembleRelease
# 原始产物：app/build/outputs/apk/release/app-release.apk（AGP 默认名，保留不动）
./gradlew :app:packageVersionedRelease
# 规范命名发布件（CI 也走这一步）→ app/build/dist/：
#   HearWrite-<versionName>.apk               # 例 HearWrite-0.3.0.apk
#   HearWrite-<versionName>-mapping.txt       # 例 HearWrite-0.3.0-mapping.txt
```

- Release 已开启 **R8 minify + 资源收缩**（`isMinifyEnabled` / `isShrinkResources`），零自定义 keep 规则（Room/OkHttp/Compose 的上游 consumer rules 足够）。
- `packageVersionedRelease` 只是把签名 APK 与 `mapping.txt` 复制成带版本号的规范名（分发 / 归档用），输出到独立目录 `app/build/dist/`——AGP 独占 `build/outputs/`，写回会触发 Gradle 任务输出重叠校验。**分发与归档一律用 `HearWrite-<versionName>` 系列文件。**
- 混淆映射原始文件在 `app/build/outputs/mapping/release/mapping.txt`——**每个发布版本归档一份**（即规范名 `HearWrite-<versionName>-mapping.txt`），用于反混淆崩溃堆栈。

### 4.4 验证与安装

```bash
~/Android/Sdk/build-tools/37.0.0/apksigner verify --print-certs \
    app/build/dist/HearWrite-0.3.0.apk    # 应显示 4.1 keytool 生成密钥时填写的 CN/O
adb install -r app/build/dist/HearWrite-0.3.0.apk
```

注意：debug 与 release 包同证书（见 4.2），`adb install -r` 可直接互相覆盖；仅当设备上的旧包由**别的证书**签出（如换过 keystore）时才需要 `adb uninstall org.yangtse.hearwrite`——卸载会清空 Room/DataStore 数据（错词本、历史、草稿）。

### 4.5 版本号规则

在 `app/build.gradle.kts` 的 `defaultConfig` 中维护：

- **`versionName`**：语义化版本 `MAJOR.MINOR.PATCH`；公开发布前处于 `0.x.y`（当前 `0.9.0`）。
- **`versionCode`**：单调递增整数，**每出一个签名发布包 +1，永不复用、不回退**（当前 `11`）。升级安装以它为准。

### 4.6 发布检查清单

1. `./gradlew :app:testDebugUnitTest :app:lintDebug` 全绿。
2. 按 4.5 提升 `versionCode`（发新版则同时改 `versionName`）。
3. 在 `CHANGELOG.md` 追加本版中文小节（`## [x.y.z]` 与 tag 对应）——仓库走 direct-to-main、无 PR，GitHub 自动 notes 只剩空 compare 链接，Release 正文靠此小节生成，缺失则 workflow hard fail。
4. `./gradlew :app:assembleRelease` → `apksigner verify` 确认签名。
5. 真机安装冒烟：导入词表 → 完整听写一轮 → 复习错词；切换深色主题检查无违和。
6. `packageVersionedRelease` 产物（`HearWrite-<versionName>.apk` + `HearWrite-<versionName>-mapping.txt`）归档，提交版本号改动，打 tag。

## 5. 开发约定

- `README.md` 与本指南使用中文（分别面向用户与维护者）；`AGENTS.md`、代码、注释与提交信息使用英文；所有应用内 UI 字符串硬编码中文（无 `strings.xml`）。
- 一次提交只做一件事（`feat:`/`fix:`/`docs:`/`data:`/`chore:`/`test:`），每个提交都必须可编译。
- AGP 9 使用**内置 Kotlin**——不要应用 `org.jetbrains.kotlin.android`；Kotlin 版本通过 Compose 编译器插件（`org.jetbrains.kotlin.plugin.compose`）设定，KSP 随 Kotlin 版本联动（一起升）。
- `data/` 资源只读；其余约定（架构分层、依赖清单、测试范围）以 `AGENTS.md` 为准。

### 5.1 CI 与 main 分支治理

- **触发**（`.github/workflows/build.yml`）：`pull_request`（含 fork）+ `push` 仅 `main`。逐分支 push 曾让每个 PR 提交把两个 job 各跑两遍（两个云模拟器）；tag push 不再触发 CI——Release 工作流会在该 tag 上构建并 `apksigner` 验签。
- **检查项**：`Build, unit tests & lint`（含 `scripts/check-assets.py` 数据门禁）与 `Instrumented tests (emulator)`。作业名**就是**分支保护的必需上下文，两边必须逐字一致。
- ⚠️ **不要给 `instrumented` job 加 `strategy.matrix`**：矩阵会让 GitHub 上报 `<作业名> (<矩阵值>)`，没有任何必需上下文能匹配，main 会对所有没有 admin 绕过的人永久不可合并。将来真要多 API 级别，用汇总 job 保住上下文不变。
- `Instrumented tests (emulator)` 的 **`api-level` 固定为 33**（应用 `minSdk` 下限 = 应用会遇到的最老 SQLite）。调高它会让"只在较新 SQLite 上成立"的迁移重新蒙混过关，见 §1.5 与 `MIGRATION_4_5` 的 KDoc。
- **保护分两层并存**，各管一半：
  - **经典分支保护** — 两项必需检查（`strict`，要求分支与 base 同步后再合并）。`enforce_admins: false`：管理员（目前只有作者）直接 push main 时绕过，**协作者与 PR 仍受门禁约束**；PR 必须两项检查通过才能合并。
  - **规则集 `main：禁止强推与删除`**（`bypass_actors: []`）— 禁止 force push 与删除 main，**对管理员同样生效**。经典保护无法这样拆分：它的 `allow_force_pushes`/`allow_deletions` 也会随 `enforce_admins: false` 一并豁免管理员。
- **核对生效规则**：`gh api repos/YangtseSu/HearWrite/rules/branches/main`（ruleset 与经典保护的合集，各规则的 `ruleset_source_type` 标明来源）。
- **加协作者给 `write`，不要给 `admin`**：GitHub 只按角色豁免，不按用户名；admin 会继承上一条绕过。

## 6. BYOK API Key 的加密存储（Android Keystore）

TTS / OCR 服务商密钥写入 DataStore 前经 `data/KeystoreCipher.kt` 用 **Android Keystore** 里的 AES-256-GCM 密钥加密（密文格式 `v1.<iv-b64>.<ct-b64>`，每次加密随机 IV，128 位认证标签）。密钥材料在 Keystore 芯片内生成、永不导出；读取时解密。要点：

- **旧数据兼容**：0.3.2 之前明文存储的 key 不是 `v1.` 形状 → 原样透传照常工作，下次「保存并启用」时自动转为密文。
- **Keystore 失效**（恢复/迁移后 DataStore 幸存而 keystore 条目丢失、锁屏凭据变更等）：无法解密的密文解析为 `""` → 配置显示为空、重新输入即可，绝不发送乱码密钥。
- **JVM 单测**：Android Keystore 在 JVM 上不存在，测试用内存 AES-GCM 假实现走 `SecretCipher` 接缝（`ProviderConfigSealTest`）验证封印/解封/明文透传契约。
- **数据备份排除**：`data_extraction_rules.xml` 已排除 `datastore/` 与数据库，密钥密文不随云备份/设备迁移外流（README「所有数据只保存在本机」）。
- 加密是尽力而为的静态防护：Keystore 由系统锁屏凭据保护，`adb backup`/root 读取 DataStore 只能拿到密文；应用进程内运行时仍需明文密钥发请求。

## 7. 真机调试：adb 驱动要点

每次验收都要在**真实界面**上跑一遍（模拟器或真机，见 §1.5），用 `adb` 驱动。以下每条都真的坑过人：

- **等 UI，不要只等设备**：`adb wait-for-device` 在界面起来之前就返回（冷启动要一两分钟）。截图或注入输入前先等 `adb shell getprop sys.boot_completed`（或匹配 `[0-9]` 的 `sys.boot_completed=1`），否则拍到的是黑屏。
- **屏幕会睡**：黑屏截图、`uiautomator dump` 为空、`input text` 悄无声息——多半是屏幕超时，不是崩溃。先看 `adb shell dumpsys window | grep mCurrentFocus` 与 logcat 再排查；长时间驱动期间用 `adb shell svc power stayon true` 保持唤醒，结束后 `adb shell svc power stayon false` 恢复。
- **默认输入法是中文键盘**：zh-CN 设备上 Gboard 拼音模式会拦下 `adb shell input text`——注入的 ASCII 落进候选缓冲，永远到不了输入框。先禁用它（`adb shell ime disable <ime-id>`，id 从 `adb shell ime list -s` 取），让输入走硬件键盘通路，用完 `adb shell ime enable <ime-id>` 还原。
- **滑动方向**：`adb shell input swipe x y1 x y2` 从顶部区域**向下**拖会拉出通知栏/锁屏，后续输入还会被吃掉——列表滚动一律从下往上（例如 `540 1900 540 500`）。
- **`unzip -l` 会把中文资产名显示成乱码**（终端字符集解码所致），并且 `grep -c 'assets/.*\.txt$'` 数出 0——但条目本身是正确的 UTF-8。验证打包结果请用 Python：

  ```bash
  python3 -c "import zipfile; z=zipfile.ZipFile('app/build/outputs/apk/debug/app-debug.apk'); print([n for n in z.namelist() if n.startswith('assets/')])"
  ```
