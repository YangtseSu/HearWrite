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

### C1b · 首页（Home）补充 —— ✅ 已完成（见 §5 阶段 C）

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

### C1c · 其余 P3（速查，按界面分组）—— ✅ 已完成（见 §5 阶段 C）

- **首页**：列表滚动到底多留 72dp 无解释空白（`HomeWordSection.kt:269`）；起始词 `共 N 词` 三处重复（头部徽章 / footer / 按钮徽章）；`清空` 在 0 词时仍可点；`HearWriteWordmark` 无 `maxLines`（`Wordmark.kt:55`）；`Icons.Outlined.Cancel` 一图标两义（错词本 vs 删除行）。
- **听写页**：成绩卡 `再听一遍` / `复习错词` 两个等重实心按钮、无副文案说明范围差异；`拍照批改` 不做预检（未配置 OCR 要等拍完一圈才报）；`重新拍照` 在同屏出现两次且启用条件不同（`DictationGradePane.kt:125-127` vs `:277-279`）；批改行 `itemsIndexed` 无稳定 key；EXTRA 的非交互图标挂了整句 `contentDescription`；transport 行只有播放器有文字标签，`结束` 与前后跳同权重。
- **词库**：三种叫法一功能（多选词库/多选词表/多选模式）；`已选 N 个词表` 报的是表数而非用户关心的词数；chip 阶梯 `10/20/全部` 的门控在 21 词池下给不出 20；`已合并 N 个跨表重复词` 措辞像用户动作；分类卡 `minLines = 2` 让 3 字分类名也占两行高度且网格只有"词表数"这一个数量信号；`ListRow` 标题 1 行截断而真实标签到 11–12 个 CJK 字。
- **设置**：`清除配置` 与主按钮同排且无破坏性样式；展开区无 `animateContentSize` 也不自动滚入视野；键位字段无 `imeAction = Next`；`清空发音缓存` 在"无缓存"时仍可点并照样弹"已清空"；清除生效中的 TTS 预设还会把发音来源退回有道（对话框没说）；hub 滚动位置在子页往返后归零；provider 表单的对话框状态用 `remember`（hub 的用 `rememberSaveable`），旋转即丢；`关于` 页开源许可只给了一个 GitHub 链接、无应用内 GPL 全文；logo 磁贴写死浅色 `#EFF6FD`（深色模式下是亮方块，注释称有意为之，需一次决策）。
- **统计**：`连续天数` 无单位、无"今日已打卡"提示；同屏两种日期格式且从不显示年份；`高频错词` 截到 10 个无提示、两卡的行都不可点；`StatFigure` 数值无 `maxLines`、无等宽数字（`累计用时` 是最宽的格子）；图表无刻度、无点按查值。
- **导航/代码**：`STATS`/`SETTINGS`/`LIBRARY` 缺 `launchSingleTop`；`HearWriteApp.kt:44`、`HomeViewModel.kt:351` 死代码；4 处未用 import（`HomePlaybackPanel.kt:20`、`StatsScreen.kt:50`、`SettingsProviderPages.kt:44`、`SettingsAboutPage.kt`）；`openUrl` 失败静默（`SettingsAboutPage.kt:49-53`）；剪贴板/Toast 无撤销通道。

### C2 · 听写页（核心界面）的其他问题 —— ✅ 已完成（见 §5 阶段 C）
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

### C3 · 统计页 —— ✅ 已完成（见 §5 阶段 C）
| 项 | 位置 | 说明 |
|---|---|---|
| 0 词的柱子**永远不可达** | `StatsScreen.kt:292-305` | `Modifier.fillMaxHeight(f)` 同时设 min/max，`f = 0f` → 高度 0 → `surfaceVariant` 的"休息日"分支是死代码；小幅值日同样被舍入吞掉（1 词 / 最大 300 词 → 0.4dp → 0）。图上看不出"哪天没听写"，只看到任意空隙 |
| `最近记录` 无上限且非惰性 | `StatsScreen.kt:170-175`、`HearWriteDatabase.kt:115` | `observeAll()` 返回整张表，`sessions` 从不裁剪（对比 `history.trimTo`），UI 在 `verticalScroll` 里 `forEachIndexed` 全量组合——全年每天一场 ≈ 350 行 ≈ 23000dp，是全 app 唯一非 lazy 的长列表 |
| Room 读失败 = "暂无听写记录" | `StatsViewModel.kt:97-102` | `.catch { emit(emptyState(loading = false)) }` → 页面显示空态文案。意图（永不空转）正确，选的状态错误；AGENTS.md 要求"异步失败要在 UI 上暴露" |
| 图表对明眼人不可读 | `StatsScreen.kt:309-325` | 只有首尾两个日期标签，无刻度、无最大值标注、无 weekday，全文件无 `clickable`——精确数值只有 TalkBack 能听到（`:286-289` 每根柱都有描述） |
| 卡片缩进不一致 | `StatsScreen.kt:213-217` | 概览卡左右各 16dp（文字落在 32dp），其余 `SettingsCard` 齐平（文字 16dp）——同一滚动里混着两条左基线 |
| 高频错词静默截断且无跳转 | `StatsViewModel.kt:51, 93` | `TOP_WRONG_LIMIT = 10` 无"显示前 10"提示；两张卡的行都没 `onClick`（对比 `HomeDrawers.kt:255-259` 的 `查看词表` 是可点的） |

### C4 · 抽词页 / OCR 链路 —— ✅ 已完成（见 §5 阶段 C）
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

### 阶段 A — 缺陷修复（12 项）—— ✅ 已完成
`A1`–`A12`（§1）。全部是"用户能感知的错误"，且都是 ≤20 行的定点改动。其中 `A2` 按 §0.1 的 D-1 恢复表盘点按（行为变化 + 隐藏态提示文案改回 `点按显示词语`），其余 11 项**无视觉变更**。

**验收证据（2026-09-12，模拟器 `pixel_9a_api37`，无截图，全部为 `uiautomator dump` 的属性/坐标断言）**：

| 项 | 断言 | 结果 |
|---|---|---|
| A1 | 展示态行 `checkable=true`，起始词行 `checked=true`；点第 3 行后勾选随之转移 | ✅ |
| A2 | 盘心可点；点盘心（540,1000）后按钮文案 `显示词语`→`隐藏词语`，盘内提示 `点按显示词语` | ✅ |
| A3 | 批改页按返回 → 回到成绩卡（`听写完成` 可 dump 到），未退出听写页 | ✅ |
| A4 | 点 `清空错词本` → 出现 `清空错词本？` + `将删除全部 3 个错词。` | ✅ |
| A5 | 作答/期望词均改为多行（代码断言，几何随内容） | ✅（代码） |
| A6 | 预览底栏按钮 bottom=2293 < 导航栏顶 2361 | ✅ |
| A7 | 焦点进 Key 字段（IME 顶 1541）后上滑，`保存并启用` bottom=990，清出键盘 | ✅ |
| A8 | 输入 `sk-test` + Enter + `XYZ` → 字段仍单行、10 个掩码字符（换行未进入） | ✅ |
| A9 | `退出多选` → `退出多选？` 对话框；`继续选择` 后勾选保留；硬件返回同样弹确认 | ✅ |
| A10 | 忙碌态代码就位（`starting` + 转圈 + `整理词表…`）；**本机 dump 延迟 ≈2 s > 富化窗口，未能在真机上采样到该瞬态** | ⚠️ 见下 |
| A11 | 三张主题卡 `checkable=true`，恰好一张 `checked=true`（跟随系统） | ✅ |
| A12 | 两条来源均为 `multi:` 且成员词表不存在 → 两组标题同为 `多词表（2 个词表）`、正常渲染、无 `Key was already used` | ✅ |

