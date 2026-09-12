# 界面审计（UI / UX Audit）

**审计基线**：`4c40ab7`（2026-09-12）。行号以该提交为准，代码变动后按符号检索。
**方法**：**纯源码静态审查**——不使用截图（视觉模型的识图误差会导致误判），不使用模拟器/真机。每条发现必须引用 `文件:行` 并附代码片段；依赖框架测量或播报顺序的推断标 `[推断]`。
**范围**：`app/src/main/java/org/yangtse/hearwrite/ui/**`（33 个文件）、`MainActivity.kt`、`HearWriteApp.kt`、`ui/theme/**`、`app/src/main/res/**`、`AndroidManifest.xml`。
**产出**：六个维度合计 177 条原始发现（含跨维度重复），去重后约 150 条，其中 **P1 18 条（每条均由主审在源码中复查，映射关系见下表）**。本文件是审计记录 + 修复计划；**只记想法与规划**，不做实现。

| P1 原始编号 | 归入 | P1 原始编号 | 归入 |
|---|---|---|---|
| 听写 D1（表盘不可点） | A2 | 首页 H1（OCR 药丸被挤没） | C1b |
| 听写 D2（词语被截断） | C2 | 首页 H2（清空草稿无确认） | C1b |
| 听写 D3（204dp 圆裁切） | C2 | 首页 H3（行点击二义） | A1 · C1b |
| 听写 D4（清空错词本无确认） | A4 | 首页 H4（间隔数值 TalkBack 读不到） | B3 |
| 听写 D5（作答被截断） | A5 | 首页 H5（抽屉 key 重复可崩） | A12 |
| 听写 D6（批改页返回退出整场） | A3 | 首页 H6（起始词无状态语义） | A1 |
| 词库 L1（预览主按钮无忙碌态） | A10 | 设置 S1（子页缺 IME inset） | A7 |
| 词库 L2（底栏缺导航栏 inset） | A6 | 设置 S2（主题选择无 radio 语义） | A11 |
| 词库 L3（多选退出静默清空） | A9 | 设置 S3（Key 字段缺 `singleLine`） | A8 |

严重度：**P1** 用户可感知的缺陷 / 无障碍阻断 · **P2** 一致性问题或明显打磨项 · **P3** 细节。

---

## 0.1 已拍板（2026-09-12）

审计中两处"不是 bug、需要选样子"的分歧，作者已定：

| # | 分歧 | **决定** | 落实位置 |
|---|---|---|---|
| D-1 | `AGENTS.md:94` 规定表盘 tap-to-reveal，实现在 `447d613` 视觉改版时把 `.clickable` 删掉、只留按钮 | **恢复表盘点按**（选项 1）：改回契约描述的行为，`显示词语` 按钮保留为可见标注 | `A2`（§1） |
| D-2 | 8 个语义 token 里 5 个全仓零引用（朱砂 `cjk*` ×3、`successContainer` ×2），`DialCenter(isCjk=…)` 参数声明后从未读 | **接上**（选项 1）：朱砂接到汉字提示层，`successContainer` 接到"正确"徽章，`isCjk` 从死参数变成实际判据 | `B6`（§2） |
| D-3 | 接上朱砂后暴露：**现有朱砂取值与错误红几乎同色**（`#B23A2A` vs `#BA1A1A` ΔE₀₀ = **4.1**；容器 `#F6DAD3` vs `#FFDAD6` ΔE₀₀ = **3.4**），而 `Color.kt` 的注释写的是"绝不用于错误"——汉字提示会被读成报错 | **换色后再接**（选项 1，作者采纳）：取值下移到赭石/熟的朱砂族，与错误红、收藏金同时拉开 | `B6`（§2），取值见下表 |

两项都不含功能损失，属于视觉/交互取向；`D-1` 恢复的是一个曾被实现的交互（非新增需求），`D-2` 兑现的是设计系统里已写好、未落地的注释承诺。

### D-3 决定的朱砂取值（ΔE₀₀ 与对比度均为实测计算）

| token | 原值 | **新值** | 与错误红的 ΔE₀₀ | 与收藏金的 ΔE₀₀ | 对比度 |
|---|---|---|---|---|---|
| `CjkAccentLight` | `#B23A2A` | **`#9C4A22`** | 4.1 → **11.6** | — → 14.8 | 白卡 6.15 / 纸底 5.75 |
| `CjkContainerLight` | `#F6DAD3` | **`#E8DFC9`** | 3.4 → **16.2**（vs 错误容器） | — | 承 `onCjk` 11.80 |
| `OnCjkContainerLight` | `#48150E` | **`#3A1B0C`** | — | — | 承 11.80 |
| `CjkAccentDark` | `#E08A7A` | **`#DB9A6B`** | 11.1 → **16.1** | — → 17.2 | 暗卡 6.82 |
| `CjkContainerDark` | `#5A231C` | **`#4A3220`** | 12.9 → **20.5**（vs 错误容器） | — | 承 `onCjk` 8.96 |
| `OnCjkContainerDark` | `#F8D9D2` | `#F8D9D2`（不变） | — | — | 承 8.96 |

口径：ΔE₀₀ ≥ 10 为"明显不同"，≥ 15 为"不可能混淆"；正文对比度门槛 4.5:1。三项约束（远离错误红 / 远离收藏金 / 保持可读）同时满足；`#8F4A18` 一类偏棕取值分离度更高（vs 错误红 15.7）但更偏褐、离"朱砂"更远，故取 `#9C4A22` 作为平衡点。
**换色本身零视觉影响**（这 5 个 token 当前无人引用），实际生效在 `B6` 接线上；两项应在同一提交内完成，避免"改了值但没人用"的中间态被误认为已修复。

---

## 0. 结论摘要

界面的**视觉语言本身是完整且自洽的**：纸质/墨色双主题、朱砂（CJK）强调色、圆角卡片阶、语义 token（`HearWriteSemantics`）、倒计时表盘的字体度量稳定占位器、`View.keepScreenOn`、`clearAndSetSemantics` + `Polite` liveRegion 的逐秒播报——这些比多数商业 app 做得更细。**35 个 `IconButton` 全部带中文 `contentDescription`**（AGENTS.md 的硬要求，零违规），7 处破坏性操作有确认对话框，裁剪浮层把手势暴露为 7 个 `CustomAccessibilityAction`。

问题不在审美，集中在四类：

| 类别 | 症状 | 代表证据 |
|---|---|---|
| **无障碍状态缺失** | "哪个词是起始词""选了哪套主题""这行勾没勾"——全靠颜色编码，语义树里不存在 | `HomeWordSection.kt:288` 只有 `clickable`；`SettingsComponents.kt:261` 只传 `border` |
| **系统收边漏项** | 键盘/导航栏压住主按钮 | `SettingsComponents.kt:238` 无 `imePadding`（子页有 5 个输入框）；`LibraryPreviewScreen.kt:95` 无 `navigationBarsPadding` |
| **交互行为不一致** | 同一动作两种待遇：确认 vs 一键清空、Toast vs Snackbar、两套时长格式 | 15 处 `Toast.makeText` / 0 个 `Snackbar`；结束页 `清空错词本`（`DictationScreen.kt:542`）无确认而同名动作在 `HomeScreen.kt:470` 有确认 |
| **说话与代码契约脱节** | `AGENTS.md:94` 规定"tap to reveal, the core interaction"，但 `DictationScreen.kt` 全文无 `clickable`/`pointerInput` | `grep clickable\|pointerInput\|toggleable DictationScreen.kt CountdownRing.kt` → **无匹配** |

