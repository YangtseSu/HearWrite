# PROMPTS

提供给负责编码的 AI 的阶段提示词。使用方法：一次只发一个阶段的提示词，建议每个阶段开一个新的 AI 会话（避免上下文污染），开场先让编码 AI 完整读一遍 `AGENTS.md` 再执行阶段任务；AI 跑偏时不要辩论，直接要求"重读 AGENTS.md 对应小节"。阶段完成后把该阶段演示结果勾掉 `PROGRESS.md`，再进入下一阶段。所有提示词默认仓库根目录就是 `/home/yangtse/projects/HearWrite`，且编码 AI 已能读取仓库内 `AGENTS.md`、`PROGRESS.md`、`data/`。

## 通用规则（每个阶段提示词已内含，无需单独粘贴）

1. 先读 `AGENTS.md`（架构、工具链版本钉、行为契约、数据格式）和 `PROGRESS.md`（当前阶段与验收标准），再动手。
2. 一次只做当前阶段列出的事；不做下一阶段的功能，不重构无关代码。发现契约歧义时以 `AGENTS.md` 为准；仍不清楚再问。
3. 参考实现只读：`/home/yangtse/projects/alice`（React Native 原项目）。需要行为细节时读它的 `src/lib/dictation.ts`、`src/hooks/usePlayback.ts`、`src/lib/tts.ts`、`src/lib/ocr.ts`，但**代码必须用 Kotlin/Compose 重新实现**，禁止照搬结构或翻译式移植。
4. `data/` 目录内容只读，禁止修改或重新生成；`ecdict-meta.json`、`compounds.json` 永远不手改。
5. 每完成一件事就 `git commit`（英文、conventional commits），每个 commit 必须能编译。
6. 阶段收尾必须：`./gradlew :app:assembleDebug` 和 `:app:testDebugUnitTest` 全绿 → `adb install -r` 装到真机/模拟器 → 实际操作演示验收标准里的场景 → 更新 `PROGRESS.md`（勾掉条目、更新状态表）并在最后一个 commit 里提交。
7. UI 文案硬编码中文；代码、注释、commit 用英文。不引入 DI 框架、不引入多模块、不写 iOS/Web/桌面端、不实现收费/Credits 相关功能。
8. 演示用的临时调试界面/日志在验收通过后删除，再打该阶段的收尾 commit。

---

## Phase 1 — 项目脚手架

```text
请完成 PROGRESS.md 中的 Phase 1（项目脚手架），严格遵守 AGENTS.md。

任务：
1. 用 Gradle 搭建单模块 Android 项目：包名 org.yangtse.hearwrite，应用名 "HearWrite 听写"，
   minSdk 36 / targetSdk 37 / compileSdk 37。Gradle wrapper 9.7.1、AGP 9.3.0、Kotlin 2.4.10、
   Compose BOM 2026.08.00，全部依赖版本集中在 gradle/libs.versions.toml（其余依赖取当前最新稳定版并记录在 catalog）。
   依赖选型按 AGENTS.md 的 boring 清单，不要多加。
2. 先运行 sdkmanager "platforms;android-37"（接受许可）再构建。
3. Compose 骨架：Material 3 主题（亮/暗跟随系统）、navigation-compose 三条路由
   HomeScreen / DictationScreen / SettingsScreen（占位内容即可，但要显示各自标题）、
   HearWriteApplication 作为 Application 类（暂只做空的手动单例容器）。
4. 项目根补上 .gitignore（已存在，可按需补充）、gradle.properties（按需：AndroidX、非传递R类等常规项）。

验收：./gradlew :app:assembleDebug 与 :app:testDebugUnitTest 通过；adb 安装后启动看到三个页面可切换。
提交：每个独立事项一个 commit（如 chore: bootstrap gradle project、feat: compose navigation skeleton），
最后更新 PROGRESS.md 状态并提交。
```

## Phase 2 — 领域层：词表解析

```text
请完成 PROGRESS.md 中的 Phase 2（词表解析），严格遵守 AGENTS.md 的 Word-line format 契约。

任务：
1. 在 app 模块 domain/ 层用纯 Kotlin 实现（不依赖 Android 类）：
   - 行解析：每行一个词条，`word | pos | meaning`（接受全角｜，1 列或 3 列，空行跳过）；
   - 词性前缀识别与归一化映射（AGENTS.md 列出的完整映射表，与 alice/src/lib/dictation.ts 一致）；
   - 朗读文本规则：`you're = you are` 只读左边；CJK 检测（含 [\u4e00-\u9fff] 判定中文模式）；
   - speakableMeaning：按 ；; 分句、，,、 分词、去括注与首尾标点、视觉宽度 12 上限（全角1半角0.5）。