**A10 的验证缺口**：`uiautomator dump` 单次约 2 s，而词表预览的 ECDICT 富化窗口（含冷启动）短于该延迟，采样不到 `整理词表…` 这一瞬态。代码路径与首页 `开始听写` 的忙碌态同构（同一 `starting` 标志 + 同一文案 + 同款 `CircularProgressIndicator`），且已确认按钮在 `starting` 期间 `enabled=false`。**残余风险低**；若要闭环，可加一条 Compose UI 测试或把 `LibraryPreviewViewModel.starting` 做成 JVM 单测（`startLines()` 挂起期间断言为 true）。

**门禁**：`testDebugUnitTest` 327/327 绿；`lintDebug` 无新增告警（当时的"6 条 `GradleDependency`"在本机不可复现，三棵树重跑均为 `No issues found.`，见 `ERRATA.md` §3）。

### 阶段 B — 一致性收口（9 项）—— ✅ 已完成
`B1`–`B9`（§2）。净 **+581 行**（`app/` 内 +2362 / −1781；含 docs 为 +2430 / −1799）——`u/SettingsProviderPages` 的 1237 行拆成 537 行页面 + 770 行公共件，加上新建的 `Format.kt`(58) / `Feedback.kt`(100)，总量是**增**不是减。收口本身确在删除：两套 provider 表单合一、15 处 `Toast` 归零、格式化器与字阶各归一。

**新增的共享件**（后续阶段复用，勿再自造第二套）：
- `ui/theme/Type.kt`：补齐全部 12 个字阶（原先只定义 5 个 → 85% 文字落到 M3 拉丁默认值），并新增 `Typography.wordHead` token（3 处手拼的"行词头"合一）。
- `ui/Format.kt`：`formatInterval` / `formatDuration` / `formatDay` / `formatStamp` / `formatPercent` 单一来源（`formatStamp` 非当年时自动补年份）。
- `ui/Feedback.kt`：一次性确认通道。`ModalBottomSheet` / `AlertDialog` 是 `ComponentDialog`（**独立窗口**），屏幕级 `SnackbarHost` 会被其遮罩盖住，故提供 `MessageHostScope` 让面板内的消息落在面板自己的窗口里。
- `ui/ListRows.kt` + `SettingsComponents.kt`：`RowToggle(checked, role, onToggle)` —— 整行即控件、指示器纯视觉（单焦点停靠点）。
- `ui/SettingsProviderForms.kt`（771 行）：两套 provider 表单的全部公共件。

**验收证据（2026-09-12，模拟器 `pixel_9a_api37`）**：

| 项 | 断言 | 结果 |
|---|---|---|
| B1 | 12 个字阶全部有定义；`wordHead` 被 3 处调用 | ✅ 源码 |
| B2 | 全仓 `Toast.makeText` **0** 处（基线 15）；面板内消息落在面板窗口 | ✅ 设备 |
| B3 | 开关行 `checkable=true` 且承载状态、`Switch` 不再是独立节点；点**标签**即翻转（听写间隔/提示音 均验证） | ✅ 设备 |
| B3b | 词表预览底栏的 随机顺序 也已改成整行 `toggleable`（`LibraryPreviewScreen.kt`）：节点子树文本 `随机顺序`、点标签即翻转、`NAF` 消失（此前是无名的 `Switch` 叶子） | ✅ 设备 |
| B4 | 首页内容列不再重复计导航栏；`开始听写` bottom=2255 < 导航栏 2361 | ✅ 设备 |
| B5 | 间隔读数 `5.0s` → **`5 秒`**；时长/日期走单一格式化器 | ✅ 设备 |
| B6 | 朱砂**换色并接线**：暗色 `#DB9A6B` 由 **0 → 661 px**（表盘拼音+组词行）；亮色 `#9C4A22` **0 → 601 px**；英文场次朱砂 **0 px**（POS/释义仍 `onSurfaceVariant`，`Color.kt` 硬规则成立） | ✅ 像素采样 |
| B7 | `SettingsSectionHeader` 带 `heading()`；provider 状态块用 `hearWriteSemantics.success` | ✅ 源码 |
| B8 | 搜索同一词表不再两处出现；选中行整行 `checkable` 且勾选随行；`载入草稿` **不再把用户抛出浏览上下文**（停留在预览页并就地提示，清单在下次进首页时落到编辑器） | ✅ 设备 |
| B9 | 两套表单抽公共件（`SettingsProviderPages.kt` 1237 → 537 行）；清除对话框不再重复；TTS 不再"保存失败也报成功"；TTS 补 base URL 校验；`正在使用` 与实际生效配置对齐 | ✅ 源码 + 编译 |

**过程中发现并修掉的既有缺陷**（超出审计清单，均为真 bug）：
- **OCR 保存同样会误报成功**：原 `Boolean` 返回值由 `viewModelScope.launch{…}` 立即返回 `true`，DataStore 写入尚未落定——即"已加固"的 OCR 路径其实一直在撒谎。两套 provider 现各自改用一次性 `StateFlow` 报告结果（镜像仓库既有的 `providerKeyWarning` 惯例），失败时报 `保存失败，请重试`。
- **`DictationViewModel.gradeToast` 是遗留命名**：Toast 换成 Snackbar 后该标识符仍在（13 处），已重命名为 `gradeNotice` / `clearGradeNotice`。

**两处刻意的行为变更**（审计要求的一致性统一，非回归）：
- 超过 1 小时的场次在成绩卡也显示 `1 小时 15 分`（此前成绩卡 `75 分 30 秒`、统计页 `1 小时 15 分`，一屏两制）。
- 记录时间戳在**非当年**时补年份（错词本/听写记录无上限，否则两年前的记录与昨天的无法区分）。

**未能闭环项（如实记录）**：
- **B4 的 IME 路径未在设备上验证**：该 AVD 为硬件键盘配置，软键盘不渲染（`mInputShown=true` 但无键盘可截），故"键盘弹起时内容列不再重复计导航栏"只有代码级确认。
- `A10`（阶段 A）的忙碌态瞬态仍未能采样：`uiautomator dump` 延迟 ≈2 s 长于富化窗口。

**门禁**：`testDebugUnitTest` 327/327 绿；`lintDebug` 无新增告警（参见 `ERRATA.md` §3）。

### 阶段 C — 状态覆盖与内容层

#### C1（缺失状态）—— ✅ 已完成

范围：`C1` 的六行状态（首页草稿加载态、听写准备态、语音失败提示、OCR 可取消、系统音色空态、抽词池读表失败），不含 `C1b`（首页补充清单）与 `C1c`（P3 速查）。

| 状态 | 位置 | 做法 |
|---|---|---|
| 首页草稿播种 | `HomeViewModel.kt` `draftLoaded` → `HomeWordSection.kt` `loading` | 草稿读取完成前，列表体渲染 `正在读取草稿…` + 转圈，`编辑` 禁用；`开始听写` 同一窗口内显示忙碌态 |
| 听写准备中 | `DictationScreen.kt` `!ui.ready` 分支 | 裸转圈改为转圈 + `准备听写…` |
| 语音失败 | `DictationEngine.speechFailures` → `DictationUiState.speechFailures` | 计数每次失败的朗读通过（speak1/speak2/释义/组词），`start` 归零；听写中与成绩卡各出一条中文提示 |
| OCR 进行中 | 新增 `ui/OcrProgress.kt` `OcrProgressStrip` | 全宽进度条 + `取消`，首页 / 批改页 / 扫描 sheet 三处共用；首页原头部药丸删除（360dp 上宽度 ≈0dp） |
| 系统音色为空 | `SettingsProviderForms.kt` `SystemVoiceSection` | 由"什么都不渲染"改为一行说明（原实现连 `英文使用默认音色` 开关一起消失） |
| 抽词池读表失败 | `LibraryDrawViewModel.kt` `DrawPoolState.failedLabels` → `LibraryDrawScreen.kt` | 失败的词表被具名列出（摘要行 / 空态），不再只落 logcat |