两个**结构级**机会：

1. **字阶只定义了一半**：`Type.kt` 定义了 5 个样式（`displayMedium/displaySmall/headlineSmall/bodyLarge/titleSmall`），全 app（`ui/**` + `MainActivity.kt`，34 个文件）**120 处** `MaterialTheme.typography.*` 调用里只有 18 处命中，**102 处（85%）落到未定义的 Material 默认值**——意味着文档里写的"中文正文 1.6 行高"只对 `bodyLarge` 生效，`bodyMedium`（32 处）、`bodySmall`（23 处）、`labelMedium`（18 处）、`titleMedium`（14 处）等仍在用 Roboto 调过的度量。
2. **几乎没有动效**：全 app 仅 **2 处**动画调用（`HomeScreen.kt:191`、`DictationScreen.kt:650`），0 处 `AnimatedVisibility`/`Crossfade`/`AnimatedContent`/`animateItem`。听写舞台在「听写中 → 成绩卡 → 批改页」之间是硬切。

---

## 1. P1 清单（按修复成本排序，全部 ≤ 20 行）

### A1 · 起始词对 TalkBack 不可见
`HomeWordSection.kt:288-297`、`LibraryPreviewScreen.kt:252-255`

```kotlin
.clickable {
    onStartIndexChange(index)
    if (expandable) { /* 展开释义 */ }
}
```
游标行只有 8% 主色底 + 3dp 主色竖条 + 主色词头三处**颜色**线索，无 `selected`/`selectable`/`stateDescription`；8% 底色的非文本对比度远低于 3:1。TalkBack 用户无法得知听写从哪个词开始。
仓库内已有正确范例：`SettingsComponents.kt:155` 的 `selectable(selected, role = Role.RadioButton)`。

### A2 · 表盘不可点（与 AGENTS.md 契约冲突）——**已拍板：恢复表盘点按**
`DictationScreen.kt:364-393`、`CountdownRing.kt`（全文）

`AGENTS.md:94`：`current word hidden by default — tap to reveal, the core interaction`。
实际实现：248dp 环 / 204dp 盘是**死区**，显示词语只能靠盘下方按钮；隐藏态文案还写着 `用下方按钮显示词语`（`DictationScreen.kt:721-724`）。
对目标场景（学生低头写字、抬眼瞥手机）代价明确：找按钮 → 点 → 再看回去，而不是点任意处。

**这不是设计取舍，是 `447d613` 视觉改版漏掉的**——git 记录可证：

| 提交 | 内容 |
|---|---|
| `d807ff8` | 初版即有 `.clickable { onToggleWord() }`（`DictationScreen.kt:402`），盘内提示 `点按显示词语` |
| `f7679e5` | 契约写入 `tap to reveal, the core interaction` |
| `447d613` | 删除 `- .clickable { onToggleWord() }`（提交正文只写"eye button moved out of the ring corner"），并把提示改写成 `用下方按钮显示词语`；契约未同步 |

**决策：恢复表盘点按，保留按钮作为可见标注。** 实施要点：

- `WatchDial` 的 `CountdownRing`（或其外层 `Box`）加 `Modifier.clickable(enabled = ui.isActive) { onToggleWord() }`；`ui.isActive` 门控与现有按钮一致（`DictationScreen.kt:372`）。
- 语义：点击区域给 `Role.Button` + `contentDescription = if (showWord) "隐藏词语" else "显示词语"`，与按钮文案同源（沿用 `:806` 的 playLabel 单源写法），避免 TalkBack 听到两个不同的动作名。
- 隐藏态提示文案改回 `点按显示词语`（`DictationScreen.kt:721-724`）；按钮保留 `显示词语`/`隐藏词语`。
- 圆盘内不得放其他可点元素与之嵌套（当前无），`显示词语` 按钮不吞掉圆盘的点击区域。
- 验收：`uiautomator dump` 断言圆盘节点带 `clickable="true"` 与上述 `content-desc`；真机走查点圆盘正中与边缘均能切换。

### A3 · 批改页按返回会退出整场听写
`DictationScreen.kt:319`（`if (gradePane)`），全文仅两个 `BackHandler`（`:149`、`:268`）

批改页显示时 `ui.finished == true`、`ui.isActive == false`，于是 `requestStop()` 走 `onClose()`，直接离开听写页并丢弃 `gradeResult`/`gradeSelected`/裁剪会话。而裁剪层两行之遥就注册了自己的 `BackHandler(enabled = cropBitmap != null || cropLoading) { viewModel.cancelCrop() }`——意图存在，只是漏了这一例。
同时顶栏的 `退出听写` 箭头（`:174-176`）在批改页之上仍然可见，与 `返回成绩` 语义冲突。

### A4 · 结束页 `清空错词本` 一键抹掉持久数据
`DictationScreen.kt:542-546`

```kotlin
TextButton(onClick = { onClearWrong(); toast(context, "已清空错词本") })
```
`clearWrongWords()` 清的是跨场次持久化的全局错词本。同一动作从首页错词本抽屉触发时有确认对话框（`HomeScreen.kt:470-474`：`清空错词本？` / `将删除全部 N 个错词。`）。结束页正好是学生写完字、手机斜靠、容易误触的时刻，只有 Toast 告知。

### A5 · 批改页把学生的字截断——判错依据被吃掉
`DictationGradePane.kt:341-347`（作答），`:335-339`（期望词）

```kotlin
Text(if (item.answer.isNullOrEmpty()) "未识别到作答" else "写：${item.answer}",
     style = bodySmall, maxLines = 1, overflow = Ellipsis)
```
整个批改页的前提是"机器判定只是候选，必须人工逐条核对"。而核对的对象 `写：…` 与 `item.expected` 都截到一行——长词或长作答时，**不同的那几个字符恰好被省略号吃掉**，正是"近似拼写"这个最需要人看的场景。

### A6 · 词库预览底部操作栏压在导航栏下
`LibraryPreviewScreen.kt:93-96`

```kotlin
bottomBar = { if (current != null && current.isNotEmpty()) { Column(modifier = Modifier.fillMaxWidth()) { ... } } }
```
Activity 是 edge-to-edge（`MainActivity.kt:17`），而 Material 3 `Scaffold` **故意不给 `bottomBar` 加系统栏 inset**（由 bar 自己负责）。两个兄弟栏都做对了并写明了理由：`LibrarySelectionBar.kt:35-40`、`LibraryDrawScreen.kt:92-96`（均 `.navigationBarsPadding().imePadding()`）。本屏的 `载入草稿` / `听写本词表` 只留了 `padding(bottom = 12.dp)`，三键导航机型上会被系统栏压住。