2. 单元测试覆盖以上每条规则及边界：全角管道、多列残缺、词性大小写、宽度截断、空 meaning。
   测试 fixture 可读取仓库 data/ 下的真实词表做样例。

验收：:app:testDebugUnitTest 全绿；测试输出展示从 data/ 高考3500、人教版小学语文 解析出的样例词条。
提交：feat: word-line parser with pos normalization（实现+测试可分两个 commit），更新 PROGRESS.md。

## Phase 3 — 内置词库

```text
请完成 PROGRESS.md 中的 Phase 3（内置词库），严格遵守 AGENTS.md 的 Built-in library 契约。

任务：
1. 把仓库根 data/ 以 assets 形式打进 APK（assets.srcDir(rootProject.file("data"))），
   排除 meta/ 目录（aapt ignoreAssetsPattern）；实现 AssetLibraryLoader：扫描分类目录 → 列表 →
   词条，带内存缓存；内置词条 id 一律 default_<category>_<label>。
2. 移植 alice/scripts/generate-library.ts 的 compareLabels 排序（年级 一→九、上/下/全、Unit/Module 序号、
   第X册 册号），放 domain/ 并写单元测试。
3. HomeScreen 增加词库入口（抽屉或页面）：分类 → 列表 → 词条预览，支持按列表名/词条搜索。

验收：能浏览全部 10 个分类；打开 "人教版小学语文 / 二上 写字表 识字 1" 看到词条与释义；搜索能定位列表。
提交：feat: bundled library asset loader、feat: label ordering、feat: library browser with search，更新 PROGRESS.md。
```

## Phase 4 — 听写引擎 + 系统 TTS

```text
请完成 PROGRESS.md 中的 Phase 4（听写引擎 + 系统 TTS），严格遵守 AGENTS.md 的
Playback engine 与 Persistence 契约。

任务：
1. DictationEngine：协程状态机（独立 SupervisorJob scope），每个词 speak1 → 700ms → speakMeaning →
   speak2 → 间隔倒计时 → 下一词。speakMeaning 内容：中文单字必读组词（本阶段先读字本身，Phase 5 接入组词）；
   英文仅在开启"朗读释义"时读 speakableMeaning。取消用 Job.cancel() + 代际号（start/pause/stop/skip 时 gen++，
   每个挂起点后校验 gen）；speak1 失败不重试，直接进下一阶段（speak2 是天然第二次机会）；自动播报关闭时
   speak2 后停在当前词；倒计时 deadline 存 StateFlow/@Volatile、每 tick 重读——间隔调整即时生效
   （alice 在这里出过真 bug：deadline 被闭包捕获，实时改间隔整版失效）；离开听写页停止，返回键弹确认框。
   默认：间隔 7s（1–10，步进 0.5）、语速 0.9（0.5–1.5）、自动播报下一词开。
2. 系统 TextToSpeech 封装：按词条语言选 en-US / zh-CN，应用语速设置；异步初始化用 suspendCoroutine 包
   onInit；UtteranceProgressListener 的 onDone 与 onError 都要 resume 挂起协程（漏 onError 会永久挂起）。
   DataStore 保存设置项。
3. HomeScreen：粘贴词表输入框（草稿 500ms 防抖持久化，DisposableEffect.onDispose 时 flush 未落盘草稿）、
   起始序号调整器（词数变少时 clamp 到有效范围）、随机顺序开关（Fisher-Yates，不持久化）、
   开始听写按钮（含从词库列表一键开听写）；开始时按 起始序号截取 → 随机洗牌 的顺序应用后再传给听写页。
4. DictationScreen：倒计时环（clearAndSetSemantics 播报剩余秒数）、当前词默认隐藏点按显示、词性与释义提示、
   标记错词按钮（本阶段先内存记录）、上一词/暂停/下一词/停止。
5. 引擎单元测试（runTest 虚拟时间 + 假 Speaker）：阶段顺序、speak 失败不死循环、cancel 后无残留播报、
   自动播报关闭时停住、代际号防竞态（连续两次 start，旧协程不污染新状态）、★ 进入 Interval 后改间隔，
   用 advanceTimeBy 验证倒计时按新值走完。

验收：真机上完成一次完整英文听写（读两遍、倒计时、翻页）与一次汉字听写（纯词表按 zh-CN 朗读）；
标记错词有可见反馈；听写中拖动间隔滑块，倒计时节奏立刻变化（★ 重点验证）。
提交：feat: dictation engine、feat: system tts、feat: dictation screen、feat: home paste import，更新 PROGRESS.md。
```