**验收证据（2026-09-14，模拟器 `HearWrite37`，`uiautomator dump` 属性断言）**：

| 项 | 断言 | 结果 |
|---|---|---|
| 草稿加载态 | 列表体 `正在读取草稿…`、`开始听写` 显示 `读取草稿…` 且 `enabled=false` | ✅ 设备（读取窗口短于 dump 延迟，用一次性 3 s 探针把窗口拉长后采样，探针已移除） |
| 听写准备态 | `!ui.ready` 期间显示 `准备听写…` | ✅ 源码（该窗口同样短于 dump 延迟） |
| 语音失败 | 禁用系统 TTS 引擎后跑一场：盘上方 `本场有 N 次发音失败…`（N 随进度增长），成绩卡 `本场有 10 次发音失败，成绩可能不准` | ✅ 设备 |
| OCR 进度 + 取消 | 首页：`识别中…` 全宽 + `取消`；点取消后进度条消失、`开始听写` 恢复 `enabled=true`。批改页：`处理图片中…` + `取消`，同样可取消 | ✅ 设备（`adb reverse` 到一个永不响应的 stub 端点制造长窗口） |
| 系统音色空态 | 禁用 TTS 引擎后进入 发音来源 → 系统语音：显示 `当前系统语音引擎未提供可选音色…` | ✅ 设备 |
| 抽词池失败 | 具名列出未能读取的词表 | ✅ 源码（无现成的失败注入点） |

**门禁**：`testDebugUnitTest` **330/330** 绿（基线 327，新增 3 条引擎用例）；`lintDebug` 无告警。

#### C1b（首页补充，8 项）+ C1c（其余 P3）—— ✅ 已完成

范围：`C1b` 全表 8 项，加上 `C1c` 六组速查里**每一条可落地项**（导航/死代码组 6 项、听写页 6 项、词库 6 项、首页 5 项、设置 9 项、统计 5 项）。

**C1b**

| 项 | 做法 |
|---|---|
| OCR 进度药丸被挤到不可读 | 上一提交（C1）已解决：头部行不再放药丸，改为头部下方全宽 `OcrProgressStrip`；本阶段仅复核（`HomeScreen.kt` 头部注释即改动说明） |
| 清空草稿一键抹掉持久数据 | `HomeWordSection` 新增两个确认对话框：`清空草稿？`（`当前草稿将被清空，内容无法恢复。`）与 `覆盖当前草稿？`（点名用哪个示例替换）；草稿为空时示例直接填充不打扰，`清空` 在 0 词时禁用 |
| 展示态行点击二义 | 行点击只改 `起始词`（`selectable` + `Role.RadioButton`）；释义展开拆成独立尾部按钮，仅当 `glossNeedsExpansion` 时出现，`展开释义`/`收起释义` |
| `开始听写` 的可用性语义 | 0 词时按钮文案改 `请先输入词表`（仍可按，按了给 `请先输入单词列表`）；OCR 进行中由 `识别中…` 忙碌态禁用（原已具备） |
| 载入历史/收藏后抽屉不关 | `onApply` 改为：载入 → 关抽屉 → 由**屏幕级** message host 提示（Sheet 是自己的窗口，消息留在里面会被自己的遮罩挡住） |
| 首页无法重置起始词 | 面板在 `startIndex > 0` 时显示 48dp `重置为从第 1 词开始`（槽位常驻，`从第 N 词开始` 不横移）；同时删除死代码 `HomeViewModel.adjustStartIndex` |
| 进入编辑态不聚焦 | 焦点请求改按"模式翻进编辑态"触发（`wasEditing` 记录上一帧模式），已有词表点 `编辑` 也直接给到光标；进入时已是编辑态不误触发 |
| `更多` sheet 四行无间距 | 四行按 8dp 间距分隔（实测行距 168px），不再读成一整块 |

**C1c**

| 组 | 做法 |
|---|---|
| 首页 | 列表底部 72dp 空白 → 8dp（`HomeScreen` 已按面板高度让位）；编辑区 footer 的 `共 N 词` 删除（头部徽章已表达）；`HearWriteWordmark` 加 `maxLines = 1`；`错词本` 图标由 `Outlined.Cancel` 换成 `Outlined.Spellcheck`，与行删除 `Filled.Cancel` 区分 |
| 听写页 | 成绩卡 `再听一遍` 保留实心主按钮、`复习错词` 改 `FilledTonalButton` 并加一行范围说明（各含词数）；`拍照批改` 预检 OCR 配置（未配置则不开批改页，只提示）；`重新拍照` 两处启用条件统一为 `!busy && !pickerBusy`；批改行 `items(... key = { it.index })` 稳定 key；EXTRA 行非交互图标 `contentDescription = null`；transport 四个控件各带文字标签（标签即 `contentDescription`） |
| 词库 | 统一叫法 `多选词表`；chip 门控 `size >= 10 / >= 20`；`已自动合并 N 个跨表重复词`；分类卡去掉 `minLines = 2`；`ListRow` 新增 `titleMaxLines`（默认 1，词库行传 2）；`已选 N 个词表`语义说明（词数合计仍在抽词页，见下） |
| 设置 | `清除配置` 用 `error` 前景表达破坏性；展开区 `animateContentSize`；三个 provider 字段加 `imeAction = Next`；无缓存时 `清空发音缓存` 行不可点；清除生效中的 TTS 预设时对话框补一句"发音来源将退回有道词典"；hub 滚动位置由 `SettingsScreen` 持有（子页往返不归零）；provider 对话框状态改 `rememberSaveable`；**新增应用内开源许可页**（`assets/licenses/GPL-3.0.txt` + `SettingsSubPage.LICENSES`）；`values-night` 覆盖 logo 磁贴底色 |
| 统计 | `连续 N 天` 带单位 + `今日已打卡`；日期格式与记录行统一（`Format.kt`：`9月14日` / `9月14日 20:10`，非当年补年份）；高频错词达上限时提示"完整错词本见首页 更多 → 错词本"；`StatFigure` 三相 `weight(1f)` + `maxLines` + `tnum` 等宽数字；图表加最大值刻度与**点按查值**（点柱显示 `9月13日 · 1 场 · 4 词`） |
| 导航/代码 | `STATS`/`SETTINGS`/`LIBRARY`/`DICTATION` 补齐 `launchSingleTop`；删除 `HearWriteApp.startDictation`、`HomeViewModel.adjustStartIndex` 两处死代码；`openUrl` 失败改为中文提示；**单行删除加 `撤销`**：历史记录行 / 错词本行删除后由 Snackbar 提供一次性撤销，撤销按原行（含 `errorCount`/时间戳/来源/收藏星）恢复，避免"删掉即永久丢失" |

**两处刻意的行为变更**：`错词本` 行删除的消息由 `已移除` 变为 `已移除 <词>`（点名删了什么，也让撤销有对象）；`清空发音缓存` 在无缓存时从"可点但空操作"变为"不可点"。

**验收证据（2026-09-15，模拟器 `pixel_9a_api37`，`uiautomator dump` 属性/坐标断言）**：