### A7 · 设置子页输入框被键盘盖住
`SettingsComponents.kt:238-243`

```kotlin
Column(modifier = Modifier.fillMaxSize().padding(innerPadding)
    .verticalScroll(rememberScrollState()).padding(bottom = 32.dp))
```
`SettingsSubPage` 的 `Scaffold` 只吃系统栏 inset，**不含 IME**；而两个 provider 子页各有 5 个输入框（接口地址 / API Key / 模型 / 音色 / 响应格式），键盘弹起时下半部分（含 `测试并试听` / `保存并启用`）在键盘之下，且滚动容器仍延伸到 IME 之下，"滚动到焦点字段"也救不回来。
`grep imePadding` 在三个设置 UI 文件里零命中；`LibraryDrawScreen.kt:96` 与 `LibrarySelectionBar.kt:40` 是仓库内已验证的写法。

### A8 · TTS 的 API Key 是唯一没 `singleLine` 的密钥框
`SettingsProviderPages.kt:237-262`

```kotlin
OutlinedTextField(value = …, label = { Text("API Key") },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
// ↑ 整个块内无 singleLine；OCR 的同款字段在 :468 有 singleLine = true
```
回车会把换行写进 Key，随后被塞进 `Authorization: Bearer …`（`OpenAiCompatibleTts.kt:227`），请求构造/执行抛错，最终以 `网络请求失败，请检查 URL 与网络`（`:199-200`）报出——指向完全错误的方向。

### A9 · 多选选择的退出会静默清空
`LibrarySelectionStore.kt:28-32`，调用点 `LibraryScreen.kt:87,102`、`LibraryListsScreen.kt:73,89`、`HearWriteApp.kt:120`、`HomeScreen.kt:166`

```kotlin
/** Enter/leave multi-select mode; leaving drops the selection. */
fun setActive(value: Boolean) { if (!value) _selectedIds.value = emptySet(); _active.value = value }
```
6 条退出路径（顶栏 `完成`、底栏 `退出多选`、硬件返回、开始抽词、回到首页）全部走这里，无确认、无撤销。更糟的是标签：`完成` 读起来是"确认继续"，用户点了却丢掉整份跨分类勾选——真正的继续动作是旁边那个 `抽词听写` 按钮。在一个 `清空错词本` / `清空历史记录` 都有确认对话框的 app 里，这个不一致很刺眼。

### A10 · 词库预览主按钮无忙碌态，点按静默失效
`LibraryPreviewScreen.kt:155-166`

```kotlin
Button(onClick = { startScope.launch { viewModel.startLines()?.let(onStartDictation) } }, modifier = Modifier.weight(1f)) {
    Text("听写本词表（共 ${current.size} 词）")
}
```
`startLines()` 先 `enrichSettled.await()`；冷启动首个英文词表会触发 3.3 MB ECDICT 解析（15–25 MB 堆、数百 ms，`DictionaryRepository.kt:31-33`）。这段窗口里点按被 `startGate.tryLock()` 静默吃掉，按钮外观完全不变。
首页对同一问题有标准解（`HomePlaybackPanel.kt:156,162-168`：`enabled = !starting` + 转圈 + `整理词表…`）——这是与仓库自身惯例的差距，不是新要求。文件自己的 KDoc（`:309-311`）已经为行内 `——` 占位论证过这个窗口，唯独按钮没有。

### A11 · 主题选择无 radio 语义
`SettingsComponents.kt:254-268`

```kotlin
Surface(onClick = onClick, …, border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null)
```
`selected` 只影响边框；无 `selectableGroup()`、无 `selected` 语义、无 `Role`。TalkBack 无法告知当前是哪套主题（亮/暗/跟随系统）。

### A12 · 错词本抽屉分组 key 可能重复 → 崩溃
`HomeDrawers.kt:231`

```kotlin
item(key = "src_${group.sourceTitle.orEmpty()}_${group.jumpCategory.orEmpty()}") {
```
分组按**原始 source id** 分（`HomeViewModel.kt:799-819`），key 却用**解析后的标题**。而 `SourceTitles.kt:36-40` 的降级路径会产生常量标题：`未知来源`、`多词表`、`多词表（N 个词表）`。同一分类下两个未解析来源（或两个成员数相同的 `multi:` 标签）→ key 完全相同 → `IllegalArgumentException: Key "…" was already used`。**被承诺"优雅降级、永不丢行"的路径反而会崩**。

---

## 2. P2 清单（一致性收口）

### B1 · 补齐 `Type.kt` 字阶（一次改动覆盖 85% 文字）
`theme/Type.kt:12-47`。用到的 12 个样式里只定义了 5 个：

| 样式 | 调用处 | 是否已定义 |
|---|---|---|
| `bodyMedium` | 32 | ✗ |
| `bodySmall` | 23 | ✗ |
| `labelMedium` | 18 | ✗ |
| `titleMedium` | 14 | ✗ |
| `bodyLarge` | 12 | ✓ |
| `titleLarge` | 7 | ✗ |
| `labelSmall` | 4 | ✗（默认 11sp，用于词数徽章、趋势图轴标签、存疑注记——CJK 偏小） |
| `labelLarge` | 4 | ✗ |
| `displaySmall` / `displayMedium` / `headlineSmall` / `titleSmall` | 3 / 1 / 1 / 1 | ✓（合计 120） |

要么补齐（中文行高 + 字距随 `bodyLarge` 的口径），要么删掉"定制字阶"的伪装。另：`HomeWordSection.kt:331-332` 与 `LibraryPreviewScreen.kt:289-290` 手工拼了两次 `bodyLarge + SemiBold` 的"行词头"样式，`DictationGradePane.kt:336-337` 又来一次——缺一个 token。

### B2 · `SnackbarHost` 取代 15 处 `Toast`
`grep Toast.makeText`：`HomeScreen.kt` 8（全部内联全限定名）、`SettingsProviderPages.kt` 2、`SettingsScreen.kt` 2、`DictationScreen.kt` 1、`HomeDrawers.kt` 1、`StatsScreen.kt` 1；`Snackbar` 全仓 **0**。
后果：确认信息不锚定在触发处、不可操作、不可撤销；而**全 app 最该被看见的一条**——"密钥未能加密保存，将以明文保存在本机"（`SettingsViewModel.kt:966-969`）——是 `Toast.LENGTH_LONG`。
各 ViewModel 已经暴露了一次性状态流，接 `SnackbarHostState` + `LaunchedEffect` 是直接替换。

### B3 · 开关行/复选行统一 `toggleable`
`SettingsScreen.kt:253-265, 293-304`、`HomePlaybackPanel.kt:129,144`、`LibraryListsScreen.kt:114-125`、`LibraryScreen.kt:288-294`
一个设置是两个焦点停靠点（行 `clickable` + `Switch` 自带 `onCheckedChange`），TalkBack 会播报"按钮"和"开关"两遍，而行本身不报开关状态。
更隐蔽的一处：`HomePlaybackPanel.kt:89` / `DictationScreen.kt:774` 把 `contentDescription = "听写间隔秒数"` 加在**数值 Text** 上——叶子节点上 `contentDescription` 会**替换** `text`，于是"当前间隔是多少"恰恰是唯一读不到的信息。同款问题在 `LibraryScreen.kt:293` 的 `选择词表 X` 覆盖了复选框的勾选态播报。
正确范例就在仓库里：`SettingsComponents.kt:173` 的 `RadioButton(selected = selected, onClick = null)`（指示器纯视觉，行负责语义）。

