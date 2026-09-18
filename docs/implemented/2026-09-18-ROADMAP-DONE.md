# Roadmap 已完成条目（归档）

**归档日期**：2026-09-18。本文件是从 [`../ROADMAP.md`](../ROADMAP.md) 拆出的**已完成**条目——正文与编号照原样保留（`Roadmap #N` 的引用按编号索引，号段不重排），拆分时只补了这条说明与失效链接，未改写内容。ROADMAP 现在只留未完成项。

**编号索引**：**1** 错词本升级 · **2** 用户词表长期保存 · **3** 听写统计 · **7** 结束页重做本场 / 复习错词带原词行 · **9** 多选词表、抽词听写 · **11** 拍手写答案自动批改（候选池已落地的五组见文末）。

---

## 1. ✅ 错词本升级（错次 + 来源） 🤖
已实现（2026-09-09，详情见 commit `2412a…` 一带与 `app/schemas/…/2.json`）：Room v2——`wrong_words` 增加 `errorCount`（按场次计）/`lastWrongAt`/`sourceLabel`（教材词表 id / 历史行 id / null）；`recordMark` upsert；`DictationSessionStore` 携带来源；错词本抽屉按来源分组、错次降序；来源失效降级「未知来源」不删行。配套新增 `app/src/androidTest`（迁移测试基建）。**决定**：`sessions` 表不并入 v2，另起 v3。

---

## 2. ✅ 用户词表长期保存（收藏不被 50 条上限淘汰） 🤖
已实现（2026-09-09）：`trimTo`/`clear` 加 `AND id NOT IN (SELECT id FROM favorites)`（`f886f67`，纯查询、无迁移）；`pruneHistoryOrphans` 语义保留。验收补 `HistoryFavoritesTrimTest`（真库：收藏豁免、50+1 上限、显式删行才清收藏、`default_*` 永不裁剪）。

---

## 3. ✅ 听写统计（本地） 🤖
已实现（2026-09-10）：Room v3 新表 `sessions` + `MIGRATION_2_3`；`DictationViewModel` 在 `engine.finished` 首次为真时写一条（中止不记）；聚合为 `domain/Stats.kt` 纯函数（`summarize`/`dailyStats`/`streakDays`）；页面 `ui/StatsScreen.kt`（无图表库）。**与规划的差异**：不做错词率趋势（样本太小）；连续天数按"今天未听写不清零"口径。验收：单测 265 绿、迁移测试 6/6、模拟器走查记录/错词率/图表/清空。详见 commit。

---

## 7. ✅ 结束页重做本场 / 复习错词带原词行 🤖
已实现（2026-09-10）：①**再听一遍**：`replayRun()` 用本场 `_activeLines` 直接 `beginRun`（不写新历史行，顺序已烘焙）；②**复习错词带原词行**：新增 `data/WrongWordLineResolver.kt`（本场行优先 → sourceLabel 回查 → 裸词降级），内置来源按预览口径 ECDICT 富化；首页错词本入口同走 resolver；两处入口 `Mutex.tryLock()` 防双击。**与想法的差异**：再听一遍不重抽随机；内置来源额外做了富化。验收：单测 275 绿、模拟器走查（富化行随重放/跨场回查）。详见 commit。

---

## 9. ✅ 多选词表、抽词听写 🤖
已实现（2026-09-11 + UI 审计 C4 后续）：①**多选词表** `data/LibrarySelectionStore.kt`（进程级选中，跨分类；退出/回首页复位；列表行整行 `toggleable`）；②**抽词页** `ui/LibraryDrawScreen.kt`（池摘要固定底栏、逐词预览 + 换一批、chip 门控、自动去重提示）；③**池与抽样** `domain/DrawWords.kt`（按可朗读词头跨表去重、shuffle+take 无放回、候选池离线富化）；④**来源** `multi:<id>,…`，resolver 逐成员回查，UI「首个可解析词表 等 N 个词表」。抽词结果不写历史（内置词表从不落库）。验收：单测 303 绿、模拟器走查。详见 commit。

---

## 11. ✅ 拍手写答案自动批改（照片批改） 🤖
已实现（2026-09-11）：结束页「拍照批改」→ 批改页 `ui/DictationGradePane.kt`；语言由本场词表判定（`isCjkRun`）；复用 OCR 管线（`OcrImagePicker` + `OcrCropOverlay` + `recognizeAnswers`），仍是四个出网点；批改提示语"抄学生的字不纠正"（`answerPrompt(lang)`）；比对为纯 domain `domain/AnswerGrading.kt`（题号优先 → NW 序列比对 → 顺序不符兜底；正确/错词/漏答/多余作答，近似拼写标存疑）；**必须人工确认才入库**（`confirmGrade`）。**差异**：成绩不计入 sessions；不做多列版面分析。验收：单测 327 绿、模拟器走查（假服务注入）。详见 commit。

---

## 候选池中已落地

### 数据与可靠性

- ✅ **测试基础设施（instrumentation 迁移测试 + CI lint）** — 已落地：`app/src/androidTest` 随错词本升级建立（`WrongWordsMigrationTest` 覆盖 v1→v2、`SessionsMigrationTest` 覆盖 v2→v3，各自对照提交的 `app/schemas/*.json` 校验），CI 的 `instrumented` job 跑 `connectedDebugAndroidTest`（受限于 API 37 镜像问题暂停在 API 36，见 `../ROADMAP.md` 工程质量），`build` job 里已有 `lintDebug` 与 `scripts/check-assets.py`。**未采纳**：Compose 冒烟测试——测试策略明确不做 Compose UI 测试（`AGENTS.md` *Testing & QA*：界面行为由下层 StateFlow 与纯函数测试锁定）。