| 项 | 断言 | 结果 |
|---|---|---|
| 起始词 + 重置 | 点第 2 行 → `从第 2 词开始` 且第 2 行 `checkable=true checked=true`；`重置为从第 1 词开始` 出现；点它 → `从第 1 词开始`、重置按钮消失 | ✅ 设备 |
| 清空草稿确认 | 点 `清空` → `清空草稿？` + `当前草稿将被清空，内容无法恢复。` + `清空/取消` | ✅ 设备 |
| 0 词语义 | 清空后：`开始听写` → `请先输入词表`；`清空` 父节点 `enabled=false` | ✅ 设备 |
| 载入历史关抽屉 | 点历史行 → 抽屉关闭、`已载入历史记录` 落在屏幕 host | ✅ 设备 |
| 更多 sheet 间距 | 四行行距 168px（原贴合成一块） | ✅ 设备 |
| 菜单图标区分 | `错词本` 用 `Outlined.Spellcheck` | ✅ 源码 |
| 统一日期 | 图表轴 `9月2日`…`9月15日`；记录行 `9月13日 02:34 · 正式听写` | ✅ 设备 |
| 连续天数 + 打卡 | `0 天` 带单位；本机无今日记录故无 `今日已打卡`（有记录时显示） | ✅ 设备 |
| 图表刻度 + 点按 | 左上 `4 词` 刻度；点第 13 根柱 → `9月13日 · 1 场 · 4 词` | ✅ 设备 |
| transport 标签 | `结束`/`上一个`/`暂停`/`下一个` 四个文字标签均在场，且与 `content-desc` 逐字一致 | ✅ 设备 |
| 成绩卡权重 + 范围 | `再听一遍` 实心、`复习错词` tonal + 范围说明行 | ✅ 源码 |
| 批改预检 | 配置存在 → 打开批改页（本机已配置）；未配置分支为代码路径（提示后留在成绩卡） | ✅ 设备 + 源码 |
| 应用内许可 | 关于 → `开源许可 GPL-3.0-or-later · 应用内全文` → 页内可滚动渲染 GPL 全文（首行 `GNU GENERAL PUBLIC LICENSE Version 3`） | ✅ 设备 |
| 撤销删除 | 历史行点删除 → `已删除该记录` + `撤销`；点 `撤销`（logcat 探针确认 `ActionPerformed` → `restored ok`）→ 列表恢复该行（`历史记录（1）`、`5 词 · 9月15日 00:25`）。探针已移除 | ✅ 设备 |
| 抽词池 / 词库边界 | chip `>=10`/`>=20` 门控、`已自动合并` 措辞、分类卡高度 | ✅ 源码（改动为常量级） |

**未能闭环项（如实记录）**：
- **`清空发音缓存` 无缓存态与 `系统音色空态` 的深色 logo 磁贴**只有源码确认：本机缓存恒非空（1.5 MB），且 AVD 为浅色主题，无法采样"无缓存时按钮 `enabled=false`"与 `values-night` 取色的设备证据。
- **`SettingsSubPage.LICENSES` 的失败降级**（资源读取失败）无注入点，只有代码路径。

**门禁**：`testDebugUnitTest` **334/334** 绿（基线 330，新增 4 条：错词本 `restore` 两条 + 历史 `restore` 两条）；`lintDebug` 无告警；`python3 scripts/check-assets.py` 0 error（新增 `licenses/` 已加入各扫描器的保留目录，见 `docs/WORDLIST.md`）。

#### C2（听写页：8 项 + 表盘裁切/展开）—— ✅ 已完成

范围：`C2` 表全部 8 行，加上表盘圆裁切与词语展开入口（审计在这两行上给的方案只是参考，实现时换了更稳的做法，见下）。

| 项 | 做法 |
|---|---|
| 词语也会被截断 | 词语不再是 `maxLines = 2 + Ellipsis` 的"截断"，而是**按字号求解**：新增 `domain/DialFit.kt` 用仓库自有的 `displayWidth`（全角 1 / 半角 0.5）与**贪心折行模型** `wrappedLineCount` 判断"这个词在盒子里占几行"，字号不足时在 40sp→22sp 之间二分求最大可行值。词语真的放不下（22sp 仍超行）时，盘下出现 `展开全部` 入口，打开 `DialDetailDialog` 显示完整词/词性/释义 |
| 204dp 圆形溢出裁切 | 内容不再放进固定 `130×156dp` 的"内接矩形"，而是**按自身高度反解**：`dialContentWidthDp` 给圆内该高度下的最大宽度（`w = √(D² − h²)`），再减去字体度量余量。盒子由子节点实际高度算出，所以"任何子节点都在圆内"是算术保证而不是估算。`展开全部` 移到圆**外**（盘下方的固定高度行），几何上不可能被裁到 —— 旧实现把它放在盘内 `Column` 底部，实测完全落在圆外、语义树里都读不到 |
| 隐藏态文案在暂停时说谎 | 盘内状态词跟随播放状态：`playing = ui.state == PlayState.PLAYING` → `听写中`/`已暂停`，与顶栏 `StatusPill` 同源 |
| 满分反而信息更少 | 成绩线恒渲染：`正确 N 词 · 错词 M（本场）`；`M == 0` 时渲染 `正确 N 词 · 错词 0 · 满分`。"0 错"与"这段没渲染"不再同形 |
| 同屏"错词"两义 | 顶栏药丸改为**本场**数量（`本场错词 N 词`，`runWrongCount`），与成绩卡同数；全局错词本继续只在成绩卡出现（`错词本（N）` + chips） |
| 标记错词不可撤销 | `markCurrentWrong` 改为 `toggleCurrentWrong`：按钮按当前词是否已标记切换 `标记错词`/`取消标记`（配色随之从 `errorContainer` 换成 `secondaryContainer`），两个按钮各加 4dp 水平内边距、主按钮由 tonal 改为实心。**数据库精确回滚**：`标记` 时在写队列内先 `find` 出旧行，`取消标记` 时按快照恢复（旧行 → `restore` 原 `errorCount`/时间戳/来源；本场新建 → `delete`）——直接 `delete` 会把学生此前几次的错误历史一起抹掉 |
| `FlowRow` 非惰性渲染全局错词本 | chip 上限 `WRONG_CHIP_CAP = 24`（书已按"错得多"排序，截断保住的正是最该处理的），超出时给 `查看全部 N 个错词`/`收起` 入口 |
| 计时数值占位符与注释不符 | 占位改为 `COUNTDOWN_SLOT_TEXT = "${MAX_INTERVAL_SEC.toInt()} 秒"`（= `10 秒`），即该槽位真会渲染的最宽文本；注释同步改写 |
| 改间隔会重置正在跑的倒计时 | `setIntervalSec` 的 ★ 语义（改间隔重启当前倒计时）**未改**——它是文档化验收项。误触只从 UI 侧收敛：`±` 命中区由 40dp 提到 48dp（实测 126×126px @420dpi） |

**两处刻意的行为变更**：`取消标记` 是本阶段新增的反向操作（此前后无法在运行内撤销）；`标记错词` 由 tonal 变实心、配色随状态翻转（审计原文指出"主色块是破坏性的标记错词"这一权重倒置）。

**实现上偏离审计建议的地方**：审计建议"用仓库已有的宽度度量替代字符数 `> 26` 判据"，实现时发现仅比较**总宽度**是不够的 —— `0.6 + 0.6 + 0.6` 单位按总和放得进两条 1.0 宽的线，实际贪心折行要三行，于是"判定为放得下、渲染却省略号"。因此判据落在 `wrappedLineCount`（与渲染器同样的贪心折行）上，而不是总宽度比较。

**验收证据（2026-09-15，模拟器 `pixel_9a_api37` 1080×2424 @420dpi，`uiautomator dump` 属性/坐标断言）**：