### B4 · Insets 收口（首页底部重复计入导航栏）
`HomeScreen.kt:189-213`、`:332-339`

`contentPad` 取自 `onSizeChanged { panelHeightPx = it.height }`，而 `onSizeChanged` 是面板链的**第一个** modifier，量到的高度**已包含**面板自身的 `.padding(bottom = 12.dp).navigationBarsPadding()`；内容列同时又加了 `.navigationBarsPadding().padding(bottom = contentPad)`。键盘弹起时 `imeBottom` 本身也已跨越导航栏——导航栏高度被计入两次。
**注意**：这套"内容底部内边距在键盘高度与面板高度之间动画、面板作为 overlay 从键盘后升起"的编排是刻意为之、且有注释说明是为了修"填满后弹回"的抖动（上游 bug），**修 B4 时不要把它简化掉**，只需改掉重复计数。

### B5 · 单位与格式化统一
- 间隔读数 `String.format(Locale.ROOT, "%.1fs", intervalSec)` → `7.0s`（`HomePlaybackPanel.kt:86`、`DictationScreen.kt:771`），而同一屏的倒计时播报是 `剩余 8 秒`、成绩卡是 `用时 3 分 20 秒`。一屏两套单位制。
- 两个时长格式化器：`DictationScreen.kt:93-94`（两档，无小时）vs `StatsScreen.kt:69-72`（三档，含小时）。75 分钟的复习轮在成绩卡显示 `75 分 30 秒`、在统计页显示 `1 小时 15 分`。
- 日期两套：趋势轴 `M/d`（`StatsScreen.kt:59`）vs 记录行 `MM-dd HH:mm`（`:60`），且在无上限的记录里不显示年份。

### B6 · 5 个语义 token 全仓无人读——**已拍板：换色后接上（§0.1 D-2 + D-3）**
`theme/Color.kt:96-107`、`theme/Theme.kt:105-137`

`cjkAccent` / `cjkContainer` / `onCjkContainer` / `successContainer` / `onSuccessContainer` —— grep 全仓，**除 `theme/` 外零引用**。KDoc 承诺它们用于"汉字标签、组词提示行"，即设计系统里"汉字身份"的强调色没有任何调用点。
配套死代码：`DialCenter(isCjk = …)` 的参数 `isCjk`（`DictationScreen.kt:644`）声明后从未被读——而表盘正是拼音提示（`:682`）与组词释义（`:690`）渲染的地方，也就是朱砂色被设计出来要用的那一个界面。

**决策：接上，删掉 `isCjk` 这个死参数——它从"声明未读"变成实际判据。** 实施要点：

| token | 接到哪里 | 具体 |
|---|---|---|
| `cjkAccent` | 汉字提示层（表盘内） | `DialCenter` 里 `entry.pos`（拼音）与汉字释义（组词）两行的 `color`；`isCjk` 为真时用 `cjkAccent`，否则维持 `onSurfaceVariant`（英文的 POS/释义**不用**朱砂——`Color.kt` 注释明确"never used for English/POS content"） |
| `cjkAccent` | 汉字行标记 | 首页/预览的展示列表行，汉字词头旁的标记（当前无标记，加一个 3dp 朱砂竖条或 `汉字` 小徽章的最小形态即可；**只做表盘那一处也可接受**，两处都做需一次性视觉走查） |
| `cjkContainer` + `onCjkContainer` | 同上的容器态 | 若加 `汉字` 徽章，则用它做底色/文字色（与 `primaryContainer`/`onPrimaryContainer` 同一用法） |
| `successContainer` + `onSuccessContainer` | 听写页"正确"徽章 | 批改页 `GradeRow` 的 `正确` 标签（当前是裸 `Text` + `success` 前景色，`DictationGradePane.kt:307, 375-379`），或成绩卡的 `正确 N 词` 一行；成对使用，保证前景/底色同源 |
| `isCjk` | 不再是死参数 | 作为上述表盘配色的判据；若最终表盘不接朱砂，则**删除该参数与 3 个 `cjk*` token**（不得留声明未读） |

约束（取值按 §0.1 D-3 已定，均为计算实测）：

- `cjkAccent` 新值 `#9C4A22`（浅）/ `#DB9A6B`（暗）：白卡 6.15:1、纸底 5.75:1、暗卡 6.82:1，均过正文门槛；与错误红 ΔE₀₀ 11.6 / 16.1，与收藏金 14.8 / 17.2——三项约束同时满足。
- `cjkContainer` 新值 `#E8DFC9`（浅）/ `#4A3220`（暗）：与错误容器 ΔE₀₀ 16.2 / 20.5；其上的 `onCjkContainer` `#3A1B0C` / `#F8D9D2` 对比度 11.80 / 8.96。
- `successContainer` 的用法与 `errorContainer` 一致（底色 + `on*` 前景），不得只取其一。
- **换色与接线同一提交完成**：如果只改 token 取值不接线，界面上看不出任何变化，容易被误判为"已修复"。

### B7 · 成功态用专属 token；补 `heading()` 语义
- `SettingsProviderPages.kt:1196-1200`、`:522-525`：`连接成功` / `已播放试听` 用 `colorScheme.primary`，而主题已有 `hearWriteSemantics.success`（`DictationScreen.kt:216` 在用）。
- `SettingsComponents.kt:55-59`：`SettingsSectionHeader` 是裸 `Text`，无 `semantics { heading() }`；`SettingsProviderPages.kt:44` 已经 `import heading` 却从未使用——说明本意如此，只是没接上。

### B8 · 词库交互的一致性
| 项 | 位置 | 现状 |
|---|---|---|
| 两个"返回"语义冲突 | `LibraryScreen.kt:73` vs `:81-84`；`LibraryListsScreen.kt:59-68` | 硬件返回只退出多选，顶栏箭头却 pop 屏幕（仍处于多选态，勾选还在）——与注释"a stale selection can never survive a back navigation"矛盾 |
| `载入草稿` 销毁浏览上下文 | `LibraryPreviewScreen.kt:150-154` + `HearWriteApp.kt:151-158` | 直接 `popBackStack(HOME)`，用户失去"在哪个分类哪张表"的位置，要重新走 4 次点击 |
| 起始词重置 ✕ 只有 40dp 且条件渲染 | `LibraryPreviewScreen.kt:129-141` | 低于 48dp；`从第 N 词开始` 因 `Spacer(weight(1f))` 在出现/消失时横向位移 |
| 列表行副标题随词数到达跳高 | `LibraryListsScreen.kt:111-113` | `wordCounts` 逐行异步填充，未到达时 `subtitle = null` → `ListRow` 不渲染该行 → 26 行的分类里可见回流。预览页已有 `——` 占位解法（`LibraryPreviewScreen.kt:308-318`） |
| 搜索可能给同一张表两个勾选框 | `LibraryScreen.kt:230-256` | `labelHits` 与 `wordHits` 用 `l_`/`w_` 前缀 key 避免崩溃，但同一 `list.id` 可同时出现在"词表"和"词条"两节，两个 Checkbox 驱动同一份 `selectedIds` |
| 空分类无空态 | `LibraryListsScreen.kt:103` | 资产扫描为空时直接渲染空 `LazyColumn`，白屏无解释（其他列表面都有 `EmptyHint`） |

