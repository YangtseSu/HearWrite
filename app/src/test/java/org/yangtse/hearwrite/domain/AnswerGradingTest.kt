package org.yangtse.hearwrite.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拍照批改 (Roadmap #11): the machine's per-answer proposal — normalization,
 * 题号 placement, sequence alignment and the recovery of answers written out
 * of order. Verdicts are a proposal only; the UI confirms them before they
 * reach the 错词本, so what these tests pin is what the parent sees.
 */
class AnswerGradingTest {

    private fun lines(vararg values: String) = values.toList()

    private fun GradeResult.bySlot() = items.filter { it.verdict != AnswerVerdict.EXTRA }
        .associateBy { it.slot }

    @Test
    fun perfectSheet_allCorrect() {
        val result = gradeAnswers(
            lines("apple", "banana", "cat"),
            lines("apple", "banana", "cat"),
        )
        assertEquals(3, result.expectedTotal)
        assertEquals(3, result.correctCount)
        assertEquals(0, result.wrongCount)
        assertEquals(0, result.missingCount)
        assertEquals(0, result.extraCount)
        assertEquals(0, result.doubtCount)
        assertFalse(result.mismatched)
        assertEquals(setOf<Int>(), result.defaultSelection())
    }

    @Test
    fun formatting_isFoldedIntoTheAnswer() {
        // Case, edge punctuation, spacing and fullwidth letters are not
        // spelling errors — OCR and handwriting produce all of them.
        val result = gradeAnswers(
            lines("apple | n. | 苹果", "banana"),
            lines("APPLE.", "  ba na na  "),
        )
        assertEquals(2, result.correctCount)
        val fullwidth = gradeAnswers(lines("banana"), lines("ＢＡＮＡＮＡ"))
        assertEquals(1, fullwidth.correctCount)
    }

    @Test
    fun apostropheVariants_areOneSpelling() {
        val result = gradeAnswers(
            lines("don't", "you're = you are"),
            lines("don’t", "you are"),
        )
        assertEquals(2, result.correctCount)
    }

    @Test
    fun expansionLine_acceptsBothSides() {
        assertEquals(1, gradeAnswers(lines("you're = you are"), lines("you're")).correctCount)
        assertEquals(1, gradeAnswers(lines("you're = you are"), lines("you are")).correctCount)
        // …but not a different spelling of it.
        assertEquals(1, gradeAnswers(lines("you're = you are"), lines("youre")).wrongCount)
    }

    @Test
    fun numberedSheet_blankRowIsMissingNotAShift() {
        // 1. 3. 4. on the paper: slot 2 was left blank, everything else keeps
        // its own number. Without 题号 handling every answer after the blank
        // would be graded against the wrong word.
        val result = gradeAnswers(
            lines("apple", "banana", "cat", "dog"),
            lines("1. apple", "3. cat", "4. dog"),
        )
        assertEquals(3, result.correctCount)
        assertEquals(AnswerVerdict.MISSING, result.bySlot()[2]!!.verdict)
        assertEquals(0, result.extraCount)
    }

    @Test
    fun numberedBlankWithoutText_isMissing() {
        val result = gradeAnswers(lines("apple", "banana", "cat"), lines("1.apple", "2.", "3.cat"))
        assertEquals(AnswerVerdict.MISSING, result.bySlot()[2]!!.verdict)
        assertEquals(2, result.correctCount)
    }

    @Test
    fun numberedOutOfOrder_placedByNumber() {
        val result = gradeAnswers(
            lines("apple", "banana", "cat"),
            lines("3、cat", "（1）apple", "2） banana"),
        )
        assertEquals(3, result.correctCount)
        assertEquals(0, result.extraCount)
        assertEquals(0, result.doubtCount)
    }

    @Test
    fun implausibleOrDuplicateNumbers_fallBackToSequence() {
        // 7 is not a slot of this run and "1" repeats: the numbers are noise,
        // the answers still align in the order they were written.
        val result = gradeAnswers(lines("apple", "banana"), lines("7. apple", "banana"))
        assertEquals(2, result.correctCount)
        assertEquals(0, result.missingCount)

        val duplicate = gradeAnswers(lines("apple", "banana"), lines("1. apple", "1. banana"))
        assertEquals(2, duplicate.correctCount)
    }

    @Test
    fun answersWrittenInOrderButShifted_recoveredAsOutOfOrder() {
        // The student answered every word, in a different order: nothing is
        // missing and nothing is stray — the recovery pass re-assigns them.
        val result = gradeAnswers(
            lines("apple", "banana", "cat"),
            lines("cat", "apple", "banana"),
        )
        assertEquals(3, result.correctCount)
        assertEquals(0, result.missingCount)
        assertEquals(0, result.extraCount)
        assertTrue(result.items.single { it.slot == 3 }.outOfOrder)
        assertFalse(result.items.single { it.slot == 1 }.outOfOrder)
    }

    @Test
    fun misspelling_isWrongButFlagged() {
        // "aple" is off by one — a spelling error, or the model misreading
        // the paper. Doubt asks the human to look before it is booked.
        val result = gradeAnswers(lines("apple"), lines("aple"))
        val item = result.items.single()
        assertEquals(AnswerVerdict.WRONG, item.verdict)
        assertTrue(item.doubt)
        assertEquals("aple", item.answer)
        assertEquals("apple", item.expected)
        assertEquals(setOf(0), result.defaultSelection())
    }

    @Test
    fun greedySheet_isWrongWithoutDoubtAndWarnsAboutMismatch() {
        val result = gradeAnswers(
            lines("apple", "banana", "cat", "dog"),
            lines("zzz", "yyy", "xxx", "www"),
        )
        assertEquals(4, result.wrongCount)
        assertEquals(0, result.doubtCount)
        assertTrue(result.mismatched)
    }

    @Test
    fun oneStrayLine_isExtraAndNeverBooked() {
        val result = gradeAnswers(lines("apple", "banana"), lines("apple", "banana", "姓名"))
        assertEquals(2, result.correctCount)
        val extra = result.items.single { it.verdict == AnswerVerdict.EXTRA }
        assertEquals("姓名", extra.answer)
        assertEquals(null, extra.expected)
        assertEquals(0, extra.slot)
        assertTrue(extra.doubt)
        assertEquals(setOf<Int>(), result.defaultSelection())
    }

    @Test
    fun duplicateWords_stayPositional() {
        val result = gradeAnswers(lines("the", "the"), lines("the", "the"))
        assertEquals(2, result.correctCount)
        assertEquals(2, result.items.size)
    }

    @Test
    fun blankSheet_everySlotMissing() {
        val result = gradeAnswers(lines("apple", "banana", "cat"), emptyList())
        assertEquals(3, result.missingCount)
        assertEquals(3, result.expectedTotal)
        assertEquals(3, result.doubtCount)
        assertFalse(result.mismatched)
        assertEquals(setOf(0, 1, 2), result.defaultSelection())
    }

    @Test
    fun cjkSheet_punctuationAndSpacingFolded() {
        val result = gradeAnswers(
            lines("月 | yuè | 月亮", "香蕉"),
            lines("1、 月。", "（2）香 蕉"),
        )
        assertEquals(2, result.correctCount)
        assertEquals(0, result.doubtCount)
    }

    @Test
    fun cjkAnswer_withPinyinAnnotation_isStillCorrect() {
        // The model echoed the textbook's pinyin next to the 汉字. A 汉字
        // answer cannot legitimately contain Latin, so the annotation is
        // dropped instead of turning a correct answer into a wrong one.
        val result = gradeAnswers(lines("月 | yuè | 月亮", "香蕉"), lines("月(yuè)", "香蕉 xiāngjiāo"))
        assertEquals(2, result.correctCount)
        assertEquals(0, result.doubtCount)
        // …but the reverse is not done: a stray 汉字 must not pass an English
        // answer.
        val english = gradeAnswers(lines("apple"), lines("apple 苹果")).items.single()
        assertEquals(AnswerVerdict.WRONG, english.verdict)
    }

    @Test
    fun wrongChar_isNearMissButPinyinIsNot() {
        // 日 for 月 is one character off — doubtful. A pinyin answer is a
        // different kind of mistake and graded confidently.
        val near = gradeAnswers(lines("月"), lines("日")).items.single()
        assertEquals(AnswerVerdict.WRONG, near.verdict)
        assertTrue(near.doubt)

        val pinyin = gradeAnswers(lines("月"), lines("yue")).items.single()
        assertEquals(AnswerVerdict.WRONG, pinyin.verdict)
        assertFalse(pinyin.doubt)
    }

    @Test
    fun headwordKey_isTheBookKey_notTheAnswer() {
        // 错词本 keys on the dictated word, not on what the student wrote.
        val result = gradeAnswers(lines("apple | n. | 苹果"), lines("aple"))
        assertEquals("apple", result.items.single().expected)
    }

    @Test
    fun isCjkRun_majorityOfSpeakableHeadwords() {
        assertTrue(isCjkRun(lines("月 | yuè | 月亮", "香蕉")))
        assertTrue(isCjkRun(lines("月", "香蕉", "apple")))
        assertFalse(isCjkRun(lines("apple", "banana")))
        assertFalse(isCjkRun(lines("apple", "月")))
        assertFalse(isCjkRun(lines("", "  ")))
    }

    @Test
    fun editDistance_andNearMissThresholds() {
        assertEquals(0, editDistance("apple", "apple"))
        assertEquals(1, editDistance("apple", "aple"))
        assertEquals(1, editDistance("beautiful", "beatiful"))
        assertEquals(2, editDistance("apple", "ape"))
        assertEquals(3, editDistance("cat", ""))

        // Threshold grows with length: 1 for short answers, 2 from 8 chars.
        assertTrue(isNearMiss("cat", "cut"))
        assertTrue(isNearMiss("beautiful", "beautifvl"))
        assertFalse(isNearMiss("cat", "dog"))
        assertFalse(isNearMiss("apple", "zzz"))
        assertFalse(isNearMiss("apple", "apple"))
    }

    @Test
    fun emptyRun_isEmptyResult() {
        val result = gradeAnswers(emptyList(), lines("apple"))
        assertEquals(0, result.expectedTotal)
        assertTrue(result.items.isEmpty())
        assertFalse(result.mismatched)
    }

    @Test
    fun stripAnswerNumber_shapesRecognized() {
        assertEquals(1 to "apple", stripAnswerNumber("1. apple"))
        assertEquals(2 to "月", stripAnswerNumber("2、月"))
        assertEquals(3 to "月", stripAnswerNumber("（3）月"))
        assertEquals(4 to "apple", stripAnswerNumber("(4) apple"))
        assertEquals(5 to "月", stripAnswerNumber(" 5 月"))
        assertEquals(null to "apple", stripAnswerNumber("apple"))
        assertEquals(null to "12", stripAnswerNumber("12"))
    }
}