| 项 | 断言 | 结果 |
|---|---|---|
| 表盘圆裁切 | 盘圆 `center=(540.5, 972.5) r=325.5`；长词场次（`the Great Hall of the people`）盘内词/词性/释义三个节点的四条 bounds 角点全部满足 `distance(corner, center) ≤ r`（词余量 146px） | ✅ 设备 |
| 40 字符单词 | `x` + 40×`a`：盘内三段文本仍在圆内；`展开全部` bounds `[468,1325][614,1378]`，**在圆外**且位于按钮行（top 1616）之上；点开后对话框显示完整 40 字符词（单节点无省略号）+ `n.` + `yyyy` + `关闭` | ✅ 设备 |
| 大字号 | `font_scale 1.5` 下同样场次：盘内三段仍全部在圆内（字号随 `fontScale` 参与求解） | ✅ 设备 |
| 暂停文案 | 暂停时盘内 `已暂停` @ `[479,976][605,1037]` + 顶栏 `已暂停`；播放时盘内 `听写中`（对照采样） | ✅ 设备 |
| 满分成绩线 | 3 词 0 错：`共 3 词 · 用时 55 秒` + `正确 3 词 · 错词 0 · 满分`；同场 `错词本（5）`（全局）与 `错词 0`（本场）同屏可分辨 | ✅ 设备 |
| 顶栏药丸 | 标记后 `本场错词 1 词`；取消后药丸消失 | ✅ 设备 |
| 标记/取消/重标记 | 逐词验证：w1 标记→按钮 `取消标记`+药丸 1；`下一个` 到 w2→按钮回到 `标记错词`（药丸仍 1）；回 w1→`取消标记`；取消→药丸回 0；w2 仍为已标记 | ✅ 设备 |
| 空操作不重复计数 | w1 标记两次：`runMarks` 去重，药丸仍为 1 | ✅ 源码（`head in _runMarks.value` 早退） |
| 数据库精确回滚（既有行） | 预置 `s0|4|manual|1690000000000|1700000000000` → 标记后 `s0|5` → 取消后**逐列等于改前**（`errorCount 4`、来源与两个时间戳全部复原） | ✅ 设备（`sqlite3` 直读） |
| 数据库精确回滚（新建行） | `w0` 已有 2 次历史：标记 → `w0|3` 且来源更新；取消 → `w0|2` + 原来源 + 原时间戳，其余行未动 | ✅ 设备 |
| 新增行删除 | `zz9` 不在书中：标记 → 插入 `zz9|1`；取消 → 行被删除，其余行完好 | ✅ 设备 |
| 取消标记后重标记 | `q0`/`r0`：标记 `|1` → 取消（空）→ 再标记 `|1`（不是 `|2`） | ✅ 设备 |
| 本场成绩跟随取消 | 标记后取消再跑完一场：成绩卡 `正确 2 词 · 错词 0 · 满分`，`sessions` 行 `2|0|dictation`（`wrongCount` 取 `runMarks.size`，非历史按压次数） | ✅ 设备 |
| 错词本 chip 上限 | 预置 30 个错词：成绩卡只组合 12 个 chip（视口内），滚动后 `查看全部 30 个错词` 出现；点开 → 30 个 chip + `收起` | ✅ 设备 |
| 计时占位符 | 10 秒间隔下逐秒采样：`10 秒` 文本宽 213px、`8/6/3/1 秒` 宽 162px，但**中心恒为 540.5–541.0**，且 `显示词语`/`标记错词` 按钮 bounds 在全部采样中完全不变（1 位与 2 位字形不再改变行尺寸） | ✅ 设备 |
| 间隔按钮命中区 | `减少间隔`/`增加间隔` 命中区 126×126px = 48dp | ✅ 设备 |

**未能闭环项（如实记录）**：
- **`书` 写失败时的回滚分支**（`BookWriteState.unwritten`）只有代码路径：Room 写失败无注入点，无法在设备上制造。触发条件是"标记时落库失败、随后取消标记"，此时跳过数据库回滚（避免删掉本场并未写过的行）。

**门禁**：`testDebugUnitTest` **353/353** 绿（基线 334，新增 19 条：`DialFitTest` 的表盘几何/折行/截断判定）；`lintDebug` 仅 5 条既有 `GradleDependency` 版本提示，无新增告警；`python3 scripts/check-assets.py` 未触及数据层。真机 demo：本表全部 ✅ 项。

#### C3（统计页：柱子下限、列表惰性/上限、错误态、图表可读）—— ✅ 已完成

范围：`C3` 表全部 6 行。

| 项 | 做法 |
|---|---|
| 0 词的柱子永远不可达 | 柱子高度由 `fillMaxHeight(fraction)` 改为**显式 dp**（`fraction` 同时设 min/max，`0f` 就是零高，灰分支因此是死代码）：休息日画 `TREND_REST_STUB_HEIGHT` 3dp 灰桩（`surfaceVariant`），有记录的日画 `TREND_BAR_MIN_HEIGHT` 6dp 起的主色柱。**两档下限不同**是刻意的：1 词 / 最大 300 词 = 0.4dp，若共用 3dp 下限，"有词的日"会画得比"没词的日"还矮。十四根柱子现在恒可见，空窗与休息日不再同形 |
| `最近记录` 无上限且非惰性 | 整页改为**一个 `LazyColumn`**（原本 `verticalScroll` + `forEachIndexed` 全量组合）：概览/趋势/高频错词各是一个 item，记录行是 `itemsIndexed(key = row.id)`。默认只渲染最近 `RECENT_PREVIEW_LIMIT = 20` 场，超出时给 `查看全部 N 场记录` / `收起`（展开状态是屏幕级 `rememberSaveable`，记录更新不会把用户打开的列表收回去）。分组卡片因此不能再用 `Surface` 包住 lazy 列表，改为**逐行绘制卡片**（`SectionCardRow`：卡片底色 + 仅首尾行带 `AppCardCorner` 圆角，行间方角，整组仍读作一张卡）——`AppCardCorner` 从 `Theme.kt` 导出，与 `Shapes.large` 同源，避免第二处写死 20dp |
| Room 读失败 = "暂无听写记录" | `StatsUiState.loadFailed`：`catch` 不再 emit 空记录，而是 emit 带 `loadFailed = true` 的空态，页面渲染 `听写记录读取失败` + `记录仍保存在本机，请稍后重试。` + `重试`。重试经 `retry: MutableStateFlow<Int>` 的 `flatMapLatest` **重新订阅**（Room 流是冷的，重订就是重读），并在重试期间回到加载态 |
| 图表对明眼人不可读 | 三处：①左上角刻度由 `120 词` 改为 `最高 300 词`（说清它是什么）；②轴改为**每根柱一个周几字**（`formatWeekday`：一…日）+ 下方 `9月3日 至 9月16日` 作为绝对锚点——两周窗口里周几正是唯一能看出"哪天空着是周末"的刻度，而 `9月2日` 这类逐柱标签在 14 格里放不下；③点按查值（审计前一轮已加）保留，其读数改用 `formatDayWithWeekday` 与轴同口径。周几行是装饰（每根柱自己已播报日期+场次+词数），故 `clearAndSetSemantics {}` 从语义树摘掉，不新增 14 个 TalkBack 停靠点 |
| 卡片缩进不一致 | 概览卡原为左右各 16dp（文字落在 32dp），其余卡片齐平 16dp——去掉概览卡的左右内边距，同一滚动内只剩一条左基线 |
| 高频错词静默截断且无跳转 | 两处对齐错词本：①**行内补来源** `错 N 次 · 最近 … · <词表名>`（错词本是分组表头承载这信息，这里是平铺行，只能行长自己带）；②源是仍可解析的内置词表时**整行可点**，跳转到该词表预览（`resolveSourceJump`，与错词本的 `查看词表` 同一个判据，两处共用）。记录行同样可点，`onClickLabel = "查看词表"` 说明点击动作（行文本只是时间戳与数字）。跳转目的地就是 `Routes.libraryPreview`，与错词本抽屉一致 |

**实现上偏离审计建议的地方**：审计给零高柱的方案是"柱高 ≥ 下限值"，直接改 `fillMaxHeight(f)` 的下限即可；实际改用**显式 dp 高度**并分两档下限，因为 ①`fillMaxHeight(fraction)` 语义上就是"父高的一部分"，给它加 `heightIn(min=)` 会与父级约束打架；②`fraction` 的 0.4dp 档位必须与"没听写"分开，否则修好一个死代码的同时制造"有词比没词矮"的新误读。

**两处刻意的行为变更**：`最近记录` 默认只显示 20 场（此前全量渲染，全年每天一场约 350 行）；错词行/记录行从只读变为可点（源可解析时）。

