# 拍照识词与拍照批改（BYOK 视觉）

本文是 [`AGENTS.md`](../AGENTS.md) *OCR import (拍照识词)* 与 *拍照批改* 两节的展开：取图/裁剪/编码、回复提取、服务商预设，以及手写答案的判定算法。用户可见的行为与规则以 AGENTS.md 为准。

两个功能共用**同一个 BYOK 视觉服务商**（**没有第五条出网链路**）与同一套取图入口（`ui/OcrImagePicker.kt`，首页「拍照识词」与结束页「拍照批改」共用）。

## 1. 拍照识词

### 1.1 取图与裁剪

入口是 Photo Picker（`ActivityResultContracts.PickVisualMedia`）或相机拍摄。**每次选图/拍摄都先过「选定识别区域」裁剪步骤**（`ui/OcrCropOverlay.kt`）：RN 前身靠 `allowsEditing` 拿到系统裁剪，Android 不保证有系统裁剪，所以 HearWrite 自己出一层 Compose 全屏遮罩。

- 原图先按 EXIF 旋转解码，再按 2 的幂采样到 ≤ `OCR_CROP_SOURCE_EDGE`（4096），保证小区域仍带够文字分辨率。
- 选择框可拖拽/缩放（边长下限 `OCR_CROP_MIN_SIDE_PX` = 96 源像素），**默认整幅**——不拖直接确认就是整页识别。
- 确认时裁出区域（`OcrService.cropToDataUrl`；归一化矩形覆盖整幅时直接透传原图），随后走标准编码。
- 裁剪 bitmap 生命周期由 ViewModel 持有（`cropBitmap` / `cropLoading`，session id + job 取消对付过期解码，确认/取消各 recycle 一次）。

### 1.2 编码

缩放到最长边 ≤ 1600 px，JPEG 质量 ≈ 0.82，base64 data URL。

### 1.3 请求与回复提取

- 请求：`POST {base}/chat/completions`，带图片内容。
- 回复先过**按语言分支的提取器** `OcrService.extractOcrLines`：英文模式只留 ASCII 单词行（音标/中文绝不变成条目；「单词 + 音标/词性/中文」的混合行取前导词头）；中文模式只留汉字串（拼音/拉丁字母/数字丢掉，`生字`/`词语表` 之类表头跳过）。
- 再剥掉 markdown 围栏，剩下的行走标准词表解析；结果为空时按语言给提示。
- **提示语是 `OcrService.kt` 里硬编码的中文原文**（英文词表版与生字/词语版；语言在扫描面板里选），有单测钉住。

### 1.4 服务商预设

`data/OcrProviderConfig.kt` 是**唯一真源**：

| 预设 | Base URL | 模型 |
|---|---|---|
| `zhipu` 智谱 GLM（**默认**） | `https://open.bigmodel.cn/api/paas/v4` | `glm-4v-flash`（免费） |
| `zen` OpenCode Zen | 见配置 | `mimo-v2.5-free` |
| `vercel` Vercel | 见配置 | `xiaomi/mimo-v2.5` |
| `commandcode` Command Code | 见配置 | `xiaomi/mimo-v2.5` |
| `openrouter` OpenRouter | 见配置 | `qwen/qwen3.8-flash` |
| `custom` 自定义 | 用户填写 | 用户填写 |

RN 前身的 openai / qwen / moonshot / siliconflow / ollama 预设按作者决定删除，端点仍可经「自定义」接入。

**只支持 BYOK**：用户自己在设置里填 key，没有内置 key、没有积分、没有额度记账（付费那套整体排除）。OCR 入口处展示免责声明 `"AI 识图可能存在误差，请核对识别结果"`；错误以中文消息 + 重试呈现。

## 2. 拍照批改（Roadmap #11）

这一条只把「刚结束的听写」与「学生手写的答案纸」对起来判：同一服务商、同一取图与裁剪流程，然后调 `OcrService.recognizeAnswers`。

### 2.1 入口与语言

结束页的「拍照批改」按钮 → 批改面板取代分数卡 → 相机/相册 → 选定识别区域。语言取**本场听写自己的**语言（`domain/isCjkRun`：可朗读词头里严格多数是中文），**不提供语言选择器**。

### 2.2 提示语是词表提示语的逆命题

`ENGLISH_ANSWER_PROMPT` / `CHINESE_ANSWER_PROMPT`（`OcrService.kt`，由 `answerPrompt(lang)` 选）要求模型抄出学生**自己的书写**——**拼写错误也要照抄（不要自动纠正）**——并带上**题号**（`1. apple`），空行跳过。理由：

- 悄悄纠正的拼写会被判成正确，错误就丢了。
- 丢掉题号会让「跳过一个词」之后的每个答案整体错位。

因此 `extractAnswerLines` 保留每一行**原样**（只剥围栏），**不做词头挽救、不做语言过滤**——与 `extractOcrLines` 正相反。

### 2.3 判定（`domain/AnswerGrading.kt`）

纯函数、无 Android 依赖、有单测。

- `normalizeAnswer` 折叠大小写、空白、字符宽度、标点与撇号变体（连字符/撇号是拼写的一部分，不是排版）。
- 期望行提供它的词头，另外补上 `you're = you are` 的右侧；答案侧的键集合在**含汉字时**追加其纯汉字形式（汉字听写的答案里合法地不会出现拉丁字母，所以 `月(yuè)` 这种被回抄出来的拼音注记不能判错它）——**反向不成立**。
- 落位顺序：**先按题号**（本场中一个可信且唯一的槽位——跳过词因此判成「漏答」，而不是把尾部整体挪位）→ 余下部分做 Needleman–Wunsch 对齐（错配两两配对，于是错答报告在它对应的词上，而不是「缺一个 + 多一个」）→ 再跑一遍恢复：把完全匹配塞进仍为空的槽位并标「顺序不符」。
- 判定结果：正确 / 错词 / 漏答 / 多余作答；`doubt` 标记近似（Levenshtein ≤ len/4，钳到 1–3）、空白行或野行——这些要人来看。
- `GradeResult.mismatched` 在整张纸与本场几乎不重叠时告警（拍到了别的页）。

### 2.4 只有确认后才进错词本

`DictationViewModel.confirmGrade` 是**唯一的照片→错词本通路**：面板逐行列出判定结果与学生所写，勾选默认 = 错词 + 漏答，人留下的勾才按词头写一次，`sourceLabel` 用本场的标签（「复习错词」轮次不写，与手动标记一致）。

记分卡、错词本 chips、复习错词与听写统计都读同一本书，所以一场「拍照批改过的听写」与「手工标记过的听写」行为完全一致。
