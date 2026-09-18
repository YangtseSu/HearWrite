# 数据模型重构（英文 + 中文）

状态：📋 spec（有设计）→ 实施中。
来源：2026-09-18 音标方案评审；作者拍板两处（音标英美双套、行文本第 4/5 列），随后升级为整体数据模型重构——**抛弃历史兼容，只考虑作者自己**。

---

## 0. 结论

把"词表行"与"词典条目"拆成两个东西。词表行只携带**作者权威**（该词表印什么、按什么顺序、念什么），词典条目是**共享的查询表**（ECDICT 义项 + ipa-dict 音标 + 仁爱教材音标覆盖）。二者在读取时合成，**运行时零写回**。

这一条同时消灭：

| 现有缺陷 | 由哪个决定消灭 |
|---|---|
| `enrichLines` 把词典数据写进行文本（3 处静默丢列、11 处 round-trip） | "行永不被改写" |
| "已有列就不补"导致仁爱 1,806 行永远拿不到音标 | "逐字段回退" |
| `pos` 列 = 词性(EN) / 拼音(HANZI) 的语义重载 | `kind` 显式字段（解析时定，不再靠 CJK 正则猜） |
| `speak` 靠 `speakTextFromEntry` 的 22 处启发式切分 | `Row.speak` 显式字段 |
| ECDICT 释义内部 `;` 被有损折成 `，`（`normalize_meaning`） | `LexEntry.senses: List<Sense>` 结构化 |
| 词典 value `"pos\|gloss"` 的扁平串无法扩展 | 结构化 JSON（`senses` + `ipa`） |

音标是顺带的：美式 ipa-dict 覆盖 97.6% 单 token；仁爱教材 IPA（英 1,447 词头）优先于 ipa-dict（教材是权威）。

---

## 1. 数据模型

### 1.1 行（作者数据）—— `.txt` 文件，一行一列格式不变

```kotlin
/** 词表的一行：作者数据。有序、可手编、可 diff、永不被补全改写。 */
data class WordRow(
    val display: String,       // 展示/作答/错词键。可含 "you're = you are"
    val speak: String,         // TTS 真正念的串（解析时从 display 的 "=" 左侧得出）
    val kind: WordKind,        // EN | HANZI | WORD — 解析时由词头唯一决定
    val pos: String?,          // 该表权威：词性(EN) / 拼音(HANZI)
    val gloss: String?,        // 该表权威：释义(EN) / 组词(HANZI)
)

enum class WordKind { EN, HANZI, WORD }

fun kindOf(display: String): WordKind = when {
    !display.contains(CJK_RE)   -> WordKind.EN
    display.length == 1         -> WordKind.HANZI
    else                        -> WordKind.WORD
}
```

`.txt` 文件一行不改。651 份 / 21,769 行。

### 1.2 词典（`LexEntry`）—— 只读、带版本、可重生成

两个文件，拆开是因为中文-only 列表（课标字表 3,800 行裸汉字）绝不该为英文词典的 3.4 MB 买单：

```
app/src/main/assets/
  dict/lexicon-en.json      ← 英文：headword → { senses, ipa }
  dict/lexicon-hanzi.json   ← 汉字：char → { pinyin, compound }
  compounds/compounds.json  ← 不变（组词候选池，另一个生成器、另一个来源）
```

```kotlin
data class LexEntry(
    val senses: List<Sense>,   // 结构化义项，不是 "n. 苹果；v. 放" 一整串
    val ipa: Ipa?,             // { us, uk }，各自可空
)
data class Sense(val pos: String?, val gloss: String)
data class Ipa(val us: String?, val uk: String?)
```

`lexicon-en.json` 的 schema（带版本头）：

```json
{
  "v": 2,
  "entries": {
    "let":     { "s": [{"p":"v.","g":"让；允许"}],
                 "i": ["let", "let"] },
    "fine":    { "s": [{"p":"adj.","g":"身体好的，健康的；很好"},
                       {"p":"v.","g":"对……处以罚款"},
                       {"p":"n.","g":"罚款"}],
                 "i": ["faɪn", "faɪn"] },
    "rubbish": { "s": [{"p":"n.","g":"垃圾"}] }
  }
}
```

