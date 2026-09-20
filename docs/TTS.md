# TTS 朗读链路（发音来源 · 音色 · 缓存）

本文是 [`AGENTS.md`](../AGENTS.md) *TTS priority chain* 一节的展开：四条发音来源的协议、音色选择、缓存键与失败降级。规则（哪条是默认、谁是兜底、组词短语走哪条）以 AGENTS.md 为准，本文补充"怎么接线、怎么失效、怎么修"。

## 0. 统一契约（`Speaker`）

- `suspend fun speak(text, lang): Boolean` + `stop()`；失败返回 `false`，**绝不向调用方抛异常**。
- 三条网络来源（Youdao / 微软 Edge / 自定义 OpenAI 兼容）是**平级 peer**：当前来源只播放自己的缓存片段，绝不回落到别人的缓存（残留的 Youdao 片段不能在 Edge/自定义下冒出来）。
- `system.speak` 是**唯一兜底**：任何一环失败都继续落到系统语音，听写不会因下载或断网而无限阻塞。
- 冷启动未命中缓存时等待**有界**时间（≈4 s，与该来源自身的单飞下载合流）再兜底，保证一场听写只有一个音色；该等待可取消。
- 预取在后台进行：当前词、下一个词、当前行的释义遍（英文释义；`EDGE`/`CUSTOM` 下的组词短语）。**Youdao 下不预取组词短语**——它就是词典单词音，读不了句子，`"月亮的月"` 直接交给系统 zh-CN，不发网络请求；Edge/自定义下该短句按本来源的音色走链路，也在本来源预取。

## 1. Youdao（默认）

- CJK：`GET https://dict.youdao.com/dictvoice?audio=<urlencoded>&le=zh`。
- 英文：先 `&type=2`，失败再 `&type=1`（`type` 两个取值都读不了中文，所以中文必须走 `le=zh`）。
- 发送移动浏览器 `User-Agent`；**响应小于 256 字节视为失败**（该接口失败时会返回一个极短的 JSON 错误体）；MP3 缓存到 `cacheDir/tts/`，键 = 文本 + 语言；同一文本单飞。
- 实测（2026-09-04，curl，同一 URL + UA）：`月` / `月亮` → `200 audio/mpeg`，MP3 合法（0.43 s / 0.62 s）；`月亮的月` → `HTTP 500 application/json` + `{"msg":"returned null audio"}`——这是**逐词的词典音**，整句会被直接拒绝。组词短语因此在本来源下固定走系统语音。

## 2. 微软 Edge Read-Aloud（keyless）

### 2.1 客户端与协议

**协议是 vendored，不是重写**：`app/src/main/java/io/edge/EdgeTts.kt` 是独立 Kotlin 客户端（okhttp/okio + coroutines + `org.json`，不含任何 Android API），对齐 Edge 朗读背后的服务当前协议——`wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1`，一个片段一次 WebSocket 会话。

自研版本 2026-09 已退役：服务会**静默丢连接**（自研实现被丢、vendored 客户端照常可用）。因此这里的规矩是：**不要重新实现协议**，只同步 vendored 文件。

文件内已覆盖：`TrustedClientToken`、`Sec-MS-GEC`（`<%.0f winTicks><token>` 的 SHA-256，winTicks = unix + 11644473600 向下取整到 300 s 窗口 ×10⁷，大写十六进制）、`Sec-MS-GEC-Version`、整套 Edge/Chromium 头（UA、`Accept-Encoding: gzip, deflate, br, zstd`、`Pragma`/`Cache-Control`、`Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold`、`Sec-WebSocket-Version: 13`、每次连接新生成的 32 位十六进制 `muid` cookie）、`Path:speech.config`（outputFormat `audio-24khz-48kbitrate-mono-mp3`）→ `Path:ssml`（按语言选音色，prosody 控制 rate/pitch/volume）→ 二进制帧（2 字节大端头长 + `Path:audio`/`Content-Type:audio/mpeg` 头块，头块末尾的 `\r\n` 计入长度，MP3 数据紧随其后）→ `Path:turn.end`；长文本按 4096 字节切块，每块单独一条连接。**403 且带 `Date` 头时按钟差校正**的逻辑也在文件内。

