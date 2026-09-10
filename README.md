# HearWrite 听写
[![Vibe Coded](https://img.shields.io/badge/vibe--coded-%F0%9F%A4%96-8A2BE2)](#关于开发方式)
[![Release](https://img.shields.io/github/v/release/YangtseSu/HearWrite?sort=semver&logo=android&logoColor=3DDC84&label=%E6%9C%80%E6%96%B0%E7%89%88)](https://github.com/YangtseSu/HearWrite/releases/latest)
[![CI](https://img.shields.io/github/actions/workflow/status/YangtseSu/HearWrite/build.yml?branch=main&label=CI)](https://github.com/YangtseSu/HearWrite/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/YangtseSu/HearWrite)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-13%2B_(API_33%2B)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)

面向中国学生的 Android 听写训练应用：导入词表 → 应用逐词朗读并倒计时 → 学生在本子上默写 → 标记错词 → 之后复习错词。
原生 Kotlin + Jetpack Compose 实现，无账号、无广告、无内购，所有数据只保存在本机。

## 功能

- **两种听写模式**：英文（单词 → 释义 → 单词）与汉字（生字 → 组词 → 生字，如「月 → 月亮的月 → 月」，自动处理多音字）。
- **内置词库**：中考1600、高考3500、初中2182、人教版/外研版/闽教版/仁爱版等 10 套教材共 403 份词表，支持按词表名或单词搜索；词表预览页可直接开始听写（同样支持随机顺序与起始序号），或一键载入草稿继续编辑。
- **灵活导入**：粘贴输入（每行一个词）；拍照识词——拍课本词表可先框选识别区域，AI 识别英文词表或汉字生字/词语（识别结果可编辑后再开始）。
- **离线词典补全**：英文词表自动补全词性与中文释义（内置 ECDICT 离线词典，不联网也能用）。
- **四档发音**：有道词典真人发音（默认，需联网）／微软 Edge 在线神经网络语音（免费、无需 API Key，可选音色）／系统语音（完全离线）／自定义 OpenAI 兼容音源（自备 API Key，预设小米 MiMo（免费）与自定义，各服务商配置独立保存）；断网或失败时自动回退，听写永不中断。
- **错词本**：听写中一键标记，结束页显示成绩与错词，可直接复习错词、导出错词到剪贴板或点按移除；错词跨次听写累积，首页「更多」菜单可随时查看、逐词移除或清空。
- **听写统计**：每场完整听写自动记录（场次、词数、错词率、用时）；统计页显示累计数据、连续天数、最近 14 天词数趋势、高频错词与最近记录。全部只存本机。
- **历史与收藏**：自动保存最近 50 条粘贴导入的词表，常用词表可收藏，随时一键再次听写。
- **播放控制**：语速、间隔（1–10 秒）、自动播报下一词、朗读英文释义，听写中可实时调整；倒计时最后一秒滴答声、完成提示音、标记错词震动。
- **深色 / 浅色主题**（可跟随系统）。

## 下载安装

- 从 GitHub [Releases 最新版](https://github.com/YangtseSu/HearWrite/releases/latest) 页面下载 APK 安装（页面顶部 Release 资产即当前最新版；文件名形如 `HearWrite-0.4.0.apk`，随版本变化）。需允许安装未知来源应用。
- 也可以自行构建，见下「从源码构建」。

要求 Android 13（API 33）及以上：覆盖面与维护成本的平衡点——覆盖 2022 年后绝大多数在售设备，同时让新依赖与 API 不被旧版本拖累（应用无需系统升级即持续保持最新特性）。

### 从源码构建

```bash
git clone https://github.com/YangtseSu/HearWrite.git && cd HearWrite
./gradlew :app:assembleDebug       # Debug APK → app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest   # 单元测试（domain 逻辑门禁）
```

前置条件：**JDK 21+** 与 **Android SDK**（含 `platforms;android-37`；环境搭建见 [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) §1）。签名材料不入库：无 `keystore.properties` 时 Debug 包用默认调试签名，Release 构建保持未签名（签名与发布流程见 DEVELOPMENT.md §4）。

## 快速上手

1. **导入词表**（首页）：直接粘贴；点「英文示例」「汉字示例」体验格式；或点「拍照识词」拍课本词表（可先框选识别区域、选英文或汉字识别）；或从「更多」菜单进「词库」选一份内置词表。
2. **听写**：点「开始听写」。每词朗读两遍，词盘中的词语默认隐藏（学生先默写），点按显示；最后一秒倒计时结束自动播下一词；写错了点「标记错词」。
3. **复习**：结束后在成绩卡点「复习错词」只重听错词；「导出错词」把错词复制成词表，粘贴回首页即可再练；首页「更多」菜单的错词本也能随时复习。

首页与词表预览页都可以设置**起始序号**（从第 N 个词开始）与**随机顺序**（打乱词序，仅本次有效）。

## 词表格式

每行一个词，支持以下写法（竖线全角 `｜` 半角 `|` 均可）：

```
apple | n. | 苹果        ← 英文：单词 | 词性 | 释义（可只有单词）
月 | yuè | 月亮          ← 生字：生字 | 拼音（带声调） | 组词（需含本字）
香蕉                     ← 词语：直接一行
you're = you are         ← 只朗读左侧
```

## 设置说明

| 设置 | 说明 |
| --- | --- |
| 外观 | 主题：跟随系统 / 浅色 / 深色 |
| 语速 | 0.5–1.5（默认 0.9），立即生效 |
| 朗读释义 | 英文词朗读后跟读中文释义 |
| 间隔与自动播报 | 听写节奏；在首页播放面板与听写页随时调整，自动保存 |
| 发音来源 | 有道词典（默认，需联网；失败自动回退系统语音）／微软 Edge（免费、无需 API Key，可选中文/英文音色；失败自动降级）／系统语音（完全离线）／TTS API（OpenAI 兼容，自备 Key；预设小米 MiMo（免费）与自定义，各服务商配置独立保存） |
| 提示音 | 倒计时滴答与完成提示音开关 |
| 清空发音缓存 | 删除已下载的发音文件，之后需要时自动重新下载 |
| 拍照识词 | 识别服务：OpenAI 兼容视觉接口，需自备 API Key；默认预设智谱 `glm-4v-flash`（免费），另有 OpenCode Zen、Vercel、Command Code、OpenRouter 预设，或自定义任意接口（本机回环接口亦可，如 Ollama 需 `adb reverse tcp:11434`） |

**API Key 只保存在本机**（应用私有存储，经 Android Keystore 加密后写入；个别设备缺少安全存储硬件时会提示并以明文降级保存），仅用于向对应服务商发请求；本应用没有后端服务器，不上传任何词表或听写记录。AI 识图可能存在误差，请核对识别结果。

## 权限说明

- 网络：在线发音（有道词典 / 微软 Edge / 自定义音源）与拍照识词请求。
- 震动：标记错词时的震动反馈。
- 拍照识词**不申请相机权限**：拍摄走系统相机，选图走系统相册选择器。

## 鸣谢

- [vvenv/alice](https://github.com/vvenv/alice)
- [rany2/edge-tts](https://github.com/rany2/edge-tts)
- [skywind3000/ECDICT](https://github.com/skywind3000/ECDICT)
- [liangqi/chinese-frequency-word-list](https://github.com/liangqi/chinese-frequency-word-list)

## 相关文档

- [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) — 开发者：环境搭建、构建、**签名与打包发布**
- [`AGENTS.md`](AGENTS.md) — 架构、工具链版本与行为契约（开发者）

## 关于开发方式

本项目在 AI 编码智能体（Oh My Pi）辅助下开发：人类负责产品需求、交互设计与验收，代码主要由 AI 生成，并经单元测试与真机验证。AI 参与的提交在标题末尾带 🤖 标记。使用中如遇问题，欢迎提 [issue](https://github.com/YangtseSu/HearWrite/issues)。

GPL-3.0-or-later，见 [`LICENSE`](LICENSE)。