### B9 · 设置页整合（本片最大杠杆）
`SettingsProviderPages.kt` 1233 行里，TTS 与 OCR 两套 provider 表单是**近乎逐行复制**：预设单选列表（`:197-206` vs `:425-436`）、URL/Key/模型三件套（`:228-275` vs `:444-495`）、测试按钮 + 状态块、`清除配置 / 保存并启用` 行、以及**逐字节相同**的清除对话框（`:382-383` vs `:571-572`）。四个下拉（`SystemVoiceDropdown:806`、`TtsApiKindDropdown:946`、`MimoVoiceDropdown:1030`、`EdgeVoiceDropdown:1104`）只差数据源与标签。

已经出现的漂移（用户能看到的）：
- TTS 保存失败仍弹 `已保存发音配置`（`:362-368`），因为 `saveTtsConfig()` 吞掉异常且返回 Unit；OCR 已被加固成返回 Boolean 并据此决定是否弹（`:544-553`）。
- TTS 表单不校验 base URL（只查非空），OCR 校验 `http(s)://`——scheme 漏写的 URL 能保存并启用，之后以通用网络错误报出。
- OCR 的测试状态块内联复制（`:496-532`），TTS 复用共享组件（`:1170`）。
- 没有"正在使用"标记：预设行只标 `已保存`，选中的单选是**草稿**目标——切一下 chip 高亮就离开了实际生效的配置。
- 草稿只在 ViewModel 内（`SettingsViewModel.kt:135-136, 161`），离开设置页即丢，无未保存提示、无确认。

---

## 3. P2/P3 · 状态覆盖与内容层

### C1 · 缺失的状态
| 状态 | 位置 | 现状 |
|---|---|---|
| 首页草稿播种 | `HomeViewModel.kt:107, 233-238` | 从 `""` 异步播种，无加载态 → 每次进首页先闪一帧空编辑器/`共 0 词`，这窗口里 `开始听写` 还会说"请先输入单词列表" |
| 听写准备中 | `DictationScreen.kt:183-186` | 裸 `CircularProgressIndicator`，而首页同样等待时写的是 `整理词表…` |
| 语音失败 | `DictationUiState`（`DictationViewModel.kt:53-68`） | **没有任何字段**。`speak1` 失败按设计不重试、跳过间隔、继续推进（`DictationEngine.kt:319-328`）——于是"全程无声"与"一切正常"在界面上完全一样 |
| OCR 进行中 | `HomeScreen.kt:224-247`、`DictationGradePane.kt:92-101` | 两处进度呈现不同（14dp 药丸 vs 全宽 LinearProgress），且**都没有取消入口**，读超时 30s。药丸还会被 sheet 的遮罩挡住；重开 sheet 看到两个灰按钮但没有任何原因 |
| 系统音色为空 | `SettingsProviderPages.kt:705-706` | `if (!loading && zhVoices.isNullOrEmpty() && enEmpty && previewState == Idle) return` → 什么都不渲染，连 `英文使用默认音色` 开关一起消失 |
| 抽词池读表失败 | `LibraryDrawViewModel.kt:83-88, 98-103` | 单表失败被吞成"该表从池里消失"，只有 logcat 知道 |

### C1b · 首页（Home）补充

| 项 | 位置 | 说明 |
|---|---|---|
| OCR 进度药丸被挤到不可读 | `HomeScreen.kt:222-247` | 头部 Row 里 wordmark（≈190dp）+ 3 个 48dp 图标（144dp）先量，360dp 屏幕上药丸只剩 ≈0dp（411dp 机上 ≈50dp），而它的内在宽度 ≈90dp、文字 `maxLines = 1 + Ellipsis`——OCR 运行期间**全屏唯一的进度反馈**被截成几个字形或完全不可见，末尾的菜单图标也被挤到 48dp 以下。宽度为字形度量估算 `[推断]`，约束算术由代码直接得出 |
| 清空草稿一键抹掉持久数据 | `HomeWordSection.kt:208-220` | `清空` → `onDraftChange("")` → 500ms 去抖写 DataStore。未起过听写的草稿不在历史里（历史行只在起听写时写），清掉即永久丢失；同时 `英文示例`/`汉字示例` 也是静默覆盖。而抽屉里的 `清空` 反而有确认对话框 |
| 展示态行点击二义 | `HomeWordSection.kt:288-297` | 点第 40 行读释义会**同时**把听写起点移到第 40 词，唯一可见证据在屏幕最底部的面板上；展开态又随草稿变化重置 |
| `开始听写` 的可用性语义 | `HomePlaybackPanel.kt:153-160` + `HomeScreen.kt:321-326` | `enabled` 只看 `starting`：0 词时外观与就绪态完全相同，唯一解释是点完之后的 Toast（还会在草稿播种窗口里误报）；OCR 进行中也不禁用，于是"对着 OCR 前的草稿起听写"→ 草稿随后被识别结果覆盖（`:741-744`） |
| 载入历史/收藏后抽屉不关 | `HomeDrawers.kt:104-107, 165-168` | 结果只以 2s Toast 出现在抽屉**后面**，用户得手动滑走；而同为"载入内容"的 `查看词表`（`HomeScreen.kt:461-464`）会关抽屉——同一种动作三种收尾 |
| 首页无法重置起始词 | `HomePlaybackPanel.kt:102-115` | 词库预览有 `重置为从第 1 词开始`（`LibraryPreviewScreen.kt:129-140`），首页没有；误点第 30 行只能翻回去找第 1 行（`HomeViewModel.kt:351` 的 `adjustStartIndex` 是死代码） |
| 进入编辑态不聚焦 | `HomeWordSection.kt:88-92` | 自动聚焦只在草稿为**空**时触发；已有词表时点 `编辑` 得到的是一张没有光标、不弹键盘的文本域，还要再点一次 |
| `更多` sheet 四行无间距 | `HomeScreen.kt:344-372` | 行容器色 `surfaceContainerLow` 与 sheet 容器同色（`Color.kt` 里这就是 bottom sheet 默认色），四行读成一整块；`MenuRow` 也无分隔 |

### C1c · 其余 P3（速查，按界面分组）