### 听写体验

- ✅ **听写页屏幕常亮** — 听写中看屏幕，熄屏会打断节奏。**早已实现**（`b198fff`，v0.1 起）：`ui/DictationScreen.kt` 在组合期间设 `View.keepScreenOn`、`onDispose` 清除，听写中 / 暂停 / 结束页整场不熄屏，退出听写页立刻恢复系统熄屏（`View.keepScreenOn` 落到窗口 `fl=KEEP_SCREEN_ON`，等价于 `FLAG_KEEP_SCREEN_ON`）。此前本条一直留在候选池、且记录它的 PROGRESS/PHASES 日志在 `c0688d4` 公开化时删除，才显得"被改没了"——代码从未移除。验证（2026-09-11，模拟器，`screen_off_timeout=15000` + `svc power stayon false`）：听写窗口 `mAttrs` 带 `fl=KEEP_SCREEN_ON`，活动中 45 s、暂停 30 s、结束页仍 `mWakefulness=Awake`；回首页后窗口无该 flag，25 s 内 `Asleep`。

### 界面（UI / UX Audit，2026-09-12 🤖）— ✅ 已完成

**全文见 [`2026-09-12-UI-AUDIT.md`](2026-09-12-UI-AUDIT.md)**（基线 `4c40ab7`，纯源码静态审查，177 条原始发现 / 去重后约 150 条，P1 18 条）。分四段：**A 缺陷修复**（12 项，≤20 行/项）→ **B 一致性收口**（补 `Type.kt` 字阶覆盖 85% 文字、Snackbar 取代 15 处 Toast、`toggleable` 统一、insets 去重、单位合一、TTS/OCR provider 表单抽公共件）→ **C 状态覆盖与内容层** → **D 打磨**（动效、导航、术语）。
建议：**高**，A 段可随时插队（便宜且用户可感知）；B1 字阶应排在其他视觉微调之前。
**已拍板（2026-09-12）**：① 表盘恢复 tap-to-reveal（改回 `AGENTS.md:94` 描述的行为，按钮保留作可见标注，见 `2026-09-12-UI-AUDIT.md` §0.1 D-1）；② 5 个零引用语义 token **接上**——朱砂用于汉字提示层、`successContainer` 用于"正确"徽章，`DialCenter(isCjk)` 从死参数转为实际判据（见 `2026-09-12-UI-AUDIT.md` §0.1 D-2）。

### 平台与打磨

- **动态取色（Material You）** — ✅ 已做（2026-09-18）：设置 → 外观 的 `动态取色` 开关，默认关（策展纸墨配色仍是默认身份），开时换成壁纸取色；朱砂/收藏金/成功语义色保持策展。见 `2026-09-12-UI-AUDIT.md` 阶段 D 收尾。

- ✅ **大屏 / 横屏 / 折叠屏适配** — 已落地（2026-09-16，UI 审计 C6）：`ui/Adaptive.kt` 给出窗口尺寸接缝（`currentWindowAdaptiveInfoV2().windowSizeClass`、`WIDTH_DP_MEDIUM`）与 720 dp 阅读宽度上限（`Modifier.contentWidth`，约 40 处调用点覆盖各页面与两条底栏，内容居中而不再拉满）；听写舞台由 `domain/DialStage.kt` 从实测框解算 STACKED / BESIDE，双栏以 WindowSizeClass 为门（`ui/DictationScreen.kt:893`/`:910`）；词库页用 `GridCells.Adaptive(180.dp)`；旋转敏感状态进 `rememberSaveable`，manifest 不锁方向。**未采样（如实记录）**：折叠屏铰链姿态与分屏（freeform）——可用硬件里没有这两种形态（审计 §未能闭环项）。
- ✅ **无障碍（TalkBack）走查** — 已闭环：Phase 10 的首轮走查（`2026-09-04-PHASES.md` 第 10 阶段）加审计 A1–A12 / B3 全部关闭；现状 33 处 `IconButton` 全带中文 `contentDescription`，switch / radio / checkbox 行有角色，倒计时读数 `clearAndSetSemantics` + `liveRegion`，裁剪层 10 个 `CustomAccessibilityAction`，分节标题 `heading()`；大字号 1.5× 已在拨盘 / 横屏舞台 / 统计图上走查。**残留**（留在 `../ROADMAP.md`）：2.0× 字号、固定 dp 高度的文本容器、36 dp 触达、CJKV 无障碍 API。
### 工程质量

- **词库质检脚本（`scripts/check-assets.py`）+ CI** — ✅ 已落地（见 [`../ROADMAP.md`](../ROADMAP.md) 条目 16）：651 表 / 21769 行，校验每行可解析、无重复词、拼音格式合法、组词含本字、多字中文行不带列、生成资产（`ecdict-meta.json` / `hanzi-meta.json` / `compounds.json`）结构完整；CI 先于 Gradle 执行。词库名/分类名的排序漂移仍由 `LabelOrderTest` 守住。
