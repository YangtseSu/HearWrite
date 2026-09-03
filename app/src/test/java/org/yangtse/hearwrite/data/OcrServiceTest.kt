package org.yangtse.hearwrite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure OCR helpers: verbatim upstream prompts (AGENTS.md: keep verbatim from
 * `alice/src/lib/ocr.ts`, sentences joined with no separator), reply
 * content/error extraction, and the language-specific OCR extractors
 * (English keeps ASCII word lines only — 音标/Chinese never survive; Chinese
 * keeps 汉字 runs only — pinyin/English/digits dropped).
 */
class OcrServiceTest {

    /** The exact upstream ENGLISH_OCR_PROMPT (join("") — no separator). */
    private val expectedEnglishPrompt = listOf(
        "这是一张包含英文单词列表的图片。",
        "请识别图中所有英文单词或词组。",
        "如果单词旁边标注了词性和中文释义，请一并提取，每行格式：单词 | 词性 | 中文释义",
        "如果图中没有词性或释义信息，只输出单词本身。",
        "像 actor / actress 这样的斜杠词组应作为一整行输出，不要拆开。",
        "不要用逗号连接、不要编号、不要输出其他标点或解释。",
    ).joinToString("")

    /** The exact upstream CHINESE_OCR_PROMPT (join("") — no separator). */
    private val expectedChinesePrompt = listOf(
        "这是一张语文课本或练习册的图片，里面有需要听写的汉字生字和词语。",
        "请识别图中所有汉字生字和词语，每行输出一个：",
        "单个生字只输出该汉字；词语输出完整词语，不要拆成单个汉字。",
        "忽略拼音、英文单词、数字、页码、题号、笔顺示意图和装饰图案。",
        "如果字词旁边标注了拼音或组词，只输出字词本身。",
        "不要输出“生字”“词语”等栏目标题、序号、标点符号或任何解释。",
        "不要把多个字词合并到一行。",
    ).joinToString("")

    @Test
    fun prompts_areVerbatimUpstream_copy() {
        assertEquals(expectedEnglishPrompt, OcrLang.ENGLISH.prompt)
        assertEquals(expectedChinesePrompt, OcrLang.CHINESE.prompt)
        // Single concatenated string — upstream joins the sentences with "".
        assertTrue(expectedEnglishPrompt.length > 100)
        assertTrue(expectedChinesePrompt.length > 150)
    }

    @Test
    fun normalizeOcrLines_plainLines_trimmedNonEmpty() {
        assertEquals(
            listOf("apple", "banana"),
            normalizeOcrLines(" apple \n\n banana "),
        )
    }

    @Test
    fun normalizeOcrLines_crlfAndCr_normalized() {
        assertEquals(listOf("apple", "banana"), normalizeOcrLines("apple\r\nbanana\r"))
    }

    @Test
    fun normalizeOcrLines_fencedReply_contentSurvives() {
        assertEquals(
            listOf("apple", "banana"),
            normalizeOcrLines("```\napple\nbanana\n```"),
        )
        assertEquals(
            listOf("apple"),
            normalizeOcrLines("```text\napple\n```\n"),
        )
    }

    @Test
    fun extractEnglishOcrLines_enrichedAndBareLines_keptInOrder() {
        assertEquals(
            listOf("apple | n. | 苹果", "banana", "car | adj. | 汽车"),
            extractEnglishOcrLines("apple | n. | 苹果\nbanana\ncar | adj. | 汽车\n"),
        )
    }

    @Test
    fun extractEnglishOcrLines_mixedRows_headwordSalvaged() {
        // Model echoed textbook rows (word + 音标 + pos + 中文) instead of
        // the pipe format: the leading English phrase survives, the
        // annotations are noise.
        assertEquals(
            listOf("apple", "banana"),
            extractEnglishOcrLines("apple /ˈæpl/ n. 苹果\nbanana"),
        )
        assertEquals(
            listOf("actor", "apple"),
            extractEnglishOcrLines("actor /ˈæktə(r)/ n. 演员\napple /ˈæpl/ 苹果"),
        )
        // Multi-word headword stays one phrase, 音标 run after it dropped.
        assertEquals(
            listOf("look forward to"),
            extractEnglishOcrLines("look forward to /lʊk ˈfɔːwəd tuː/ v. 盼望"),
        )
    }

