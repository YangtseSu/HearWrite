# HearWrite 听写

面向中国学生的 Android 听写训练应用：粘贴或拍照录入词表 → 应用逐词朗读并倒计时 → 学生书写 → 标记错词 → 之后复习。原生 Kotlin + Jetpack Compose 实现，从 [alice](https://github.com/YangtseSu/alice)（React Native 应用）从头重写（仅保持行为一致）。

- 架构、工具链版本与行为契约：[`AGENTS.md`](AGENTS.md)
- 分阶段计划与进度：[`PROGRESS.md`](PROGRESS.md)
- 许可证：GPL-3.0-or-later（见 [`LICENSE`](LICENSE)）

## 开发环境搭建（新机器）

工具链相关的一切（Gradle、AGP、Kotlin、Compose BOM）都已锁定在仓库内；只有三样东西是机器本地的：**JDK 21**、**Android SDK** 和 GitHub 认证。

### 1. 克隆仓库

仓库为私有仓库——先完成认证：

```bash
gh auth login          # 或配置 SSH 密钥
gh repo clone YangtseSu/HearWrite && cd HearWrite
```

### 2. JDK 21

`gradle.properties` 将 Gradle 守护进程固定到 `org.gradle.java.home=/usr/lib/jvm/java-21-openjdk`。

- Arch 系 Linux：`sudo pacman -S jdk21-openjdk`——路径一致，无需改动。
- 其他操作系统/发行版：路径不同（如 Debian/Ubuntu 上是 `java-21-openjdk-amd64`）。把那一行改成本地 JDK 21 路径，并**保持该改动不入库**（这是每台机器各自的设置）。

任何 JVM ≥ 21 都能运行守护进程；编译目标在 `app/build.gradle.kts` 中固定为 Java 21。

### 3. Android SDK

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

### 4. 构建

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

无需系统安装 Gradle：wrapper 会一次性把锁定的 Gradle 9.7.1 下载到 `~/.gradle/wrapper/dists`，并缓存供所有项目复用。发行包 URL 指向腾讯镜像，并锚定了官方 SHA-256（部分网络无法访问 `services.gradle.org`）；其余依赖均从 Google Maven / Maven Central 解析。首次构建需要几分钟，之后都是增量构建。

### 5. 在设备上运行

仓库未提交模拟器配置——演示在开启 USB 调试的真机上运行：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 日常命令

| 命令 | 用途 |
| --- | --- |
| `./gradlew :app:assembleDebug` | 构建 Debug APK |
| `./gradlew :app:testDebugUnitTest` | 单元测试（domain 逻辑门禁） |
| `./gradlew :app:lintDebug` | Android lint |

## 仓库结构

| 路径 | 用途 |
| --- | --- |
| `app/` | Android 应用（单一 `:app` 模块） |
| `data/` | 原始词表 + 内置数据资源（原样打包进 APK assets；只读，禁止手工重新生成） |
| `scripts/` | 数据处理脚本（Python 3），按需添加 |
| `hearwrite.svg` | 应用图标设计源文件（自适应图标各层生成到 `app/src/main/res/`） |

## 约定

- README.md 使用中文撰写（面向中文读者）；`AGENTS.md` 等其他文档、代码、注释与提交信息使用英文；所有面向用户的界面字符串硬编码为中文（无 `strings.xml`）。
- 一次提交只做一件事（`feat:`/`fix:`/`docs:`/`data:`/`chore:`/`test:`），每个提交都必须可编译。
- AGP 9 使用**内置 Kotlin**——不要应用 `org.jetbrains.kotlin.android`；Kotlin 版本通过 Compose 编译器插件（`org.jetbrains.kotlin.plugin.compose`）锁定。