**验收证据（2026-09-16，模拟器 `pixel_9a_api37` 1080×2424 @420dpi，`uiautomator dump` 属性/坐标断言 + 程序化像素取样）**：

| 项 | 断言 | 结果 |
|---|---|---|
| 休息日灰桩 | 预置 14 天窗口（8 天有记录、6 天为 0）：0 词日柱高 **3.05dp**、色 `#E4E1D9`（`surfaceVariant`），14 根柱全部有可见标记 | ✅ 像素 |
| 有词的日下限 | 5 词/12 词日（最大 300）柱高均 **6.1dp**、色 `#1B5FAA`（`primary`）——高于休息日桩，不再被舍入吞掉 | ✅ 像素 |
| 最高刻度 | `最高 300 词`；最大日柱高 120.0dp = `TREND_BARS_HEIGHT` | ✅ 设备 |
| 周几轴 + 起止日期 | 14 个周几字（四五六日一二三…）中心 x 与对应柱中心逐列对齐（72/143/215/287/…/1007 px），下方 `9月3日 至 9月16日` | ✅ 设备 + 像素 |
| 周几轴不进语义树 | 14 个周几字不在 `uiautomator dump` 中（`clearAndSetSemantics`）；每根柱仍带 `9月3日 周四，0 场，0 词` 描述 | ✅ 设备 |
| 点按查值 | 点柱后读数 `9月14日 周一 · 2 场 · 45 词`（带周几，与轴同口径） | ✅ 设备 |
| 记录惰性 + 上限 | 预置 34 场：初始只组合 11 行（视口内）、`查看全部 34 场记录` 在场；点开后同一滚动位置继续列出更早的场次、按钮变 `收起` | ✅ 设备 |
| 记录分组卡片外观 | 首行/末行圆角、行间方角、卡片底色 `surfaceContainerLow`（视觉复核：整组读作一张卡） | ✅ 设备 + 截图 |
| 读失败态 + 重试 | `DROP TABLE sessions` 后进入统计页：`听写记录读取失败` + `记录仍保存在本机，请稍后重试。` + `重试`（此前显示 `暂无听写记录`）；建回表后点 `重试` → 页面恢复为真实数据 | ✅ 设备 |
| 空记录态 | 清空 `sessions` 后：仍渲染 `暂无听写记录`（与失败态区分） | ✅ 设备 |
| 高频错词来源 + 跳转 | 行读数 `错 3 次 · 最近 9月15日 21:46 · 一上 写字表 识字 2`；点该行 → 打开 `一上 写字表 识字 2` 预览（标题+4 词+底栏齐备）。`manual` / 已删除历史行 / `multi:` 池（`一上 写字表 识字 2 等 2 个词表`）的行**不可点**（`clickable=false`），降级为 `未知来源` 且不丢行 | ✅ 设备 |
| 错词本抽屉回归 | 同源分组标题与 `查看词表` 按钮行为不变（`未知来源` / `一上 写字表 识字 2` / `一上 写字表 识字 2 等 2 个词表` 三组照旧，`resolveSourceJump` 抽取未改语义） | ✅ 设备 |
| 记录行跳转 | 点 `一上 写字表 识字 2 · 9 词 · …` 记录行 → 该词表预览；`multi:…` 来源显示 `一上 写字表 识字 2 等 2 个词表` 且**不可点**（池成员不是一张表） | ✅ 设备 |
| 卡片缩进 | 概览卡文字与 `SettingsCard` 左基线同为 16dp（42px） | ✅ 设备 |
| 暗色主题 | 柱 `#8FC3F5`、休息日桩 `#3A4450`、卡片 `#1A212B`：三档可分辨，文字可读 | ✅ 像素 + 截图 |
| 大字号（1.5×） | 14 个周几字无一截断/重叠，`9月3日 至 9月16日` 与提示行完整 | ✅ 设备 |
| 横屏 | 概览卡三列数值与标签无裁切、无重叠 | ✅ 设备 |

**未能闭环项（如实记录）**：
- **`loadFailed` 只在"表被删"这一种失败下采样到**：真实 Room 故障（磁盘错、迁移失败）无注入点，故错误态的设备证据来自刻意删表；`重试` 的成功分支同样以"建回表再点"复现。

**门禁**：`testDebugUnitTest` **355/355** 绿（HEAD 基线 354 条，新增 1 条：`SourceTitlesTest` 的 `resolveSourceJump` 边界——可解析内置词表给值，已消失的词表 / 历史行 / `multi:` 池 / 空 id 均为 null）；`lintDebug` 无新增告警；`python3 scripts/check-assets.py` 未触及数据层。

#### C4（抽词页控件行/摘要/逐词预览 + OCR 链路）—— ✅ 已完成

范围：`C4` 表全部 10 行。

| 项 | 做法 |
|---|---|
| 320dp 宽度下溢出 | 输入框 + 三个 chip 的 `Row` 改 `FlowRow`（`Arrangement.spacedBy(8.dp)`）：原来一个不换行 Row 的内在宽度约 380dp，比 320dp 屏还宽。实测 320dp 等效密度（`wm density 540`）下越界节点数 **0**（此前 `随机听写全部 1446 词` 与 `全部` chip 均被推出屏外） |
| 池摘要滚出视野 | `已选 N 个词表 · 合计 M 词` / `已自动合并…` / 失败词表三项**移入底栏**，与它论证的 `随机听写` 按钮同层固定；同时删掉列表里那份重复副本（此前同一组数字两处出现，且随列表滚走）。底栏顺序改为摘要 → 控件行 → 按钮 |
| 抽词页无逐词预览 | 新增 `逐词预览` 列表块：`本次将听写 N 词（按顺序）` + 每词一行（序号 + 词头 + 词性/释义或拼音/组词）+ `换一批`。**预览即本次抽词**：`LibraryDrawViewModel` 在前瞻里抽一次并留存，`prepareSession()` 复用同一份（仅当 X 变了才重抽），所以页面显示什么就听写什么，不存在"看一眼 A、听写 B"。`换一批` 重抽同尺寸并让运行跟随。参与抽词的词表清单移到预览之后 |
| 识别语言不持久化 | 新增 DataStore 键 `ocr_lang`（`SettingsRepository.ocrLang` / `setOcrLang`）。未存过时**按当前草稿推断**（`data/OcrService.kt` 的 `inferOcrLang`，复用 `isCjkRun` 的多数派判据），并随草稿变化跟随；一旦用户自己选过就再也不覆盖。草稿播种完成后立即重跑一次推断（否则协程竞态会让汉字草稿开出「英文」页签） |
| 成功识别直接覆盖草稿 | 草稿为空时照旧静默填入；**非空草稿**时识别结果进 `ocrPending`，弹出 `识别到 N 个词` 对话框（`当前草稿有 M 个词…未开始听写的草稿不会被记入历史，替换后无法找回`），三选：`替换` / `追加` / `取消`。`追加` 把识别行接到现有草稿之后（解析后拼接，保持行格式） |
| 配置类错误无出路 | `HomeViewModel.failOcrConfig()` 同时置 `ocrNeedsSettings`；错误卡在该状态下多一个 `去设置`（与扫描 sheet 的入口同目的地 `onOpenOcrSettings`）。此前文案指向设置却只给 `关闭`。成功提交后该标记自动清除 |
| 裁剪把手不可见且偏小 | 画法由"4 个 7dp 圆点（有一半点在选区外）"改为**角上 L 形臂（22dp/4dp）+ 四条边中点长条（26dp/4dp）**，边热区因此第一次有了视觉；命中容差 `cornerTol` 28dp（≈56dp 方形）、`edgeTol` 22→24dp（48dp 带宽，达到指引尺寸）。另新增 **40dp keep-out margin**：整图选区时把手正落在屏幕边缘，而左/右边缘 24dp 内是系统返回手势区——实测（`adb shell input swipe` 从距右缘 25dp 起）会直接退出应用 |
| 触到下限/边界无反馈 | `clampedBy()`（`ui/CropRect.kt`，纯函数）逐边比较"请求的位移"与"实际发生的位移"，被夹住时 `Haptics.tick()`（`VibrationEffect.EFFECT_TICK`，跟随系统触感设置）震一下；600ms 冷却避免沿边拖动时每帧连震 |
| 只能框选，不能缩放 | 新增 `CropViewport`（`ui/CropViewport.kt`，纯数据 + 纯函数）：contain fit × zoom、按点缩放、平移夹取。手势：**双指捏合/平移**操作画面（选区不动），**双击**在 1× 与 2× 之间切换（以双击点为锚），单指仍只操作选区；画面非整页时右上角出现 `适应屏幕`。底部增加 `整张图片` 按钮（此前"重置为整张图片"只存在于 TalkBack 自定义操作里），与自定义操作共用同一份逻辑 |
| 免责声明印两遍 | `GradeEmptyState` 里那句自带 `AI 识图可能存在误差` 的说明改为只讲流程（`逐条核对后再记入错词本`），`OCR_DISCLAIMER` 常量在同页出现一次 |
| `识别语言` 标签名不达意 | 标签改 `词表类型`（它选的是英文单词表 vs 汉字生字/词语表）。`OcrLang` 的 KDoc 补上这层语义 |
| sheet 无滚动容器 | `ModalBottomSheet` 主体加 `verticalScroll`：1.5× 字号下四行说明把两个按钮推出 sheet 高度，滚动后按钮可回到可点区域（实测 1.5× 下 `从相册选择` 由 2367→2183，进入屏内） |

