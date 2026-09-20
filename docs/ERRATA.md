# 勘误记录

已发布的**提交信息**无法在不改写 `origin/main` 历史的前提下更正，因此把复核出的错误陈述记在这里：树内容是正确的，只有措辞与事实不符。

## 2026-09-14 复核 `v0.6.0..6af093c`

复核方式：逐 commit 重建（`git worktree`）、沙盒重跑生成器逐字节对比、`uiautomator dump` 在真机/模拟器上取属性断言、三棵树的 `lintDebug` 全量重跑。

### 1. `6af093c` 的 "testDebugUnitTest 328/328"

该 commit 树上单元测试是 **327**（`git grep -c "@Test" 6af093c -- 'app/src/test/*'` = 327，HEAD 实跑同样 327）。它真正新增的是**仪器测试**：`RenamedLabelMigrationTest`（androidTest 6 → 7），提交信息里的 "connectedDebugAndroidTest 7/7" 是对的，`328/328` 把两者混在了一起。

### 2. `4012f66` 的 "all 19 new label chars are already in it"

该 commit 自己的树里 `domain/LabelOrder.kt` 的 `HANZI` 表仍只有 **57** 条，而它引入的 19 个七上标签含 **72** 个汉字，其中 **60 个不在表内**（53 个只出现在七上标签、7 个与七下共有，如 `春 济 梅 造 邓 猫 鸽 雁 皇 娲 寓 秋 韵 散 诗 屋`…）。表由**下一个** commit `1b3fc9b` 的 121 条补丁补齐（121 = 七上独占 53 + 七下独占 61 + 共有 7），`6af093c` 的 60 条再把八上补完；HEAD 为 **244** 条、覆盖全部 **243** 个分类名与词表名里的汉字（计数方式见 2026-09-20 复核第 4 条）。

**无用户可感知后果**：实测 `4012f66` 与 `1b3fc9b` 的人教版初中语文顺序逐项相同——这些标签先在 `<课号>` 数字段上分开，`naturalCompare` 在 CJK 比较之前就分出胜负。

**但这暴露一个无守卫的缺口**：`LabelOrderTest` 只断言 `compareLabels` 与 `library-label-order.json` 一致，而该 fixture 是用**同一个比较器**重新生成的——缺字时两侧一起退化，测试仍全绿（`4012f66` 就是这样：缺 60 个字、327 个测试全绿）。

**已闭环**：新增 `domain/LabelOrderCoverageTest`（遍历 `app/src/main/assets/` 的真实分类名与词表名，断言每个 CJK 字符都在 `HANZI` 中，比较器侧新增 `internal fun hanziTableGaps` 作为测试缝）。已在 `4012f66` 那棵树上验证它会红并逐字列出那 60 个缺口（`git worktree add /tmp/hw401 4012f66` + 打上同款补丁），在 HEAD 上为绿。`docs/WORDLIST.md` §5 第 3 步与 `AGENTS.md` 的排序契约已同步指向该测试。

### 3. 阶段 A/B 的 "lintDebug 仅剩 6 条 GradleDependency"

在本机不可复现：`v0.6.0`、`f1b1056`、`6af093c` 三棵树各跑 `./gradlew :app:lintDebug --rerun-tasks`（另试 `-Pandroid.lint.checkDependencies=true`）均为 `No issues found.`，报告里 `GradleDependency` 计数为 0（版本目录已追平最新稳定版）。记录在此以免后续把"6 条已知告警"当作可对齐的基线。

### 4. 阶段 B 的"净 −347 行"

`docs/implemented/2026-09-12-UI-AUDIT.md` §5 已就地更正为 **净 +581 行**（`app/` 内 +2362 / −1781）：`1434` 那份手算漏掉了本 commit 新建文件的正文行（`SettingsProviderForms.kt` 770 + `Feedback.kt` 100 + `Format.kt` 58）。收口本身确在删除，只是新增的共享件把总量拉正。

## 2026-09-16 复核 `9d181ad`（release 0.8.0）

### 1. 两条 `sessions` 记录行的来源对调

该 commit 的正文写：

> 听写统计 lists both sessions (正式听写 5 词 · 错 1 · 未知来源; 复习错词 全对 · 10 秒)

实测两行的来源正好相反（同一台模拟器、同一份 signed release APK）：

```
9月16日 08:22 · 复习错词   →  未知来源 · 1 词 · 全对 · 10 秒
9月16日 08:21 · 正式听写   →  香蕉 · 5 词 · 错 1 · 正确 4 · 27 秒
```

即**正式听写**那场带回源词表 `香蕉`，**复习错词**那场是 `未知来源`——后者是刻意设计而非缺口：`DictationViewModel` 的复习轮以 `sourceLabel = null` 起跑（`review` 轮是对错词本的再检查，不对应任何一张词表），`SourceTitles` 于是把它降级为 `未知来源`，与 0.7.0 的行为一致。树内容与门禁数字均无误，只有正文两行写反了。