`"i"` 是 `[us, uk]` 数组（缺失则整项省略）。**音标源优先级：仁爱教材 > ipa-dict**（教材是权威，见 §3.2）。

`lexicon-hanzi.json`：

```json
{ "v": 2, "entries": { "月": { "p": "yuè", "c": "月亮" } } }
```

`p` = 拼音（带调，无调原样）；`c` = 组词，**缺失表示"无组词可取"**（现有 `hanzi-meta.json` 的 `拼音|` 两段式已被 §2.4 的结构替代）。

### 1.3 合成（运行时消费的唯一类型）

```kotlin
/** 行覆盖 + 词典回填 = 拨盘/引擎实际看到的。纯函数结果，零持久化。 */
data class ResolvedWord(
    val display: String,
    val speak: String,
    val kind: WordKind,
    val senses: List<Sense>,   // EN
    val pinyin: String?,       // HANZI
    val compound: String?,     // HANZI
    val ipa: Ipa?,             // EN
)
```

### 1.4 解析规则

```
行覆盖 > 词典
```

逐字段：`senses = row.pos/gloss 拼成单义项 ?: lexicon.senses`；音标只查 lexicon（行不覆盖音标）。

这条规则消灭了旧设计的根本缺陷：仁爱 1,806 行带 pos/gloss，旧规则 `if (entry.pos != null || entry.meaning != null) return line` 导致这些行永远拿不到音标。新规则逐字段回退，仁爱行照常拿到 `/let/`。

---

## 2. 文件格式

### 2.1 词表 `.txt`——一行不改

651 份文件保持原样。原因：① 作者数据与词典数据已分离，改文件没有意义；② 21,769 行的 diff 会让 review 不可读。

### 2.2 词典资产（替代 `ecdict-meta.json` / `hanzi-meta.json`）

生成脚本：

| 旧 | 新 | 来源 |
|---|---|---|
| `build-ecdict-meta.py` | `build-lexicon.py` | ECDICT（义项）+ ipa-dict（音标）+ `scripts/data/renai-ipa.tsv`（教材覆盖） |
| `build-hanzi-meta.py` | `build-hanzi-lexicon.py` | 词表汉字行 + 常用词表 + overrides（与现逻辑同） |

生成器仍为纯 stdlib、首次运行联网（下载 ECDICT / ipa-dict 到 `.cache/`），`.gitignore` 已覆盖。

---

## 3. 仁爱教材 IPA 导入

### 3.1 原始数据

`~/Downloads/仁爱版英语七上 / 七下 / 八上`（41 份 OCR 页文件 + 合并版）。提取脚本解析后：

| 项 | 值 |
|---|---|
| 有音标的行 | 2,947 |
| 去重后不同词头 | **1,447**（1,514 行解析成功 → 1,447 词头） |
| 其中英美都有 | 377 |
| 只有英式（单音标=教材只印了一个，两音相同） | 1,073 |
| 解析拒绝（无词头形 / 音标字符越界） | 228 |
| 音标记号 | 全部合规 IPA：`ə ɪ ʊ ʌ ɒ ɔ ɑ æ ɜ ʃ ʒ θ ð ŋ ɡ ɹ ˈ ˌ ː (r)`，Roboto 全覆盖 |
| 重音位置 | **英式在前、美式在后，20/20 全对**（`dance dɑːns;dæns`、`car kɑː;kɑːr`、`tomato təˈmɑːtəʊ;təˈmeɪtəʊ`、`hot hɒt;hɑt`） |
| 仁爱 1,796 个已提交词头中，原始数据覆盖 | 1,514（84.3%） |
| 仁爱与 ipa-dict 的记号差异 | 教材 `/freɪnd/` vs ipa-dict `/fɹˈɛnd/`——**教材是学生课本上印的那个，应优先** |
| 仁爱内部冲突 | 仅 `people` 一词（`ˈpiːpl` vs `ˈpiːpəʊl`），两条都留 |
| 解析拒绝 | 无词头形 159（全是节标题/续行）、音标字符越界 69（OCR `**` 污染等）→ 退回 ipa-dict 覆盖 |

提取产物 `scripts/data/renai-ipa.tsv`（1,447 行 `headword\tus\tuk`）随仓库提交，保证重生成不依赖外部目录。

