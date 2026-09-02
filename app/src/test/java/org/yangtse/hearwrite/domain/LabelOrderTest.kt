package org.yangtse.hearwrite.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering contract for the built-in library (AGENTS.md "Built-in library"),
 * ported from `alice/scripts/generate-library.ts` compareLabels. The golden
 * fixture `library-label-order.json` is the upstream sort output over the real
 * `data/` tree (regenerate with the alice script when lists are added).
 */
class LabelOrderTest {

    private fun assertOrdered(vararg labels: String) {
        for (i in 0 until labels.size - 1) {
            val msg = "expected \"${labels[i]}\" < \"${labels[i + 1]}\""
            assertTrue(msg, compareLabels(labels[i], labels[i + 1]) < 0)
            assertTrue(msg, compareLabels(labels[i + 1], labels[i]) > 0)
        }
    }

    @Test
    fun `grade and term rank before text compare`() {
        assertOrdered("一上 Unit 1", "一下 Unit 1", "二上 Unit 1", "二下 Unit 1", "九下 Unit 1")
    }

    @Test
    fun `term rank is 上 lower 全`() {
        assertOrdered("九上 Unit 13", "九下 Unit 1", "九全 Unit 1")
    }

    @Test
    fun `volume labels sort by volume number not pinyin`() {
        assertOrdered("第一册 生僻", "第二册 常见", "第三册 常见", "第十册 A", "第十一册 A")
    }

    @Test
    fun `same volume sorts by natural text compare`() {
        assertOrdered("第一册 常见", "第一册 生僻")
    }

    @Test
    fun `numeric runs compare by value`() {
        assertOrdered("七上 Module 2", "七上 Module 10", "七上 Module 12")
        assertOrdered("二上 写字表 识字 2", "二上 写字表 识字 10", "二上 写字表 阅读 1")
        assertOrdered("八上 读读写写 2", "八上 读读写写 21")
    }

    @Test
    fun `starter units precede units`() {
        assertOrdered("七上 Starter Unit 1", "七上 Starter Unit 3", "七上 Unit 1")
    }

    @Test
    fun `letter labels sort naturally`() {
        assertOrdered("A", "B", "IJK", "L", "QR", "S", "UV", "XYZ")
    }

    @Test
    fun `chinese list types sort by pinyin within a grade term`() {
        // 词语表(cí) < 识字表(shí) < 写字表(xiě) — zh collation, same as upstream.
        assertOrdered("二上 词语表 识字 1", "二上 识字表 识字 1", "二上 写字表 识字 1")
    }

    @Test
    fun `grade labels beat bare labels of the same school-year head`() {
        assertOrdered("八上 Unit 1", "八上 Unit 2")
    }

    @Test
    fun `full library order matches upstream generator golden`() {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("library-label-order.json"))
        val root = Json.parseToJsonElement(stream.readBytes().decodeToString()).jsonObject

        // Per-category first: sorted labels must equal the upstream generator order.
        for ((category, expectedJson) in root.getValue("perCategory").jsonObject) {
            val expected = (expectedJson as JsonArray).map { it.jsonPrimitive.content }
            val actual = expected.sortedWith(::compareLabels)
            val firstDiff = expected.zip(actual).indexOfFirst { (e, a) -> e != a }
            assertEquals("order within $category (first diff at $firstDiff)", expected, actual)
            // Sanity: every category is ordered strictly (no duplicates / unstable keys).
            for (i in 0 until expected.size - 1) {
                assertTrue(compareLabels(expected[i], expected[i + 1]) < 0)
            }
        }
        // Categories: compareLabels over the directory names (excluding data/ non-library dirs).
        val categories = root.getValue("categoryOrder").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(categories, categories.sortedWith(::compareLabels))
    }

    @Test
    fun `builtin list ids embed category and label verbatim`() {
        assertEquals("default_人教版小学语文_二上 写字表 识字 1", builtinListId("人教版小学语文", "二上 写字表 识字 1"))
    }

    @Test
    fun `empty and equal labels compare equal`() {
        assertEquals(0, compareLabels("二上 识字表 识字 1", "二上 识字表 识字 1"))
        assertEquals(0, compareLabels("", ""))
    }
}