- **首页**：列表滚动到底多留 72dp 无解释空白（`HomeWordSection.kt:269`）；起始词 `共 N 词` 三处重复（头部徽章 / footer / 按钮徽章）；`清空` 在 0 词时仍可点；`HearWriteWordmark` 无 `maxLines`（`Wordmark.kt:55`）；`Icons.Outlined.Cancel` 一图标两义（错词本 vs 删除行）。
- **听写页**：成绩卡 `再听一遍` / `复习错词` 两个等重实心按钮、无副文案说明范围差异；`拍照批改` 不做预检（未配置 OCR 要等拍完一圈才报）；`重新拍照` 在同屏出现两次且启用条件不同（`DictationGradePane.kt:125-127` vs `:277-279`）；批改行 `itemsIndexed` 无稳定 key；EXTRA 的非交互图标挂了整句 `contentDescription`；transport 行只有播放器有文字标签，`结束` 与前后跳同权重。
- **词库**：三种叫法一功能（多选词库/多选词表/多选模式）；`已选 N 个词表` 报的是表数而非用户关心的词数；chip 阶梯 `10/20/全部` 的门控在 21 词池下给不出 20；`已合并 N 个跨表重复词` 措辞像用户动作；分类卡 `minLines = 2` 让 3 字分类名也占两行高度且网格只有"词表数"这一个数量信号；`ListRow` 标题 1 行截断而真实标签到 11–12 个 CJK 字。
- **设置**：`清除配置` 与主按钮同排且无破坏性样式；展开区无 `animateContentSize` 也不自动滚入视野；键位字段无 `imeAction = Next`；`清空发音缓存` 在"无缓存"时仍可点并照样弹"已清空"；清除生效中的 TTS 预设还会把发音来源退回有道（对话框没说）；hub 滚动位置在子页往返后归零；provider 表单的对话框状态用 `remember`（hub 的用 `rememberSaveable`），旋转即丢；`关于` 页开源许可只给了一个 GitHub 链接、无应用内 GPL 全文；logo 磁贴写死浅色 `#EFF6FD`（深色模式下是亮方块，注释称有意为之，需一次决策）。
- **统计**：`连续天数` 无单位、无"今日已打卡"提示；同屏两种日期格式且从不显示年份；`高频错词` 截到 10 个无提示、两卡的行都不可点；`StatFigure` 数值无 `maxLines`、无等宽数字（`累计用时` 是最宽的格子）；图表无刻度、无点按查值。
- **导航/代码**：`STATS`/`SETTINGS`/`LIBRARY` 缺 `launchSingleTop`；`HearWriteApp.kt:44`、`HomeViewModel.kt:351` 死代码；4 处未用 import（`HomePlaybackPanel.kt:20`、`StatsScreen.kt:50`、`SettingsProviderPages.kt:44`、`SettingsAboutPage.kt`）；`openUrl` 失败静默（`SettingsAboutPage.kt:49-53`）；剪贴板/Toast 无撤销通道。

### C2 · 听写页（核心界面）的其他问题
| 项 | 位置 | 说明 |
|---|---|---|
| 词语也会被截断 | `DictationScreen.kt:675-681` | `maxLines = 2` + Ellipsis 且**没有展开入口**（释义反而有 `展开全部`）。已上架词表里有 `doing morning exercises`(23)、`Dragon Boat Festival`(20)、`have a stomachache`(18) 这类条目。释义的展开判据是字符数 `> 26`（`:656`），而仓库自己有全角感知的宽度度量（`SpeechText.kt:61-63`） |
| 204dp 圆形溢出裁切 | `DictationScreen.kt:658-670` | `Surface` 会 `clip(shape)`；词(2×52sp) + 词性 + 释义(2 行) + `展开全部` 按钮 + 32dp 内边距 ≈ 200–252dp `[推断]` vs 可用 204dp，中心对齐 → 首行词或展开按钮可能被圆裁切，且无处滚动 |
| 隐藏态文案在暂停时说谎 | `DictationScreen.kt:713-714` | 盘内恒显示 `听写中`，而顶栏 `StatusPill` 同步显示 `已暂停`（`:220`） |
| 满分反而信息更少 | `DictationScreen.kt:461-467` | `if (ui.runWrongCount > 0)` 才渲染 `正确 N 词 · 错词 M`；全对时只显示 `共 N 词 · 用时 X`，无法区分"0 错"与"这段没渲染" |
| 同屏"错词"两义 | `DictationScreen.kt:461-467` vs `:516` vs `:302-307` | 成绩线的"错词"是本场的，`错词本（N）` 是全局长度的，顶栏红色药丸显示的是**全局**数量却挂在"表现"的位置 |
| 标记错词不可撤销 | `DictationViewModel.kt:440-476` | 全流程只有一个 `markCurrentWrong`，本场内无法反标记；重复按下与首次按下视觉完全一致（只有盘上的 400ms 红闪，而手在下方按钮上）。两个按钮无水平内边距（`:364-368`）且阈值翻转——主色块是破坏性的 `标记错词`，而 `显示词语` 反而是次要的 outlined |
| `FlowRow` 非惰性渲染全局错词本 | `DictationScreen.kt:521-541` | 书是从 Room 全量播种的（`:274-278`），无上限、无"查看更多"，`FlowRow` 会组合每一个 chip，且处于 `verticalScroll` 内 |
| 计时数值占位符与注释不符 | `DictationScreen.kt:611-616` | 占位文本写死 `"8 秒"`，但 `MAX_INTERVAL_SEC = 10.0` 会渲染 `"10 秒"`（多一个字形）——注释声称"行尺寸永不变化"不成立 |
| 改间隔会重置正在跑的倒计时 | `DictationEngine.kt:206-211` | 是文档化的意图，但误触 `+` 会静默推迟下一个词 |

### C3 · 统计页
| 项 | 位置 | 说明 |
|---|---|---|
| 0 词的柱子**永远不可达** | `StatsScreen.kt:292-305` | `Modifier.fillMaxHeight(f)` 同时设 min/max，`f = 0f` → 高度 0 → `surfaceVariant` 的"休息日"分支是死代码；小幅值日同样被舍入吞掉（1 词 / 最大 300 词 → 0.4dp → 0）。图上看不出"哪天没听写"，只看到任意空隙 |
| `最近记录` 无上限且非惰性 | `StatsScreen.kt:170-175`、`HearWriteDatabase.kt:115` | `observeAll()` 返回整张表，`sessions` 从不裁剪（对比 `history.trimTo`），UI 在 `verticalScroll` 里 `forEachIndexed` 全量组合——全年每天一场 ≈ 350 行 ≈ 23000dp，是全 app 唯一非 lazy 的长列表 |
| Room 读失败 = "暂无听写记录" | `StatsViewModel.kt:97-102` | `.catch { emit(emptyState(loading = false)) }` → 页面显示空态文案。意图（永不空转）正确，选的状态错误；AGENTS.md 要求"异步失败要在 UI 上暴露" |
| 图表对明眼人不可读 | `StatsScreen.kt:309-325` | 只有首尾两个日期标签，无刻度、无最大值标注、无 weekday，全文件无 `clickable`——精确数值只有 TalkBack 能听到（`:286-289` 每根柱都有描述） |
| 卡片缩进不一致 | `StatsScreen.kt:213-217` | 概览卡左右各 16dp（文字落在 32dp），其余 `SettingsCard` 齐平（文字 16dp）——同一滚动里混着两条左基线 |
| 高频错词静默截断且无跳转 | `StatsViewModel.kt:51, 93` | `TOP_WRONG_LIMIT = 10` 无"显示前 10"提示；两张卡的行都没 `onClick`（对比 `HomeDrawers.kt:255-259` 的 `查看词表` 是可点的） |