## Phase 5 — 汉字组词朗读

```text
请完成 PROGRESS.md 中的 Phase 5（组词朗读），严格遵守 AGENTS.md 的 Speech-text rules。

任务：
1. 加载 assets 的 compounds/compounds.json（{compounds, learned}，音节为数字调、ü=v、轻声无调号）。
2. 移植 alice/src/lib/dictation.ts 的 cjkWordSpeech，播报文本为 "组词的字"（月|yuè|月亮 → "月亮的月"；
   听写序列：生字 → "月亮的月" → 生字）。候选三级、取第一个通过过滤的：
   ① 条目 meaning 列：按 ；; 再 ，,、 拆分、去括注，保留含该字的二字词，不按读音过滤（课本释义权威）；
   ② 已学词池：当前词表其他含该字的二字词（按出现序，不过滤）+ compounds.json 的 learned（过滤），
      本级整体按常用词表频级排序；
   ③ compounds.json 的 compounds 常用词池（过滤，频级升序）。
   多音字按条目带调拼音过滤候选（音节一致才可读；任一方无调放行）；NO_COMPOUND_HEADS 虚词直接读单字。
   ⚠️ 禁止按"词"去重候选：朝阳/澄清 等约 21 个词带双读音，去重后 朝|zhāo 永远选不中"朝阳"。
3. 组词 pass 强制走系统 zh-CN TTS（有道词典音发不出短语）。
4. 单元测试用真实数据：月（月亮）、长（cháng→长期 / zhǎng→增长）、朝（zhāo→朝阳 / cháo→朝廷，验证双读音不去重）、
   虚词"的"（返回空）。

验收：真机汉字模式听写单字时依次播报 生字 → "组词的字" → 生字（月 → "月亮的月" → 月，系统中文 TTS）；
"长" 在不同拼音条目下读出对应组词。
提交：feat: cjk compound speech for char dictation + tests，更新 PROGRESS.md。
```

## Phase 6 — 错词本 / 历史 / 收藏

```text
请完成 PROGRESS.md 中的 Phase 6（错词本/历史/收藏），严格遵守 AGENTS.md 的 Persistence 契约。

任务：
1. Room 建表：wrong_words(word, addedAt)、history（仅用户自建词表；id、原文、enriched 文本、创建时间，
   上限 50 条超出淘汰最旧）、favorites（词条 id：default_* 或 history id）。库访问用 DAO + Flow。
2. 听写结束页：成绩统计；错词本复习（只对错词重跑一轮听写）；错词一键导出到剪贴板。
3. Home 的历史/收藏抽屉：历史增删清、enriched 文本挂接、收藏切换；词库列表可收藏。

验收：听写 → 标错 → 复习轮只播报错词；导出后剪贴板内容可直接粘贴回首页开新听写；
杀进程后错词/历史/收藏仍在。
提交：feat: room persistence、feat: finish screen and review、feat: history favorites drawers，更新 PROGRESS.md。
```

## Phase 7 — 有道 TTS + 音效

```text
请完成 PROGRESS.md 中的 Phase 7（有道 TTS + 音效），严格遵守 AGENTS.md 的 TTS priority chain。

任务：
1. Youdao 音频获取：CJK 用 dict.youdao.com/dictvoice?audio=<urlencode>&le=zh，
   英文依次尝试 type=2、type=1；带移动端 User-Agent；小于 256 字节的响应视为失败；
   MP3 缓存到 cacheDir/tts/（key=text+lang）；同一文本并发去重（single-flight）；失败自动退回系统 TTS。
   speak() 只使用已就绪的缓存、绝不阻塞等待下载，未命中立即降级系统 TTS；后台预取当前词、下一词与英文释义；
   本地片段播放用 MediaPlayer（完成监听 + 10s 超时兜底防音频卡死；Media3 仅在确有需要时引入）。
2. 设置新增 TTS 来源（有道/系统，默认有道）。
3. assets 的 audio/tick.wav、chime.wav 接入：倒计时最后一秒播 tick（音量 0.5），整轮结束播 chime（音量 0.6），
   设置可关；标记错词时加震动反馈（对齐 alice 的 haptics）。

验收：联网时听写发音来自有道（无系统 TTS 的机械感）；飞行模式下自动退回系统 TTS 且不中断听写；
tick/chime 可听到。
提交：feat: youdao tts with cache and fallback、feat: ui sound effects and haptics，更新 PROGRESS.md。
```