### 3.2 为什么教材优先

学生看到的音标要和课本一致（AGENTS.md：内置词库权威教材同步）。实测记号差异：
- ipa-dict en_US 用 `ɫ`（深 l）、`ɹ`、`ɝ`——教材不印这些符号。
- 仁爱教材 `/freɪnd/`、`/ˈsiːzn/` 是学生课本上印的那个。
- 记号差异不影响读音正确性，但影响学生把课本和应用对照。

### 3.3 为什么不存进词表行

仁爱教材印的音标是**词典数据**（`let` 这个词的读音），不是仁爱列表的属性——同一个词出现在 中考1600、高考3500 等十几份词表里，音标应该出现在每一份里。存进词表行意味着 78 份文件都要写，而词表覆盖只有仁爱的 3 卷。

与释义的区别：释义确实是"某张表说它是这个意思"的属性（仁爱印 `让；允许`，ECDICT 印 `允许`），所以释义留在行上。音标不属于任何一张表。

---

## 4. 性能实测（API 37 模拟器，`kotlinx.serialization` 真实路径）

| 指标 | 现状（3 列） | 新设计 | 换成预置 SQLite |
|---|---|---|---|
| 词典资产 raw | 3,376 KB | ~5,200 KB（`lexicon-en` ~4,600 + `lexicon-hanzi` ~120） | 4,964 KB |
| APK 内 deflate | 1,162 KB | ~1,400 KB（**+240 KB**） | 2,308 KB |
| 冷解析 | 24–48 ms | 同量级 | 0（改由页缓存代付） |
| 常驻堆（GC 后） | 10 MB | ~14 MB（**+4 MB**） | ≈0 + IO 查询 |
| 拨盘提示行宽度 | — | 中位 60 dp，p99 113 dp（**204 dp 盘内 0 溢出**） | — |
| 拨盘几何改动 | — | **零**（音标与词性合成同一行，`dialFit` 的 tail 不变） | — |

+4 MB 堆是 `List<Sense>` 替代扁平串的代价。可接受，不需要数据库（AGENTS.md 的 500 ms 迁库阈值差一个数量级）。

**字体不是风险**：实际会用到的 52 个 IPA 字符，设备上 Roboto 全部覆盖（`Roboto-Regular.ttf` / `RobotoStatic-Regular.ttf` 缺失 0 个）。

### 4.1 拨盘显示

| 界面 | 显示 | 实测 |
|---|---|---|
| **拨盘**（听写中） | 音标**与词性合成同一行**：`/ˈæpəl/ n.` | 204 dp 盘、单行词时内容框宽 **147.5 dp = 9.8 units**；美式音标宽度中位 **4.0 u（60 dp）**、p99 7.5 u、最大 10.0 u —— **超宽仅 2 / 13,905（0.0%）**，超出的走现有「展开全部 → 详情卡」 |
| **详情卡** | 并列 `英 /ˈæpəl/ · 美 /ˈæpəl/` 独立一行 | 中位 **8.5 u（128 dp）**；超过 300 dp 对话框宽的仅 **1 / 3,170** —— 该卡是 `verticalScroll`，多折一行无代价 |
| 首页展示行 / 词库预览行 | hint 由 `pos meaning` 变 `/音标/ pos meaning` | 仍是单行 `Text` + 2 行 clamp，行最小高 52 dp，LazyColumn 自行吸收 |
| 抽词预览行 | 同上（`labelMedium` 单行） | 最紧的一处，但仍是一行 |

**关键取舍：音标绝不独立成第四行。** 实测 `dialFit` 的影子高度模型（`tailDp`）：多一行 15sp/24dp 提示行会把 204 dp 盘上的两行词从"可容纳"推到必须缩到 **25–27 sp**（`wordMinSp` 下限是 22）。合成一行则 `DialMetrics`、`tailDp`、`dialContentWidthDp`、`dialStageGeometry` **零改动**，几何部门完全不动。

配套的小重构：`dialFit(word, gloss, hasPos, …)` 的 `hasPos` 语义已变成"有一行提示"，改名 `hasHint`（主代码 2 处 + `DialFitTest` 10 处调用点）。

### 4.2 显示规则