## 2026-09-20 复核 `v0.8.0..HEAD`（0.9.0 周期）

复核方式：按切片并行深读 `v0.8.0`..`HEAD` 的 41 个提交，每一条结论再回树独立复算（`git show --stat`/`git log --format=%B` 读正文，`python3` 脚本重算计数）；全文见 `docs/implemented/2026-09-20-REVIEW-0.9.0.md`。本节只记**已发布的措辞与树不一致**的部分。

### 1. `ab928f7` 的 "the full-library round-trip in DataFixtureTest"

声明（提交正文）：验收行称 `DataFixtureTest` 里的往返序列化是**全库**递增守门（"incl. the full-library round-trip"）。

树里的事实：该测试的往返断言只有一条（`DataFixtureTest` 里 `chinese writing-list rows parse into pinyin and compound columns` 的 `entries.forEach { assertEquals(it, parseWordLine(rowToLine(it))) }`），输入来自同一个测试选定的 `人教版小学语文/二上 写字表 识字 1.txt`——上一行的 `assertEquals(7, lines.size)` 钉住它只有 **7 行**，即**一个文件的 7 行**。真正跨表的 `WordLineParserTest` 往返用 5 条手写行；本轮没有"全库往返"这一条测试。

结论：只有措辞失实，门禁数字（396 绿）与树内容都无误，故不改动任何测试。全库行为在本次复核里由独立重放覆盖（651 表 / 21,769 行，speak 文本 / kind / 往返字节 0 差异）；若要把这条写进测试，那是新增覆盖的工作，不是修正声明。

### 2. `34a5c5c` 的 "4 files, +21/-20" 与 "17 验收证据 headers"

声明（提交正文）：`git diff --stat` = 4 files, +21/-20；且改写了 "17 验收证据 headers"。

树里的事实：`git show --shortstat 34a5c5c` = **4 files changed, 29 insertions(+), 29 deletions(-)**（`--numstat` 逐文件：`DEVELOPMENT.md` 2/2、`ROADMAP.md` 3/3、`2026-09-04-PHASES.md` 11/11、`2026-09-12-UI-AUDIT.md` 13/13）。该 diff 里匹配 `^+.*验收证据` 的行是 **13** 行，且 13 个 `验收证据` header 全在 `2026-09-12-UI-AUDIT.md`（`2026-09-04-PHASES.md` 里为 0）。

结论：文件数对，增删行数与 header 数都写错（21/20 应为 29/29，17 应为 13）。**扫描结论本身成立**——对受控文件的型号 / 序列号 / AVD 名检索在 `HEAD` 上为空；本节第 5 条记的是这次扫描没覆盖到的**提交正文**。

### 3. `b606413` 与 `2026-09-12-UI-AUDIT.md` 的 "13 处 push 全部经 `openTop`"

声明（`b606413` 正文，同一句也进了 `docs/implemented/2026-09-12-UI-AUDIT.md` 的 C5 段与"未能闭环项"）：`openTop` 有 13 个调用点，全文件 push 无一绕过。

树里的事实：`ui/HearWriteApp.kt` 里 `openTop(` 的**调用点 11 处**（Home 5 个入口、Stats 的来源跳转 1 处、Library 3 处、LibraryLists 2 处）；`openTop` 本身在 `HearWriteApp` 里声明一次（声明行是 `val openTop: (String) -> Unit`，不匹配 `openTop(`，不计入）。全文件 `navigate(` 恰好 3 处：`openTop` 定义体内 1 处；另两处是 `startSession` 的 `DICTATION` push 与结束页成绩卡入口的 `STATS` push，**两处都自带 `launchSingleTop`**（后者还有 `popUpTo(HOME)`）。`b606413` 那棵树上同样是 11（`git show b606413:app/src/main/java/org/yangtse/hearwrite/ui/HearWriteApp.kt | grep -c "openTop("` = 11）——是当次数错，不是后来漂移。

结论：**无行为缺陷**（该行想消灭的"新增目的地忘记加 flag"确实被消灭了：两处直连 push 都有 flag，且一处是刻意的 `popUpTo` 清栈）。数字在 ERRATA 与本文件中就地更正为 11。

### 4. 本文件 2026-09-14 第 2 条的 "HEAD 为 238 条"（已在原地更正）

声明（本文件自己的行文）：`HANZI` 表 "HEAD 为 238 条、覆盖全部 237 个标签汉字"。