## Phase 8 — 拍照识词 OCR

```text
请完成 PROGRESS.md 中的 Phase 8（OCR 导入），严格遵守 AGENTS.md 的 OCR import 契约。
绝对不要实现 Credits/配额/扣费逻辑——原项目的收费体系整体排除。

任务：
1. 图片输入：Photo Picker（ActivityResultContracts.PickVisualMedia）+ 相机拍照；压缩至最长边 1600px、
   JPEG 质量 0.82、base64 data URL。
2. OpenAI 兼容 vision 调用：POST {base}/chat/completions；提示词原文照抄 AGENTS.md 指定的
   alice/src/lib/ocr.ts 中英文两套中文 prompt（在扫描面板里选语言）；回复按行用 Phase 2 解析器解析
   （剥掉可能的代码围栏），结果填入首页输入框供人工修正。
3. 重入防护：OCR 请求用 Mutex.tryLock() 在首个挂起点之前同步占位，快速连点不会重复发起请求
   （alice 曾因异步 state 防护失效而双倍扣费；本项目无扣费，但重复请求同样要防）。
4. 设置：OCR 服务商配置（默认预设 智谱 https://open.bigmodel.cn/api/paas/v4 / glm-4v-flash，
   baseUrl/apiKey/model 可改，用户提供自己的 key，本地 DataStore 存储）；提供"测试连接"按钮；
   所有入口展示 "AI 识图可能存在误差，请核对识别结果"；错误用中文提示并允许重试。

验收：对英文词表和语文生字词表各拍一张照，识别结果可编辑并直接开听写。
提交：feat: ocr import via openai-compatible vision、feat: ocr settings，更新 PROGRESS.md。
```

## Phase 9 — OpenAI 兼容 TTS（可选音源）

```text
请完成 PROGRESS.md 中的 Phase 9（自定义 TTS 音源），严格遵守 AGENTS.md 的 TTS priority chain。

任务：
1. TTS 服务商配置：api = speech | chat 两种线协议——speech: POST {base}/audio/speech 返回二进制音频
   （默认 mp3）；chat: POST {base}/chat/completions 带 audio 选项，base64 音频在
   choices[0].message.audio.data（小米 MiMo 免费档）。配置字段 baseUrl/apiKey/model/voiceEn/voiceZh/
   responseFormat；预设列表照抄 alice/src/lib/ttsConfig.ts 的 preset 内容（字段可编辑）。
2. 片段缓存 cacheDir/tts/，key 为 provider|model|voice|rate|text 哈希；并发去重；任何失败回退下一级
   （自定义 → 有道/系统），绝不阻塞听写流程。
3. 设置页新增音源：默认有道 / 自定义（选后展示 provider 表单与测试按钮）。

验收：配置 MiMo 后听写用其音色；关闭网络或填错 key 时自动回退，听写不中断。
提交：feat: openai-compatible tts provider、feat: tts provider settings，更新 PROGRESS.md。
```

## Phase 10 — 设置整合 / 主题 / 打磨 / 发布

```text
请完成 PROGRESS.md 中的 Phase 10（设置、主题、打磨、发布），遵守 AGENTS.md 全部约定。

任务：
1. SettingsScreen 整合：语速、间隔、自动播报、朗读释义、音效、TTS 音源与 provider、OCR 配置、主题。
2. 亮/暗 Material 3 打磨（统一 colors/shape tokens）；空态/错误态文案；无障碍走查
   （contentDescription、触控目标、滑块可用递增/递减操作——对齐 alice 近期的 a11y 修复提交）；
   应用图标与自适应图标。
3. 发布配置：gitignored keystore.properties 签名（模板 keystore.properties.example）、
   R8 minify 的 assembleRelease 可出签名 APK；确定 versionCode/versionName 规则。

验收：签名 release APK 安装并完整演示一轮（导入 → 听写 → 复习）；暗色主题无违和。
提交：feat: settings consolidation、feat: app icon and theming、chore: release signing and minify，更新 PROGRESS.md。
```

---

阶段全部完成后：把仓库推到 GitHub（远端待定，见 PROGRESS.md「Open decisions」），并确认 License 选择。
