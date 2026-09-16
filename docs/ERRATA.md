# 勘误记录

已发布的**提交信息**无法在不改写 `origin/main` 历史的前提下更正，因此把复核出的错误陈述记在这里：树内容是正确的，只有措辞与事实不符。

## 2026-09-14 复核 `v0.6.0..6af093c`

复核方式：逐 commit 重建（`git worktree`）、沙盒重跑生成器逐字节对比、`uiautomator dump` 在真机/模拟器上取属性断言、三棵树的 `lintDebug` 全量重跑。

### 1. `6af093c` 的 "testDebugUnitTest 328/328"

该 commit 树上单元测试是 **327**（`git grep -c "@Test" 6af093c -- 'app/src/test/*'` = 327，HEAD 实跑同样 327）。它真正新增的是**仪器测试**：`RenamedLabelMigrationTest`（androidTest 6 → 7），提交信息里的 "connectedDebugAndroidTest 7/7" 是对的，`328/328` 把两者混在了一起。

### 2. `4012f66` 的 "all 19 new label chars are already in it"

该 commit 自己的树里 `domain/LabelOrder.kt` 的 `HANZI` 表仍只有 **57** 条，而它引入的 19 个七上标签含 **72** 个汉字，其中 **60 个不在表内**（53 个只出现在七上标签、7 个与七下共有，如 `春 济 梅 造 邓 猫 鸽 雁 皇 娲 寓 秋 韵 散 诗 屋`…）。表由**下一个** commit `1b3fc9b` 的 121 条补丁补齐（121 = 七上独占 53 + 七下独占 61 + 共有 7），`6af093c` 的 60 条再把八上补完；HEAD 为 238 条、覆盖全部 237 个标签汉字。

**无用户可感知后果**：实测 `4012f66` 与 `1b3fc9b` 的人教版初中语文顺序逐项相同——这些标签先在 `<课号>` 数字段上分开，`naturalCompare` 在 CJK 比较之前就分出胜负。

**但这暴露一个无守卫的缺口**：`LabelOrderTest` 只断言 `compareLabels` 与 `library-label-order.json` 一致，而该 fixture 是用**同一个比较器**重新生成的——缺字时两侧一起退化，测试仍全绿（`4012f66` 就是这样：缺 60 个字、327 个测试全绿）。

**已闭环**：新增 `domain/LabelOrderCoverageTest`（遍历 `app/src/main/assets/` 的真实分类名与词表名，断言每个 CJK 字符都在 `HANZI` 中，比较器侧新增 `internal fun hanziTableGaps` 作为测试缝）。已在 `4012f66` 那棵树上验证它会红并逐字列出那 60 个缺口（`git worktree add /tmp/hw401 4012f66` + 打上同款补丁），在 HEAD 上为绿。`docs/WORDLIST.md` §5 第 3 步与 `AGENTS.md` 的排序契约已同步指向该测试。

### 3. 阶段 A/B 的 "lintDebug 仅剩 6 条 GradleDependency"

在本机不可复现：`v0.6.0`、`f1b1056`、`6af093c` 三棵树各跑 `./gradlew :app:lintDebug --rerun-tasks`（另试 `-Pandroid.lint.checkDependencies=true`）均为 `No issues found.`，报告里 `GradleDependency` 计数为 0（版本目录已追平最新稳定版）。记录在此以免后续把"6 条已知告警"当作可对齐的基线。

### 4. 阶段 B 的"净 −347 行"

`docs/UI-AUDIT.md` §5 已就地更正为 **净 +581 行**（`app/` 内 +2362 / −1781）：`1434` 那份手算漏掉了本 commit 新建文件的正文行（`SettingsProviderForms.kt` 770 + `Feedback.kt` 100 + `Format.kt` 58）。收口本身确在删除，只是新增的共享件把总量拉正。

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