树里的事实：`domain/LabelOrder.kt` 的 `private val HANZI = mapOf(…)` 共 **244** 个键（解析该块、计数 `'X' to (`，244 个键互不重复）；`app/src/main/assets/` 下**全部**分类名与 `.txt` 表名（排除 `dict`/`compounds`/`audio`/`licenses` 四种非词库资产目录，651 份词表 + 11 个分类）里的 CJK 汉字去重后是 **243** 个，`hanziTableGaps` 为空（缺 0）。表里比名字多一个 `十`——它在表头注释里就说明了是给前缀数字串用的，属于有意包含。

那 238 是阶段性数字：`1b3fc9b` 时 178 条 → `6af093c` 时 238 条 → `9e28448`（仁爱版入库，2026-09-15）补到 244 条，`9d181ad`（0.8.0）起一直是 244。原句把只对 `6af093c..9e28448` 成立的中途值写成了 `HEAD`，已在上方该条就地更正为 244 / 243 并注明计数方式。

### 5. 提交**正文**里的 AVD 名与设备序列号

声明（`34a5c5c` 的扫描结论 + `AGENTS.md` 的卫生规则）：受控内容不含设备型号、序列号或 AVD 名。

树里的事实：**文件**侧成立——本次复核对 `docs/`、`AGENTS.md` 的 AVD 名检索为空，`git grep` 侧与 `34a5c5c` 的结论一致。**提交正文**侧不成立：范围内有 13 个提交的验收/证据行写着模拟器 AVD 名（按时间：`5e7b1cd`、`22e4b91`、`f2a8b8d`、`f8edd88`、`d05c143`、`fc5d10d`、`582021b`、`be87e47`、`ca7dd40`、`186aad1`、`516d73c`、`b606413`、`617bc55`），其中 8 个是同一个名字、4 个是第二个名字、1 个（`ca7dd40`）是第三个；另有 `394aaed` 的正文把两个**设备序列号**原文抄进了"我们丢掉了什么"那一段。

计数方式：对 `git log v0.8.0..HEAD --format=%B` 逐条扫，凡出现"字母开头、带 CPU 代号样风格后缀或 API 等级后缀"的短标识（模拟器名的典型形状），再逐条回看上下文确认它确实在指代某台模拟器；同批 `pixel` 作为"像素"普通词出现的不计。`docs/implemented/2026-09-20-REVIEW-0.9.0.md` §5.10 记的是其中 8 个 AVD 名提交加 `394aaed`；本次复核另在 `617bc55`、`186aad1`、`516d73c`、`b606413`、`ca7dd40` 找到同类，完整集合即上面 13 个加 `394aaed`。

结论：按 `AGENTS.md` 的规则，本节**不复现**任何具体 AVD 名或序列号，只记类别与提交号——它们正是不该出现在仓库里的东西。历史不改写；后续提交只写 `真机` / `模拟器` / `device`（该规则由 `acd8d76` 落进 `AGENTS.md`，但晚于上述全部提交，`ca7dd40` 也在其中）。

### 6. `CHANGELOG.md` 的 0.9.0 节漏记三个已随该版发布的功能

声明（`CHANGELOG.md` 0.9.0 节 = `v0.9.0` 的 GitHub Release 正文）：发布时该节只列了词库 / 听写 / 数据三组。`grep "取色\|横屏\|折叠\|识别服务" CHANGELOG.md` 在该节上全空。

树里的事实：三个用户可见变更都落在 `v0.8.0..v0.9.0` 内、且已随 0.9.0 的包发出去，却没进这一节——**动态取色**（`be87e47`，设置 → 外观，默认关）、**大屏 / 横屏 / 折叠屏适配**（`516d73c`，横屏舞台重排 + 720 dp 阅读宽度）、**OCR 面改名「识别服务」**（`22e4b91`）。

结论：`CHANGELOG.md` 的 0.9.0 节**已就地补上**这三条（新装用户与老用户拿到的都是同一个 0.9.0 包，说明本就该有）。已经发布的 GitHub Release 正文无法在不编辑 release 的前提下更正，故记在这里。

### 7. `2026-09-18-ROADMAP-DONE.md` 的 `Modifier.contentWidth` "约 40 处调用点"

声明（归档文档）：`Modifier.contentWidth` "约 40 处调用点覆盖各页面与两条底栏"。

树里的事实：按 `.contentWidth(` 精确匹配，`app/src/main/java` 下命中 **30** 行，其中 `ui/Adaptive.kt` 里 `fun Modifier.contentWidth(…)` 那一行是该修饰符的**定义**，其余 **29** 处是调用点（`app/src/test` 下 0 处）。口径必须说清：不把定义计入、"40" 无论按哪种口径都不成立（连"调用点 + 定义"也只有 30）。该文档已就地改为 29（并注明另有 1 处定义）。

结论：修正的是文档事实，不是历史声明；写上口径是为了让下一个读者不会用另一种数法得出另一个数（`contentWidth` 作为词根还会出现在 `domain/DialFit.kt` 的 `contentWidthDp` 里，那是另一个符号）。