**实现上偏离审计建议的地方**：
- 审计对"裁剪不能缩放"给的方向是加放大镜/缩放；实现按 **viewport 缩放 + 双击**走（选区几何仍归一化到图像坐标，缩放只是显示变换），因此选区的百分比语义、TalkBack 自定义操作与 `onConfirm(rect)` 输出都不受影响——加放大镜则要引入第二套坐标。
- 审计把"抽词页逐词预览"列为规划里的"未做"；实现时把它与**抽样时机**绑在一起（先抽后看、看的就是要跑的），而不是另做一份"示例"。代价是 X 改变时会立刻消耗一次抽样（无副作用，纯 shuffle）。

**两处刻意的行为变更**：识别结果在非空草稿上不再直接覆盖（多一次确认）；抽词页起听写用的就是页面上列出的那批词（此前是不可见的独立抽样）。

**验收证据（2026-09-16，模拟器 `HearWrite37` 1080×2424 @420dpi，`uiautomator dump` 属性/坐标断言 + 程序化像素取样 + `dumpsys vibrator_manager`）**：

| 项 | 断言 | 结果 |
|---|---|---|
| 320dp 控件行 | `wm density 540`（≈320dp 宽）下全树无 `bounds` 右边界 > 1080 的节点；`全部` chip 由 `[778,1979]` 折到下一行 `[122,2011]` | ✅ 设备 |
| 摘要固定 | 底栏读数 `已选 3 个词表 · 合计 1446 词` + `已自动合并 41 个跨表重复词` 与 `随机听写全部 1446 词` 同屏常驻；列表内不再有第二份 | ✅ 设备 |
| 逐词预览 | 预览首行 `1 future / n. · 将来,未来`；`换一批` 后预览整批更换（`warm→here`、`dumpling→back`、`at the same time→so`…） | ✅ 设备 |
| 预览即抽词 | 点 `随机听写全部 1446 词` 起听写，`1 / 2` 两词与预览第 1、2 行**逐字相同**（`here` → `back`） | ✅ 设备 |
| 语言持久化 | 选 `中文` 后强杀重启：`ocr_lang=CHINESE` 落盘，sheet 仍选中 `中文`（草稿为英文也不被推断覆盖） | ✅ 设备 + 落盘读取 |
| 语言推断 | 汉字草稿（`香蕉/学校/苹果/月亮/生日`）冷启动首开 sheet：`中文` 页签 `selected=true`（修复前恒 `英文`） | ✅ 设备 |
| 覆盖前确认 | 5 词草稿 + 识别 3 词：`识别到 3 个词` + `当前草稿有 5 个词…` + `替换/追加/取消`；`追加` → 头部徽章 `8 词`；`替换` → `3 词` | ✅ 设备 |
| 空草稿不打扰 | 无草稿时识别成功后直接填入，不弹对话框 | ✅ 源码（同一 `parseWords(...).isEmpty()` 分支） |
| 配置类错误出路 | 清空 DataStore 后跑识别：错误卡 `请先在设置中配置 OCR 服务（需自备 API Key）` + `去设置` + `关闭`；点 `去设置` → OCR 服务商页（`服务商` / `智谱 GLM`…） | ✅ 设备 |
| 把手可见性 | 整图选区下像素探测：上/下边中点、四角、左边中点均为白色把手（`>200` 通道计数 143–169/169） | ✅ 像素 |
| 边把手可拖 | 从距右缘 130px（≈40dp 外）起拖动：选区 `右 100% → 74% → 17%` | ✅ 设备 |
| 下限反馈 | 把右边一路拖到下限，`dumpsys vibrator_manager` 记录到 app 发起的 `Prebaked=TICK` effect（12:59:25.600，duration 102ms），且拖动过程中不连震 | ✅ 设备 |
| 双击缩放 | 双击画面：`适应屏幕` 按钮出现、截图像素差 1,186,217（画面确实放大）；再次双击回到整页且按钮消失 | ✅ 设备 + 像素 |
| `整张图片` 按钮 | 选区被拖到 `右 2%` 后点 `整张图片` → `左 0% 上 0% 右 100% 下 100%` | ✅ 设备 |
| sheet 滚动容器 | `font_scale 1.5` 下 `从相册选择` 初始 bottom=2424（贴屏幕下沿）；上滑后 2183，完整可点 | ✅ 设备 |

**未能闭环项（如实记录）**：
- **pinch 手势本身未在设备上采样**：`adb shell input` 无法注入双指（需要 `sendevent` 逐指位流或 uiautomator2 的多点 API）。缩放路径的设备证据来自**双击**（共用同一个 `CropViewport.zoomBy`），平移与夹取由 `CropViewportTest` 的 8 条 JVM 用例钉住。
- **`clampedBy` 的边夹取分支**只有单测覆盖：设备上把边拖到图像边界时无法区分"震了一下"与"没震"（`dumpsys` 只能看到 app 确实发过 TICK），故只断言了"下限处发了 tick"。

**门禁**：`testDebugUnitTest` **369/369** 绿（基线 355，新增 14 条：`CropViewportTest` 8 + `CropRectTest` 5 + `OcrServiceTest.inferOcrLang` 1）；`lintDebug` 无新增告警；`python3 scripts/check-assets.py` 未触及数据层。

#### C5（导航与信息架构）—— ✅ 已完成

范围：`C5` 表全部 3 行。其中第 2、3 行在 **C1c 已随导航组一并落地**（`launchSingleTop` 补齐、两处死代码删除），本阶段先按 `4c40ab7` 基线逐条核对确认，只重做**未闭环部分**。

