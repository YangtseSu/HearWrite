# 开发指南（构建 · 签名 · 打包）

面向开发者。架构、工具链版本钉与行为契约见 [`../AGENTS.md`](../AGENTS.md)；分阶段开发日志见 [`PHASES.md`](PHASES.md)（**归档冻结于 Phase 10**，不再更新）。本文记录：机器环境搭建、日常命令、以及**签名与打包发布**的完整流程。

## 1. 环境搭建（新机器）

工具链相关的一切（Gradle 9.7.1 wrapper、AGP 9.3.0、Kotlin 2.4.10、Compose BOM 2026.08.00）都已锁定在仓库内；只有三样东西是机器本地的：**JDK 21**、**Android SDK** 和 GitHub 认证。

### 1.1 克隆仓库

仓库为私有仓库——先完成认证：

```bash
gh auth login          # 或配置 SSH 密钥
gh repo clone YangtseSu/HearWrite && cd HearWrite
```

### 1.2 JDK 21

守护进程 JDK 的选择**不入库**（仓库的 `gradle.properties` 不提交 `org.gradle.java.home`），在用户级 `~/.gradle/gradle.properties` 固定——每台机器各自的设置：

```bash
# ~/.gradle/gradle.properties（本机已配置）
org.gradle.java.home=/usr/lib/jvm/java-21-openjdk
```

- Arch 系 Linux：`sudo pacman -S jdk21-openjdk`——路径一致，无需改动。
- 其他操作系统/发行版：路径不同（如 Debian/Ubuntu 上是 `java-21-openjdk-amd64`），改用户级配置里的路径即可；也可以用环境变量 `JAVA_HOME` 指向 JDK 21。

任何 JVM ≥ 21 都能运行守护进程；编译目标在 `app/build.gradle.kts` 中固定为 Java 21。

### 1.3 Android SDK

安装到 `~/Android/Sdk`（约 2 GB）。请使用 **Google 官方 cmdline-tools**——发行版自带的 `sdkmanager` 二进制携带过期的软件包索引，找不到 API 37 平台：

```bash
curl -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
mkdir -p /tmp/clt ~/Android/Sdk/cmdline-tools && unzip -q /tmp/clt.zip -d /tmp/clt
mv /tmp/clt/cmdline-tools ~/Android/Sdk/cmdline-tools/latest
yes | ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$HOME/Android/Sdk" --licenses
yes | ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$HOME/Android/Sdk" \
    "platforms;android-37" "platform-tools" "build-tools;36.0.0"
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # 已被 gitignore，机器本地文件
```

### 1.4 首次构建

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

无需系统安装 Gradle：wrapper 会一次性把锁定的 Gradle 9.7.1 下载到 `~/.gradle/wrapper/dists` 并缓存复用。发行包 URL 指向腾讯镜像，并锚定官方 SHA-256（部分网络无法访问 `services.gradle.org`）；其余依赖均从 Google Maven / Maven Central 解析。首次构建需要几分钟，之后都是增量构建。

### 1.5 在设备上运行

仓库不提交模拟器配置——演示在开启 USB 调试的真机上运行（实机调试的常见坑见 `AGENTS.md` 的 *adb device-driving notes*）：

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

## 3. 仓库结构

| 路径 | 用途 |
| --- | --- |
| `app/` | Android 应用（单一 `:app` 模块：`ui/` Compose 界面、`domain/` 纯 Kotlin 逻辑、`data/` 仓库与网络） |
| `data/` | 原始词表 + 内置数据资源（原样打包进 APK assets；**只读**，禁止手工重新生成） |
| `docs/` | 阶段日志归档（`PHASES.md`，冻结于 Phase 10）、本指南、README 截图（按需） |
| `scripts/` | 数据处理脚本（Python 3），按需添加 |
| `hearwrite.svg` | 应用图标设计源文件（自适应图标各层生成到 `app/src/main/res/`） |

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
# 产物：app/build/outputs/apk/release/app-release.apk
```

- Release 已开启 **R8 minify + 资源收缩**（`isMinifyEnabled` / `isShrinkResources`），零自定义 keep 规则（Room/OkHttp/Compose 的上游 consumer rules 足够）。
- 混淆映射在 `app/build/outputs/mapping/release/mapping.txt`——**每个发布版本归档一份**，用于反混淆崩溃堆栈。

### 4.4 验证与安装

```bash
~/Android/Sdk/build-tools/36.0.0/apksigner verify --print-certs \
    app/build/outputs/apk/release/app-release.apk    # 应显示 CN=HearWrite, O=Yangtse Su
adb install -r app/build/outputs/apk/release/app-release.apk
```

注意：debug 与 release 包同证书（见 4.2），`adb install -r` 可直接互相覆盖；仅当设备上的旧包由**别的证书**签出（如换过 keystore）时才需要 `adb uninstall org.yangtse.hearwrite`——卸载会清空 Room/DataStore 数据（错词本、历史、草稿）。

### 4.5 版本号规则

在 `app/build.gradle.kts` 的 `defaultConfig` 中维护：

- **`versionName`**：语义化版本 `MAJOR.MINOR.PATCH`；公开发布前处于 `0.x.y`（当前 `0.1.0`）。
- **`versionCode`**：单调递增整数，**每出一个签名发布包 +1，永不复用、不回退**（当前 `1`）。升级安装以它为准。

### 4.6 发布检查清单

1. `./gradlew :app:testDebugUnitTest :app:lintDebug` 全绿。
2. 按 4.5 提升 `versionCode`（发新版则同时改 `versionName`）。
3. `./gradlew :app:assembleRelease` → `apksigner verify` 确认签名。
4. 真机安装冒烟：导入词表 → 完整听写一轮 → 复习错词；切换深色主题检查无违和。
5. 归档 APK + `mapping.txt`，提交版本号改动，打 tag。

## 5. 开发约定

- `README.md` 与本指南使用中文（分别面向用户与维护者）；`AGENTS.md`、`docs/PHASES.md`、代码、注释与提交信息使用英文；所有应用内 UI 字符串硬编码中文（无 `strings.xml`）。
- 一次提交只做一件事（`feat:`/`fix:`/`docs:`/`data:`/`chore:`/`test:`），每个提交都必须可编译。
- AGP 9 使用**内置 Kotlin**——不要应用 `org.jetbrains.kotlin.android`；Kotlin 版本通过 Compose 编译器插件（`org.jetbrains.kotlin.plugin.compose`）锁定，KSP 需 ≥ 2.3.6。
- `data/` 资源只读；其余约定（架构分层、依赖清单、测试范围）以 `AGENTS.md` 为准。