### C4 · 抽词页 / OCR 链路
| 项 | 位置 | 说明 |
|---|---|---|
| 320dp 宽度下溢出 | `LibraryDrawScreen.kt:103-140` | 一个不换行 `Row` 装固定 140dp 输入框 + 最多 3 个 chip + `spacedBy(8.dp)`，内在宽度约 380dp。全 app 唯一横向堆叠的控件行 |
| 池摘要滚出视野 | `LibraryDrawScreen.kt:186-206` | `已选 N 个词表 · 合计 M 词` 是滚动项，而它论证的确认按钮固定在底栏——选中很多表时摘要正好滚出屏幕 |
| 抽词页无逐词预览 | `LibraryDrawScreen.kt` | 起听写前看不到抽出什么（规划里记为"未做"） |
| 识别语言是全流程唯一不持久化的设置 | `HomeScreen.kt:129` | `rememberSaveable` 且默认 `ENGLISH`；其余同类设置全部落 DataStore。汉字表用户每次冷启重选，首次用户拿英文提示语去识汉字表，得到的是"未识别到英文单词" |
| 成功识别直接覆盖草稿 | `HomeViewModel.kt:676-681` | 无确认、无撤销，且未开始听写的草稿不在历史里（历史行只在起听写时写，`HomeViewModel.kt:577-578`），持久化的草稿同步被覆盖 → 原内容无法找回 |
| 配置类错误无出路 | `HomeViewModel.kt:660-662` + `HomeScreen.kt:576-581` | 文案说"请先在设置中配置 OCR 服务"，但卡片只有 `关闭`（`ocrRetryable` 在网络阶段之后才置位，此分支为 false）——告诉你去找设置，却不给入口（扫描 sheet 里反而有 `去设置`） |
| 裁剪把手不可见且偏小 | `OcrCropOverlay.kt:190-194, 232-233` | 只画了 4 个角点（7dp 圆，有一半点在选区外），四条边的 resize 热区**完全无视觉**；`cornerTol = 28.dp` / `edgeTol = 22.dp` 低于 48dp 指引 |
| 触到下限/边界无反馈 | `OcrCropOverlay.kt:337-383` | `coerceIn` 到 `OCR_CROP_MIN_SIDE_PX` 或图片边界时就是不动，无震动、无边框闪、无文案——分不清"已经最小了"和"手指滑了"。仓库里已有 `Haptics`（`data/Haptics.kt:22-24`） |
| 只能框选，不能缩放 | `OcrCropOverlay.kt:264` | 唯一的指针手势是 `detectDragGestures`；"重置为整张图片"只存在于 TalkBack 自定义操作里（`:225-227`）。手机拍的笔记本一页 4096px 缩到 ~350dp 后一行手写约 2dp 高，没有放大镜很难框准 |
| 免责声明印两遍 | `DictationGradePane.kt:191-193` 与 `:205-209` | 第一句已逐字包含 `OCR_DISCLAIMER`，相隔 15dp |
| `识别语言` 标签名不达意 | `OcrScanSheet.kt:55-71` | 它选的是**词表类型**（英文单词 vs 中文生字/词语，见失败文案 `OcrService.kt:738-742`），不是"照片里的语言"——生字表带拼音时这个歧义正是要避免的 |
| sheet 无滚动容器 | `OcrScanSheet.kt:47-51` | 未配置时 sheet 里会有 4 行说明文字在按钮之前，大字号下按钮可能被推出 sheet 外 |

### C5 · 导航与信息架构
| 项 | 位置 | 说明 |
|---|---|---|
| 统计藏两层 | `HomeScreen.kt:368-371` | 词库/设置是顶栏 1 击图标，`听写统计` 却在 `更多` 里（2 击）。结束页也没有指向"本次刚写入的记录"的入口 |
| 双击可能叠栈 | `HearWriteApp.kt:52,57,58` vs `:98,110` | `LIBRARY_DRAW` 用了 `launchSingleTop`，`STATS`/`SETTINGS`/`LIBRARY` 没有——快速双击会压两层，返回一次还停在同屏 |
| 死代码 | `HearWriteApp.kt:44-46`（`startDictation` 无引用）、`HomeViewModel.kt:351`（`adjustStartIndex` 无引用）、`HomePlaybackPanel.kt:20`（未用 import）、`StatsScreen.kt:50`（未用 import）、`SettingsProviderPages.kt:44`（未用 import） |

### C6 · 大屏 / 横屏 / 大字号
- 无 `WindowSizeClass`（全仓 0 命中），分类网格固定 `GridCells.Fixed(2)`（`LibraryScreen.kt:165`），首页编辑器在全宽展开（折叠屏上 CJK 行宽过长）。
- 表盘写死 248dp/204dp（`DictationScreen.kt:585, 660`），而舞台是 `weight(1f)` 非滚动 `Box`；横屏（高度 ≈ 360dp 减去 64dp app bar 与 ~150dp 面板）时表盘会坍缩或被裁切且无处滚动。
- `DictationScreen.kt:116-118` 的 `showWord`/`metaExpanded`/`exitDialogVisible` 是普通 `remember`：旋转会重置词语显示态，并**无回答地关掉** `结束听写？` 确认框（首页同类状态做对了，`HomeScreen.kt:128-129` 用 `rememberSaveable`）。
- Manifest 无 `android:configChanges`（合规），也没有 `windowSoftInputMode`。