### 2.2 维护仪式

微软多次破坏过这个接口（2023 的 Sec-MS-GEC 时代；2025-12 的 MUID/UA/分块变更）。**接口挂掉时**：

1. 把 `io/edge/EdgeTts.kt` 与独立参考实现同步——[edge-tts-kotlin](https://github.com/YangtseSu/edge-tts-kotlin)（上游 [rany2/edge-tts](https://github.com/rany2/edge-tts) 是 **LGPLv3**，vendored 文件里带着 provenance 声明）。
2. 在真机上重新验证（模拟器常无可用 TTS 引擎）。

### 2.3 音色选择

- 仅在选中「微软 Edge」时，发音来源页显示两个下拉：**中文音色**（zh-CN）与**英文音色**（en-US）。
- 目录是 `data/EdgeTts.kt` 的 `EDGE_VOICE_CATALOG`：中文只用大陆 zh-CN 音色，英文只用 en-US；zh-HK/zh-TW 与方言音色**排除**——它们读不了简体普通话词表。
- 表单项样式与 TTS API 表单一致，每项带**试听**按钮（只合成样音，不改选择）。
- 没有单独的「默认」项：目录里本来就有内置默认音色 晓晓 / Aria，选中其中一个时**存空值**（= 内置默认）。
- 持久化：`edge_voice_zh` / `edge_voice_en`；空 = 内置默认 晓晓 / Aria。选择**实时跟随**播放。
- 缓存键绑定音色 + 语速，切音色会重新生成，而不是回放旧音频。

### 2.4 缓存与播放

`data/EdgeTts.kt` 只是 vendored 客户端外面的一层**磁盘缓存 + 单片段单飞**：它决定音色、后台预热 `cacheDir/tts/edge-…`、把就绪片段交给播放。播放永远不等下载。

## 3. 系统 TTS（兜底，始终可用）

- `android.speech.tts`；按条目 `kind` 选 `en-US` / `zh-CN`；语速取设置（0.5–1.5，默认 0.9）。
- **音色选择**（设置 → 系统语音，`system_voice_zh` / `system_voice_en`）：init 成功后枚举一次 `getVoices()` 并缓存；按语言的 `Voice.name`（空 = 引擎默认）实时跟随播放，应用顺序是 **`setLanguage` 之后 `setVoice`**——`setLanguage` 会把 voice 重置回该 locale 默认，顺序决定选中的音色是否真的发声。
- **命名**：Android 的 `Voice` 不给显示名与性别，所以选择器的文案来自 `data/SystemSpeaker.kt` 的纯函数（`systemVoicesFor` / `systemVoiceInfos`，在 `SystemEngineVoice` 模型上做 JVM 单测）：引擎名可读（`com.apple.voice…Samantha` → `Samantha`）就保留；无意义的生成 id（`cmn-cn-x-ssa-local`）退回 `中文男声1` / `中文女声1` / `中文语音1` 式命名（英文同理；性别取自引擎的 `#female`/`#male` 标记）。
- **过滤**：只保留简体普通话（zh/cmn + CN）与美式英语——zh-TW/zh-HK/yue 与 en-GB 读不了教材词表。
- **生命周期**：异步 init 用 `suspendCoroutine` 包住，由 `onInit` 恢复；说话完成的续体必须从 `UtteranceProgressListener` 的 **`onDone` 和 `onError` 两个回调**恢复（漏掉 `onError` = 永久挂起）**或**由看门狗（`max(4000, text.length × 250)` ms，按成功处理）恢复——静音的 utterance 绝不能让播放冻住。
- **片段播放**：缓存 MP3 用 `MediaPlayer`，完成监听 **+ 10 s 看门狗**（只有出现具体需求才考虑 Media3）。

## 4. 自定义 OpenAI 兼容 TTS（BYOK）

两种报文形状，由配置的 `api` 字段选择；都用 `Authorization: Bearer <apiKey>` + `Content-Type: application/json`：

- **`speech`**（标准二进制——OpenAI TTS、智谱 GLM-TTS、硅基流动…）：`POST {base}/audio/speech`，body `{ "model": …, "input": …, "response_format": …, "speed": … }`；`speed` = 语速，钳到 [0.25, 4]；`response_format` 取配置（默认 `mp3`）；响应是**二进制音频**。
- **`chat`**（小米 MiMo 走 chat completions 合成）：`POST {base}/chat/completions`，body `{ "model": …, "messages": [{ "role": "assistant", "content": <文本> }], "audio": { "format": "wav" } }`；该形状的 format 固定 `wav`；响应是 JSON，**base64 音频在 `choices[0].message.audio.data`**（缺失/为空 = 错误；字段存在但不是字符串也算缺失）。

其余约定：

- **按文本语言选音色**：CJK → `voiceZh`，英文 → `voiceEn`；音色为空表示**省略 `voice` 字段**，用服务商默认。
- **片段缓存键 = `api|model|voice|format|rate` 的 8 位十六进制哈希**（rate ×10 取整）+ 文本。每个成分都进哈希，所以换音色/格式/语速会重新生成，而不是回放旧音频。
- 非 2xx 响应：能取到 `error.message` 就显示它，否则显示 `HTTP <status>`；原始响应体不展示（HTML/不透明内容，会泄露服务商内部信息）。
- 预设列表在 `data/TtsProviderConfig.kt`——**唯一真源**，有单测钉住：`mimo`（小米 MiMo，免费默认）与 `custom`（自定义）。RN 前身的 zhipu / siliconflow / openai 预设按作者决定删除，端点仍可经「自定义」接入。

## 5. 朗读文本规则（说什么，而不是怎么发声）

**组词朗读**（`cjkWordSpeech`，只用于**单字** CJK 条目）：朗读 `"组词的X"`——`月|yuè|月亮` → `"月亮的月"`。多字词/句子返回 `""`（按原样朗读）。虚词永不成词：`的 地 得 着 了 吗 呢 吧 啊 呀 啦 嘛 么`（`NO_COMPOUND_HEADS`）。

候选分层，先命中先取：

1. 条目自己的 `meaning` 列——按 `；;` 再按 `，,、` 切分、去括号，留下含本字的二字词。**不做拼音过滤**（课本释义即权威）。
2. "learned" 池：当前词表里其余含本字的二字词（按出现顺序，不过滤），然后 `compounds.json` 的 `learned`（过滤）；本层按常用词表排序。
3. `compounds.json` 的 `compounds` 常用词池（过滤，按词频排）。

拼音过滤（`syllableMatches`）：候选词中本字的音节必须等于条目的带调拼音（数字调，`ü→v`；任一侧无调/轻声都算通过）。**没有拼音的行**（用户粘进来的裸字）没有读音锚点，就用该字最常用的常用池词推断音节（`好` → 良好 的 `hao3`）再过滤——否则 learned 池里的 好客(`hao4`) 会劫持一个裸 好（它自己的候选读的是主导音 hǎo）。常用池里没有该字的，保留"全通过"兜底。

⚠️ **绝不按词去重——走原始数组，取第一个通过过滤的候选。** `朝 | zhāo`（tier-3 池）走 `朝鲜(chao2)✗ → 朝廷(chao2)✗ → 明朝(zhao1)✓ → "明朝的朝"`；`澄 | dèng` 走 `澄清(cheng2)✗ → 澄清(deng4)✓ → "澄清的澄"`——若去重只保留第一条 澄清(cheng2)，dèng 读音就一个候选都不剩（退回裸字）。约 21 个词有这种双读音（`朝` 的池子：朝鲜 chao2、朝廷 chao2、明朝 zhao1、朝阳 zhao1、王朝 chao2、朝阳 chao2）。

**朗读释义**（`speakableMeaning`，英文条目）：逐义项（按 `；;` 切）去掉行首词性前缀（`n.` `vt.` …，否则 TTS 会逐字母拼读）、去括号与边缘标点，取第一个非空义项；若仍超过视觉宽度 12（全角 = 1，半角 = 0.5），在第一个 `，,、` 处截断。结果为空 = 不朗读。

**不引入拼音库**（`pinyin4j`/`TinyPinyin`/…）：课本行自带拼音、组词数据自带逐字音节，解析器永远不需要生成拼音。