| 项 | 做法 |
|---|---|
| 统计藏两层 | 首页顶栏**不放**第 4 个图标：360dp 宽下实测 wordmark 右缘 x=629、三个 48dp 图标占满至 1023/1080（余 11dp），再加一个必然挤压菜单图标——这正是 `C1b` 修掉的"OCR 药丸被挤没"的同一类宽度债。改为**减少动作深度**：结束页成绩卡新增 `听写统计` 入口（原本只能 首页 → 更多 → 听写统计，且离开刚写完记录的那一刻）。落地语义与既有退出规则一致——`popUpTo(HOME)` + `launchSingleTop` 一次导航压栈，**结束页的听写入口被清掉**（`DictationSessionStore.take()` 是一次性的，保留它会让"返回"落在已消费会话的死页面上），返回即首页 |
| 双击可能叠栈 | C1c 已给四个顶层路由补齐；本阶段发现**同类缺陷仍在带参路由上**（`library_list/{category}`、`library_preview/{category}/{label}` 由分类卡/词表行双击可达，同样会压两层）。新增的结束页入口也不再自写 `navigate`，统一走单顶辅助函数 `openTop`，全文件 **13 处 push 全部经它**，"新增目的地忘记加"这个类别被消灭 |
| 死代码 | C1c 已删 `HearWriteApp.startDictation` 与 `HomeViewModel.adjustStartIndex`。本阶段补上审计未列、但同类的**未用 import 7 处**（`HomeScreen.OcrLang`、`Haptics.Vibrator`、`OcrProviderConfig.jsonPrimitive`、`OcrService.buildJsonArray`、`TtsChainSpeaker.CoroutineScope`/`Job`、`TtsProviderConfig.contentOrNull`/`jsonPrimitive`、`LibraryViewModel.withLock`）——均以"符号在非 import 行零命中"逐一核实 |

**实现上偏离审计建议的地方**：审计给"统计藏两层"的方向是"提一层"（一个到顶栏）。实测顶栏没有空间，硬塞会复现 `C1b` 的挤压缺陷，故改为在**有空间的地方**（成绩卡）降低深度；首页 `更多 → 听写统计` 保留，两条路径都能到，目的地同名同源。

**验收证据（2026-09-16，模拟器 `HearWrite37` 1080×2424 @420dpi，`uiautomator dump` 属性/坐标断言）**：

| 项 | 断言 | 结果 |
|---|---|---|
| 顶栏宽度账 | 360dp 等效密度（`wm density 480`）下 `HearWrite` 右缘 629、`听写` 至 629、三图标 665–1023，余量 11px ≈ 无 | ✅ 设备 |
| 成绩卡入口在场 | 5 词跑完：`听写统计` 节点在场（`[501,1841][647,1894]`），与 `拍照批改` / `导出错词` / `清空错词本` 同列 | ✅ 设备 |
| 入口落点 | 点 `听写统计` → 统计页（`共听写 6 场`），**最新一条正是本次**（`9月16日 13:37 · 正式听写 · 5 词 · 全对 · 26 秒`） | ✅ 设备 |
| 返回不落死页面 | 统计页按返回 → **首页**（`单词列表` + `开始听写` 在场），不是已消费会话的听写页 | ✅ 设备 |
| 带参路由单顶 | 同一坐标快速双击词表行 → 预览页；按一次返回即回到词表列表（未叠两层） | ✅ 设备 |
| 门禁 | `testDebugUnitTest` **369/369** 绿；`lintDebug` `No issues found.` | ✅ |

**未能闭环项（如实记录）**：
- **"双击叠栈"的设备判据弱**：`adb shell input tap` 两次同坐标的间隔受 shell 往返限制（≈0.6 s），比真实连点慢，只采样到"双击后一次返回回到列表"这一条；`launchSingleTop` 本身由 `openTop` 单点强制，13 处 push 无一绕过（源码层可穷举）。
- **`popUpTo(HOME)` 清栈**只在"结束页 → 统计 → 返回首页"这一条路径上采样到；"从词库预览起听写后走同一入口"未单独重放（同一 `composable(Routes.DICTATION)` 分支，无分叉）。



> 说明：本节此前写作「`C4` → `C6`」，跳过了 `C5` —— 那是审计时按"阶段分组"随手排的顺序，不是"C5 无需修复"的结论。`C5` 已于 2026-09-16 完成（见上），`C6`（`WindowSizeClass`、表盘随约束、旋转不丢状态）仍待做。

### 阶段 D — 打磨（按喜好）
动效（§4）、术语统一（C7）、系统栏图标随自选主题、动态取色（Material You）。（原列表中的 `launchSingleTop`、结束页 → 本次统计入口、死代码清理已随 `C1c` / `C5` 落地。）

### 依赖与顺序
- `A2` 已按 §0.1 D-1 拍板为"恢复表盘点按"，**不再是待决项**；`AGENTS.md:94` 契约不需要改。
- `B6` 已按 §0.1 D-2 拍板为"接上"；`isCjk` 参数随之从死代码变成判据（若最终走另一条路，则须同时删参数与 3 个 `cjk*` token，不得留声明未读）。
- `B9` 是 `C4` 的前置（OCR 表单改动落在抽取后的公共件上，避免第三次复制）。
- `B1` 放最前：它改变几乎所有页面的文字度量，晚做会让其他视觉微调白做。
- 其余条目相互独立。

---

## 6. 验证方案

最初排除截图，是为了避开"让视觉模型读图再下结论"的误判风险。**该限制已于 2026-09-12 放宽**，但方法要求不变：结论必须落在**可复现的测量**上，而不是模型对图像的主观描述。三层手段，按可信度排序：

**1) 布局树 + 语义树（主验收手段，也就是无障碍验收本身）**

```bash
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
#      content-desc="起始词" / selected="true"        → A1
#      主题卡片 checked/selected 属性                 → A11
#      整行 checkable=true 而 Checkbox checkable=false → B3
```

**2) 像素采样（截图允许后新增；B6 的唯一可行验收）**

颜色不在语义树里（`uiautomator dump` 不含颜色属性），"接线是否正确"只能用像素判定。做法是**程序化取色比对，不靠眼睛**：

```bash
adb exec-out screencap -p > /tmp/x.png
python3 -c "from PIL import Image; ... 统计目标色值的像素数 ..."
#    基线 vs 改后必须可定量：B6 的判据是朱砂像素数
#    0 → 661（暗）/ 0 → 601（亮），且英文场次保持 0。
```

截图只用于**辅助确认"是哪个元素"取了色**；读图给出的是定性描述，不作结论依据。

**3) 几何断言（C2 表盘裁切 / C4 320dp 溢出 / C3 零高柱）**

在 dump 出的 bounds 上直接断言：子节点 bounds 落在父节点之内；0 词日期的柱高 ≥ 下限值。

**4) 大字号 / 横屏**

```bash
adb shell settings put system font_scale 1.5
adb shell settings put system user_rotation 1
#    重新 dump 后重复 (3)。
```

**5) 回归**

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
#    单测应全绿；lint 不应新增告警（对比基线 4c40ab7）。
```

`StatsScreen` 的零高柱、`LibraryDrawScreen` 的固定行溢出、表盘圆形裁切属于纯几何问题，用 `bounds` 三元组即可证伪/证实。

---

## 7. 不属于本次审计范围

- **对比度以外的视觉品味**（配色倾向、圆角大小、图标选择）不做评判——双主题 token 体系自洽，无 `Color(0x…)` 泄漏到功能文件（唯一例外是 `OcrCropOverlay.kt` 的 12 处 `Color.Black/White`，那是刻意与相机界面一致的深色表面，可接受）。
- **性能**（冷启动、词典解析耗时）见 ROADMAP 候选池「冷启动与内存预算」。
- **已实现得好的部分**（不重复列举于正文，仅作决策参考）：`View.keepScreenOn` 全场保持；每词/每场重置显示态（显示不会跨词泄漏）；逐秒 `liveRegion` 播报 + 字体度量稳定占位器 + `"—"` 从语义树清除；播放按钮的图标/描述/可见标签同源（`DictationScreen.kt:806`）；退出确认是真对话框；`StatusPill` 用主题 token + 文字标签（颜色从不单独承载状态）；40 个 `contentDescription` 中 35 个图标按钮全部合规；`OcrCropOverlay` 的"图像层不读拖拽状态所以拖动时不重绘"两层 Canvas 拆分；进程级选择顺序保留并贯穿到 `multiSourceLabel` 的诚实来源；`Mutex.tryLock()` 先于首次挂起的所有重入闸。