    @Test
    fun extractEnglishOcrLines_rowNotStartingWithEnglish_dropped() {
        // Chinese (or pure 音标) leading a row means it is no English entry.
        assertEquals(emptyList<String>(), extractEnglishOcrLines("苹果 apple"))
        assertEquals(
            listOf("banana"),
            extractEnglishOcrLines("演员 actor\nbanana"),
        )
    }

    @Test
    fun extractEnglishOcrLines_commaDump_splitIntoCandidates() {
        assertEquals(
            listOf("apple", "banana", "orange", "pear"),
            extractEnglishOcrLines("apple, banana; orange、pear"),
        )
    }

    @Test
    fun extractEnglishOcrLines_phraseAndLongRun_handled() {
        // ≤ MAX_PHRASE_TOKENS stays whole (phrases like "ice cream" survive).
        assertEquals(listOf("ice cream"), extractEnglishOcrLines("ice cream"))
        // A longer run is a failed dump → flattened into single tokens.
        assertEquals(
            listOf("apple", "banana", "orange", "pear", "grape"),
            extractEnglishOcrLines("apple banana orange pear grape"),
        )
    }

    @Test
    fun extractEnglishOcrLines_markersQuotesPunctuation_cleaned() {
        assertEquals(
            listOf("apple", "banana", "orange"),
            extractEnglishOcrLines("1. apple\n- banana\n\"orange\""),
        )
    }

    @Test
    fun extractEnglishOcrLines_dedupeCaseInsensitive_firstWins() {
        assertEquals(
            listOf("Apple", "banana"),
            extractEnglishOcrLines("Apple\napple\nbanana"),
        )
        // Enriched and bare duplicates share the same seen set.
        assertEquals(
            listOf("Apple | n. | 苹果"),
            extractEnglishOcrLines("Apple | n. | 苹果\napple"),
        )
    }

    @Test
    fun extractEnglishOcrLines_phoneticTrailingHeadwordWithMeta_salvaged() {
        // 音标 run after the headword in the word column is dropped; meta
        // columns are preserved.
        assertEquals(
            listOf("apple | n. | 苹果", "pear"),
            extractEnglishOcrLines("apple /ˈæpl/ | n. | 苹果\npear"),
        )
    }

    @Test
    fun extractEnglishOcrLines_fullwidthPipeLine_degradesToBareWord() {
        // Alice splits on the ASCII pipe only; a fullwidth-pipe line goes
        // through the plain path, tokens flatten, meta fails WORD_RE.
        assertEquals(listOf("apple"), extractEnglishOcrLines("apple ｜ n. ｜ 苹果"))
    }

    @Test
    fun extractEnglishOcrLines_empty_isEmpty() {
        assertEquals(emptyList<String>(), extractEnglishOcrLines(""))
        assertEquals(emptyList<String>(), extractEnglishOcrLines("   \n\n "))
        assertEquals(emptyList<String>(), extractEnglishOcrLines("```\n```"))
    }

    @Test
    fun extractChineseOcrLines_enrichedLine_headwordOnly() {
        // Later pipe columns are annotations (pinyin 组词), not entries.
        assertEquals(
            listOf("月", "学校"),
            extractChineseOcrLines("月 | yuè | 月亮\n学校 | xuéxiào"),
        )
    }

    @Test
    fun extractChineseOcrLines_pinyinAndLatin_stripped() {
        assertEquals(
            listOf("月亮", "月", "学校"),
            extractChineseOcrLines("月亮 yuèliang\n月(yuè)\napple 123 学校"),
        )
    }

    @Test
    fun extractChineseOcrLines_multiWordLine_splitIntoRuns() {
        assertEquals(
            listOf("月", "月亮", "明月"),
            extractChineseOcrLines("月、月亮 明月"),
        )
    }

    @Test
    fun extractChineseOcrLines_stopwordRuns_skipped() {
        assertEquals(
            listOf("月", "月亮", "太阳"),
            extractChineseOcrLines("生字：月\n词语：月亮\n读一读：太阳"),
        )
    }