### C7 · 文案与术语
- 三种叫法指同一功能：`多选词库`（`LibrarySelectionStore.kt:9`、ROADMAP）、`多选词表`（入口图标 `LibraryScreen.kt:90`）、`多选模式`（页内提示 `LibraryScreen.kt:133`）。
- 面向学生/家长的界面出现工程术语：`OCR`、`API Key`、`Base URL`、`Key`、`/audio/speech 返回的音频格式（mp3/wav…）`（`SettingsProviderPages.kt:330` 等 9 处）。
- `已合并 N 个跨表重复词`（`LibraryDrawScreen.kt:197-203`）读起来像用户做过的动作，实为自动去重。
- `连续天数` 是裸数字（`StatsScreen.kt:245`），无法区分"连续 3 天（今天还没听写）"与"今天已打卡"——domain 层已能区分（`domain/Stats.kt:114-115`）。
- 首页 `更多` sheet 的 KDoc（`HomeScreen.kt:70`）写的是 收藏/历史记录/**词库/设置**，代码里是 收藏/历史记录/**错词本/听写统计**。
- 同一个 ✕ 图标两义：`Icons.Outlined.Cancel` 在 `更多` 里是"错词本"，在词表行里是"删除这个词"（`HomeScreen.kt:364` vs `HomeWordSection.kt:356`）。
- 全角标点扫描通过（中文句子内无 ASCII 逗号/句号），`词/场/个/秒/分` 单位一致——唯一例外是 `7.0s`（见 B5）。

---

## 4. 动效（几乎从零）

全 app 仅 **2 处**动画调用：`HomeScreen.kt:191`（`animateDpAsState`）、`DictationScreen.kt:650`（`animateColorAsState`），0 处 `AnimatedVisibility`/`Crossfade`/`AnimatedContent`/`animateItem`/`rememberInfiniteTransition`。

| 项 | 位置 | 方向 |
|---|---|---|
| 舞台硬切 | `DictationScreen.kt:318-413` | 「听写中 → 成绩卡 → 批改页」是全 app 最主要的状态转换，用 `AnimatedContent`（淡入 + 轻微缩放，~200ms） |
| 编辑/展示硬切 | `HomeWordSection.kt:131-176` | 两个表面被刻意做成同一张卡（注释 `:158-159`），却瞬间换结构；用 `Crossfade`/`animateContentSize` |
| 倒计时弧线 | `DictationEngine.kt:25`（50ms tick）、`DictationScreen.kt:571-585` | 直接按原始状态重绘 ≈20fps，短间隔下可见阶梯；实时改间隔时 deadline 被重写，弧线会跳。可用 `withFrameNanos` 或 `animateFloatAsState` 插值 |
| 列表增删 | `DictationScreen.kt:521-539`、`HomeWordSection.kt:269`、`LibraryScreen.kt:164` | 无 `animateItem()`，chip 增删、行删除、搜索结果都是弹变 |

**已正确**：两处动画走的是标准 Compose API，会读平台的 `MotionDurationScale`，因此**遵循"移除动画"的无障碍设置**；唯一例外是 `DictationViewModel.kt:473` 的 `delay(MARKED_FLASH_MS)`——但它配合的是震动反馈，不是动效缺陷。

---

## 5. 修复计划

### 阶段 A — 缺陷修复（12 项）
`A1`–`A12`（§1）。全部是"用户能感知的错误"，且都是 ≤20 行的定点改动。其中 `A2` 按 §0.1 的 D-1 恢复表盘点按（行为变化 + 隐藏态提示文案改回 `点按显示词语`），其余 11 项**无视觉变更**。**建议单独发一个 patch release**。

### 阶段 B — 一致性收口（9 项，一次做完收益最大）
`B1`（字阶，覆盖 85% 文字）+ `B2`（Snackbar 取代全部 Toast）+ `B3`（`toggleable` 统一）+ `B4`（insets 去重）+ `B5`（单位/格式化合一）+ `B6`（**换色 + 接上朱砂与 successContainer，`isCjk` 转为实际判据**，见 §0.1 D-2/D-3）+ `B7`（success token + `heading()`）+ `B8`（词库交互一致性）+ `B9`（provider 表单抽公共件）。

### 阶段 C — 状态覆盖与内容层
`C1`（缺失状态：首页草稿加载态、听写准备态、语音失败提示、OCR 可取消、空态补齐）→ `C2`（听写页：词语展开、表盘圆裁切、暂停文案、满分成绩、错词不可撤销、错词本 chip 惰性化）→ `C3`（统计页：柱子下限、列表惰性/上限、错误态、图表可读）→ `C4`（抽词行 `FlowRow`、摘要固定、OCR 语言持久化、覆盖草稿前确认、错误卡加 `去设置`、裁剪把手与缩放）→ `C6`（`WindowSizeClass`、表盘随约束、旋转不丢状态）。

### 阶段 D — 打磨（按喜好）
动效（§4）、导航 `launchSingleTop`、结束页 → 本次统计入口、术语统一（C7）、死代码清理、系统栏图标随自选主题、动态取色（Material You）。

### 依赖与顺序
- `A2` 已按 §0.1 D-1 拍板为"恢复表盘点按"，**不再是待决项**；`AGENTS.md:94` 契约不需要改。
- `B6` 已按 §0.1 D-2 拍板为"接上"；`isCjk` 参数随之从死代码变成判据（若最终走另一条路，则须同时删参数与 3 个 `cjk*` token，不得留声明未读）。
- `B9` 是 `C4` 的前置（OCR 表单改动落在抽取后的公共件上，避免第三次复制）。
- `B1` 放最前：它改变几乎所有页面的文字度量，晚做会让其他视觉微调白做。
- 其余条目相互独立。

---

## 6. 验证方案（不依赖截图）

截图被排除后，验证走**布局树 + 语义树**；这恰好也是无障碍的验收手段。

```bash
# 1) 语义断言（A 阶段主验收）
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
#    断言目标节点存在且携带状态，例如：
#      content-desc="起始词" / selected="true"      → A1
#      主题卡片 checked/selected 属性               → A11
#      复选框节点同时含 checked 与自身名称          → B3

# 2) 遮挡断言（A6 / A7 / B4）
#    取 dump 中各节点的 bounds，与 `adb shell dumpsys window displays` 的
#    导航栏 / IME 高度比较，断言无按钮 bounds.bottom > 导航栏上沿。

# 3) 几何断言（C2 表盘裁切 / C4 320dp 溢出 / C3 零高柱）
#    在 dump 出的 bounds 上直接断言：子节点 bounds 落在父节点之内；
#    0 词日期的柱高 ≥ 下限值。纯几何，无需任何像素。

# 4) 大字号 / 横屏
adb shell settings put system font_scale 1.5
adb shell settings put system user_rotation 1
#    重新 dump 后重复 (3)。

# 5) 回归
./gradlew :app:testDebugUnitTest :app:lintDebug
#    A 阶段不触碰 domain/，单测应全绿；lint 不应新增告警（对比基线 4c40ab7）。
```

`StatsScreen` 的零高柱、`LibraryDrawScreen` 的固定行溢出、表盘圆形裁切属于纯几何问题，用 `bounds` 三元组即可证伪/证实。

---

## 7. 不属于本次审计范围

- **对比度以外的视觉品味**（配色倾向、圆角大小、图标选择）不做评判——双主题 token 体系自洽，无 `Color(0x…)` 泄漏到功能文件（唯一例外是 `OcrCropOverlay.kt` 的 12 处 `Color.Black/White`，那是刻意与相机界面一致的深色表面，可接受）。
- **性能**（冷启动、词典解析耗时）见 ROADMAP 候选池「冷启动与内存预算」。
- **已实现得好的部分**（不重复列举于正文，仅作决策参考）：`View.keepScreenOn` 全场保持；每词/每场重置显示态（显示不会跨词泄漏）；逐秒 `liveRegion` 播报 + 字体度量稳定占位器 + `"—"` 从语义树清除；播放按钮的图标/描述/可见标签同源（`DictationScreen.kt:806`）；退出确认是真对话框；`StatusPill` 用主题 token + 文字标签（颜色从不单独承载状态）；40 个 `contentDescription` 中 35 个图标按钮全部合规；`OcrCropOverlay` 的"图像层不读拖拽状态所以拖动时不重绘"两层 Canvas 拆分；进程级选择顺序保留并贯穿到 `multiSourceLabel` 的诚实来源；`Mutex.tryLock()` 先于首次挂起的所有重入闸。