| 界面 | 规则 |
|---|---|
| 拨盘 | 显示**美式**（第 4 列/`i[0]`），因为 TTS 链路默认美式 |
| 详情卡 | `英 /…/ · 美 /…/` 并列；只查到一套时显示一套、不标另一套 |
| 列表行 | `英 /…/ · 美 /…/ · 词性 · 释义` 拼接进现有单行 |
| 无音标的行 | **不显示占位符**，直接不出这一段 |

---

## 5. 与参考设计的差异（及理由）

参考设计（截图，别的 AI 的方案）提出：`LexEntry(headword, kind, senses, ipa, tags)` + `Row(id, kind, display, speak, overrides)`。

| 参考设计 | 我的取舍 | 理由 |
|---|---|---|
| `LexEntry.kind` 显式字段 | **不存** | kind 由词头唯一决定（单汉字→HANZI、多汉字→WORD、否则 EN），解析时算一次存到 `ResolvedWord` 上；存了反而多一个可漂移字段 |
| `LexEntry.tags: Set<Tag>` | **不存** | 中考1600 里的词不需要 `zk` 再告诉它一次——词表本身就是标签；app 无消费者，存了就是死数据 |
| `Row.id: String` 稳定主键 | **不加** | 错词/收藏/统计全按 `speak` 键；改了词头，错词记录应该失效（学生标的就是那个拼法）。改词不改 id 反而会让错词指向一个已不存在的词 |
| `Row.speak` 独立字段 | **采纳** | 整库仅 2 行用 `=`，但它是唯一的 kind 相关解析规则，抽成字段后 `speakTextFromEntry` 的 22 处调用点消失 |
| `Row.overrides` | **采纳** | 正是解决"仁爱版 gloss ≠ ECDICT gloss"的正确机制 |
| 词典合入一个文件 | **拆两个** | 中文-only 列表不该解析 53k 英文词条（AGENTS.md "never on the startup path"） |

---

## 6. 实施计划

### Phase 1 · 词表行模型重构（不碰词典资产）

| # | 文件 | 变更 |
|---|---|---|
| 1 | `domain/WordKind.kt` **新建** | `enum WordKind { EN, HANZI, WORD }` + `fun kindOf(display: String): WordKind` |
| 2 | `domain/WordLine.kt` → `domain/Row.kt` | `WordEntry(word,pos,meaning)` → `WordRow(display, speak, pos?, gloss?)`；`parseWordLine` 返回 `WordRow` 并填充 `speak` |
| 3 | `domain/ResolvedWord.kt` **新建** | 如 §1.3 |
| 4 | `domain/SpeechText.kt` | `speakTextFromEntry`/`isCjkEntry`/`findLineByHeadword` 的 22 处调用点全部改为消费 `WordRow`/`ResolvedWord` 字段 |
| 5 | `domain/DictationEngine.kt` | `start(lines: List<String>)` → `start(rows: List<WordRow>)` |
| 6 | `data/DictationSessionStore.kt` | `lines: List<String>` → `rows: List<WordRow>` |
| 7 | 测试 | `WordLineParserTest` / `SpeechTextTest` / `DataFixtureTest` 重写为新契约 |

**验收**：`testDebugUnitTest` 全绿；`grep -r "speakTextFromEntry\|isCjkEntry"` 在 `main` 下返回 0。

### Phase 2 · Lexicon

| # | 文件 | 变更 |
|---|---|---|
| 8 | `scripts/data/renai-ipa.tsv` **新建** | 1,447 行，从 `~/Downloads/` 提取，随仓库提交 |
| 9 | `scripts/build-lexicon.py` **新建**（替代 `build-ecdict-meta.py`） | ECDICT 义项结构化 + ipa-dict + renai-ipa.tsv → `dict/lexicon-en.json` |
| 10 | `scripts/build-hanzi-lexicon.py`（改名自 `build-hanzi-meta.py`） | 输出 `dict/lexicon-hanzi.json` |
| 11 | `data/LexiconRepository.kt`（替代 `DictionaryRepository.kt`） | `lookup(headword): LexEntry?`；**删 `enrichLines`/`enrichText`** |
| 12 | `scripts/check-assets.py` | 校验新 JSON schema（`s` 数组、`i` 数组、字符白名单） |
| 13 | `docs/WORDLIST.md` / `AGENTS.md` / `README.md` | 词表格式说明更新 |