    @Test
    fun extractChineseOcrLines_labelSegment_skipped() {
        assertEquals(
            listOf("月亮", "星星"),
            extractChineseOcrLines("词语 | 月亮\n生字表 | 星星"),
        )
    }

    @Test
    fun extractChineseOcrLines_fullwidthPipes_parsed() {
        assertEquals(
            listOf("月"),
            extractChineseOcrLines("月｜yuè｜月亮"),
        )
    }

    @Test
    fun extractChineseOcrLines_dedupe_firstWins() {
        assertEquals(
            listOf("月", "月亮"),
            extractChineseOcrLines("月\n月\n月亮"),
        )
    }

    @Test
    fun extractChineseOcrLines_empty_isEmpty() {
        assertEquals(emptyList<String>(), extractChineseOcrLines(""))
        assertEquals(emptyList<String>(), extractChineseOcrLines("yuè pinyin only"))
        assertEquals(emptyList<String>(), extractChineseOcrLines("拼音 | yuè"))
    }

    @Test
    fun extractOcrLines_routesByLanguage() {
        // Same mixed reply: English mode keeps ASCII words only — the 音标/
        // Chinese trailing a word is dropped and the headword survives;
        // Chinese mode keeps only 汉字 runs. The per-language expectation.
        val mixed = "apple /ˈæpl/ 苹果\n月 yuè\nbanana"
        assertEquals(listOf("apple", "banana"), extractOcrLines(mixed, OcrLang.ENGLISH))
        assertEquals(listOf("苹果", "月"), extractOcrLines(mixed, OcrLang.CHINESE))
    }

    @Test
    fun parseReplyContent_extractsChoicesContent() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"  apple \n banana  "}}]}"""
        assertEquals("apple \n banana", parseReplyContent(body))
    }

    @Test
    fun parseReplyContent_missingContent_isEmpty() {
        assertEquals("", parseReplyContent("""{"choices":[{"message":{"role":"assistant"}}]}"""))
        assertEquals("", parseReplyContent("""{"choices":[]}"""))
        assertEquals("", parseReplyContent("""{"error":{"message":"boom"}}"""))
        assertEquals("", parseReplyContent("not json"))
        assertEquals("", parseReplyContent(""))
    }

    @Test
    fun errorDetail_extractsProviderMessage() {
        assertEquals("认证失败", errorDetail("""{"error":{"message":"认证失败","type":"auth"}}"""))
        assertEquals("", errorDetail("""{"error":{}}"""))
        assertEquals("", errorDetail("plain text error"))
        assertEquals("", errorDetail(""))
    }

    @Test
    fun emptyMessages_distinguishUnparsedFromEmpty() {
        // Model said nothing.
        assertEquals("未识别到英文单词，请换一张更清晰的图片再试", ocrEmptyMessage(OcrLang.ENGLISH, unparsed = false))
        assertEquals("未识别到中文生字或词语，请换一张更清晰的图片再试", ocrEmptyMessage(OcrLang.CHINESE, unparsed = false))
        // Model answered but nothing survived the language filter.
        assertEquals(
            "未能从识别结果中提取英文单词，请换一张更清晰的单词列表再试",
            ocrEmptyMessage(OcrLang.ENGLISH, unparsed = true),
        )
        assertEquals(
            "未能从识别结果中提取中文生字或词语，请换一张更清晰的生字/词语表再试",
            ocrEmptyMessage(OcrLang.CHINESE, unparsed = true),
        )
    }

    @Test
    fun extractEnglishOcrLines_spacedSlashPhrase_keptWhole() {
        assertEquals(
            listOf("actor / actress"),
            extractEnglishOcrLines("actor / actress"),
        )
        // Pipe-enriched rows keep the whole slash phrase too.
        assertEquals(
            listOf("actor / actress | n. | 演员"),
            extractEnglishOcrLines("actor / actress | n. | 演员"),
        )
        // Salvage still yields the full slash phrase off a row that trails
        // annotations, and plain rows after it stay independent.
        assertEquals(
            listOf("actor / actress", "apple"),
            extractEnglishOcrLines("actor / actress /əˈktə(r)/ n. 演员\napple"),
        )
    }
}
