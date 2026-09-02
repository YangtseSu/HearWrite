package org.yangtse.hearwrite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure OCR helpers: verbatim upstream prompts (AGENTS.md: keep verbatim from
 * `alice/src/lib/ocr.ts`, sentences joined with no separator), fence
 * stripping, reply content/error extraction and the standard word-line
 * parser round-trip.
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
    fun stripOuterFence_plainContent_isUntouched() {
        assertEquals("apple\nbanana", stripOuterFence("apple\nbanana"))
    }

    @Test
    fun stripOuterFence_wrappedFence_isRemoved() {
        assertEquals(
            "apple\nbanana",
            stripOuterFence("```\napple\nbanana\n```"),
        )
    }

    @Test
    fun stripOuterFence_languageTaggedFence_isRemoved() {
        assertEquals(
            "apple | n. | 苹果",
            stripOuterFence("```text\napple | n. | 苹果\n```\n"),
        )
    }

    @Test
    fun stripOuterFence_bareFenceNoBody_unchanged() {
        assertEquals("```", stripOuterFence("```"))
    }

    @Test
    fun stripOuterFence_whitespaceTrimmed() {
        assertEquals("a\nb", stripOuterFence("  \na\nb\n  "))
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
    fun ocrReplyLines_enrichedLines_roundTripThroughParser() {
        val content = "apple | n. | 苹果\nbanana\ncar | adj. | 汽车\n"
        assertEquals(
            "apple | n. | 苹果\nbanana\ncar | adj. | 汽车",
            ocrReplyLines(content),
        )
    }

    @Test
    fun ocrReplyLines_fencedReply_parsed() {
        assertEquals(
            "月\n学校",
            ocrReplyLines("```\n月\n学校\n```"),
        )
    }

    @Test
    fun ocrReplyLines_fullwidthPipes_parsed() {
        assertEquals(
            "apple | n. | 苹果",
            ocrReplyLines("apple ｜ n. ｜ 苹果"),
        )
    }

    @Test
    fun ocrReplyLines_empty_isEmpty() {
        assertEquals("", ocrReplyLines(""))
        assertEquals("", ocrReplyLines("   \n\n "))
        assertEquals("", ocrReplyLines("```\n```"))
    }

    @Test
    fun emptyMessages_areChinesePerLang() {
        assertTrue(ocrEmptyMessage(OcrLang.ENGLISH).contains("英文单词"))
        assertTrue(ocrEmptyMessage(OcrLang.CHINESE).contains("生字"))
    }
}