**验收**：`check-assets.py` 0 error；`LexiconRepositoryTest` 钉住 仁爱覆盖词 / ipa-dict 词 / 双源缺失词。

### Phase 3 · 管线切换（消掉所有写回）

| # | 变更 |
|---|---|
| 14 | `HomeViewModel`：删 `enrichDraft()`；展示态直接 `resolve(parseDraft(text), lexicon)`，**草稿永不被改写** |
| 15 | `LibraryViewModel` / `LibraryDrawViewModel`：删 `needsEnrich` / `enrichSettled`；直接 resolve |
| 16 | Room v5：`ALTER TABLE history DROP COLUMN enriched_text`（SQLite ≥ 3.35 / API 33+） |
| 17 | `HearWriteApplication`：resolver 不再 enrich，直接 resolve |
| 18 | `WrongWordLineResolver` / `AnswerGrading` / `DictationGradePane`：消费 `ResolvedWord` |
| 19 | `ui/DictationScreen`：拨盘合成行 + 详情卡并列 `/英/ /美/` |

**验收**：`grep -r "enrichLines\|enrichText\|enrichedText"` 返回 0；模拟器走查：仁爱版行显示教材音标、中考1600 行显示 ipa-dict 音标、拨盘拨到 仁爱行 `/let/ v.`。

### Phase 4 · 文档 + 提交

`CHANGELOG.md` 新版本段；commit 信息含体积/堆/解析三组前后实测值。

---

## 7. 测试改动清单

**必须替换（不是改预期）**：`WordLineParserTest` 的 `columns beyond meaning are ignored`（第 4 列不再是"被忽略"）—— 它是旧契约本身，改测试名与断言。

**必须新增**：5 列 round-trip（含 `英` 有 `美` 无的占位往返）；`decodeStored` 对 2 段旧值与 4 段新值的兼容；空音标必须留空不得回填；`enrichLines` 对"已有 pos/meaning 的英文行只补音标、不改动前 3 列"。

**会跟着红/需同步**：`DataFixtureTest` 的 `entries.forEach { assertEquals(it, parseWordLine(entryToLine(it))) }`（全库 round-trip，是这一改动最强的守门测试）；`DictionaryRepositoryTest` 的 `enrichText` 字符串字面量；`WordLineParserTest` 的 `entryToLine keeps empty columns as separators`。

**不受影响**：`DialFitTest`/`DialStageTest`/`DialHiddenStackTest`（几何零改动，除非做 `hasPos` 改名）、`StatsTest`、`AnswerGradingTest`、`CjkWordSpeechTest`、各迁移测试。

---

## 8. 风险与已知缺口

| 风险 | 缓解 |
|---|---|
| Phase 1 动引擎 | 引擎切换放 Phase 3 最后；Phase 1 只改模型 + 单测 |
| 仁爱 228 行拒绝（OCR `**`/缺斜杠/空格读音） | 退回 ipa-dict 覆盖，不丢音标 |
| 初中2182 的 2,098 行 3 列（alice ECDICT 派生）与新 lexicon 不一致 | 保留为行级覆盖（补全规则生效），不删——差异化就是作者权威 |
| 拨盘合成行 2% 溢出 | 展开全部 → 详情卡承接（现有行为） |
| 常驻堆 +4 MB | 可后续延迟加载 `senses`；先不做 |
| 历史去重会断一次（旧 3 列行 + 新 5 列行精确文本不等） | 一次性、无数据损坏；作者确认可接受 |

---

## 9. 明确不做

- 不换 Room / 不引 `createFromAsset`（实测 25–57 ms ≪ 500 ms 阈值）。
- 不改 651 份 `.txt` 的一行。
- 不给汉字行加 IPA。
- 不独立成拨盘第四行（几何代价实测过）。
- 不让视觉模型输出音标（会幻觉），OCR 提示语保持原样。
- 不提交 ipa-dict / 仁爱原始页面到仓库（只提交提取产物 `renai-ipa.tsv`）。
- 不加 `tags` / `Row.id` / `LexEntry.kind` 字段（理由见 §五）。
