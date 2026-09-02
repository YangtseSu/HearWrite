package org.yangtse.hearwrite.domain

import java.text.Collator
import java.util.Locale

/**
 * Ordering rules for built-in library list labels and categories, ported from
 * `alice/scripts/generate-library.ts` (behavioral ground truth): grade rank
 * 一→九 by school year (not pinyin), term 上/下/全, numeric-aware compare for
 * "Unit N" / "Module N" / 识字序号, 第X册 by volume number (一、二、三 not pinyin),
 * letter lists (A, B, …) by natural order.
 *
 * CJK text compares by pinyin syllable → stroke count → code point, mirroring
 * ICU "zh-Hans" collation. The JDK `Collator` disagrees with ICU on 同音字
 * (it orders 仁 before 人, ICU orders 人 before 仁), so CJK runs use the table
 * below and the JDK collator is only used for Latin runs.
 */

private val GRADE_RANK = mapOf(
    "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
    "六" to 6, "七" to 7, "八" to 8, "九" to 9,
)

private val TERM_RANK = mapOf("上" to 0, "下" to 1, "全" to 2)

/** 第X册 where X may be 一…九 or 十/二十… compounds. */
private val VOL_PREFIX_RE = Regex("""^第([一二三四五六七八九十]+)册""")
private val TENS_RE = Regex("""^([一二三四五六七八九]?)十([一二三四五六七八九]?)$""")

/** 中文数字（一~九、十、X十Y）→ value; unmapped input yields 99 (sorts last). */
private fun ceNum(ce: String): Int {
    GRADE_RANK[ce]?.let { return it }
    val m = TENS_RE.matchEntire(ce)
    if (m != null) {
        val tens = if (m.groupValues[1].isEmpty()) 1 else GRADE_RANK.getValue(m.groupValues[1])
        val unit = m.groupValues[2].ifEmpty { null }?.let(GRADE_RANK::get) ?: 0
        return tens * 10 + unit
    }
    return 99
}

/**
 * Pinyin (tone-unmarked, ü written u) and stroke counts for every CJK char that
 * occurs in the built-in library names. Kept as an explicit closed set: adding
 * a list with a new char means extending this table (the golden-order unit
 * test over data/ will flag any ordering drift).
 */
private val HANZI = mapOf(
    // 一…九、十 (rank chars; harmless to include for prefix runs)
    '一' to ("yi" to 1), '二' to ("er" to 2), '三' to ("san" to 3), '四' to ("si" to 5),
    '五' to ("wu" to 4), '六' to ("liu" to 4), '七' to ("qi" to 2), '八' to ("ba" to 2),
    '九' to ("jiu" to 2), '十' to ("shi" to 2),
    '上' to ("shang" to 3), '下' to ("xia" to 3), '全' to ("quan" to 6),
    '中' to ("zhong" to 4), '第' to ("di" to 11),
    // categories and list-type words
    '人' to ("ren" to 2), '仁' to ("ren" to 4), '初' to ("chu" to 7), '高' to ("gao" to 10),
    '闽' to ("min" to 9), '研' to ("yan" to 9), '考' to ("kao" to 6), '外' to ("wai" to 5),
    '爱' to ("ai" to 10), '版' to ("ban" to 8), '教' to ("jiao" to 11), '小' to ("xiao" to 3),
    '学' to ("xue" to 8), '文' to ("wen" to 4), '生' to ("sheng" to 5), '僻' to ("pi" to 15),
    '常' to ("chang" to 11), '册' to ("ce" to 5), '园' to ("yuan" to 7), '地' to ("di" to 6),
    '字' to ("zi" to 6), '表' to ("biao" to 8), '见' to ("jian" to 4), '识' to ("shi" to 7),
    '词' to ("ci" to 7), '语' to ("yu" to 9), '读' to ("du" to 10), '写' to ("xie" to 5),
    '阅' to ("yue" to 10),
)

private fun hanziKey(c: Char): Pair<String, Int>? = HANZI[c]

/** Case/accent-folding Latin collation — mirrors `sensitivity: "base"` for ASCII runs. */
private val latinCollator: Collator =
    Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }

/**
 * Compare two text runs CJK-aware: single Han chars compare by
 * (pinyin, stroke count, code point); non-Han runs (Latin, spaces) by ASCII
 * collation. Spaces are ignored, matching ICU zh collation where they are
 * weaker than primary level. A shorter run sorts first when it is a prefix.
 */
private fun zhTextCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length || j < b.length) {
        // Skip spaces on both sides (weak in zh collation).
        while (i < a.length && a[i] == ' ') i++
        while (j < b.length && b[j] == ' ') j++
        val aEnd = i >= a.length
        val bEnd = j >= b.length
        if (aEnd || bEnd) return if (aEnd == bEnd) 0 else if (aEnd) -1 else 1

        val aHan = a[i] in HANZI
        val bHan = b[j] in HANZI
        if (aHan && bHan) {
            val ak = hanziKey(a[i])!!
            val bk = hanziKey(b[j])!!
            val cp = ak.first.compareTo(bk.first)
            if (cp != 0) return cp
            val cs = ak.second.compareTo(bk.second)
            if (cs != 0) return cs
            val cc = a[i].compareTo(b[j])
            if (cc != 0) return cc
            i++
            j++
        } else if (aHan != bHan) {
            // Latin runs sort before Han chars in zh collation. Unreachable for
            // real data (list names keep one writing system per position).
            return if (aHan) 1 else -1
        } else {
            // Both sides on non-Han runs: consume until a known Han char on both
            // sides and compare the runs with the Latin collator.
            val aNext = nextHanIndex(a, i)
            val bNext = nextHanIndex(b, j)
            val c = latinCollator.compare(a.substring(i, aNext), b.substring(j, bNext))
            if (c != 0) return c
            i = aNext
            j = bNext
        }
    }
    return 0
}

private fun nextHanIndex(s: String, start: Int): Int {
    var k = start
    while (k < s.length && (s[k] !in HANZI || s[k] == ' ')) {
        if (s[k] == ' ') {
            k++ // spaces handled inside the caller's loop for the run path too
        } else {
            k++
        }
    }
    return k
}

/**
 * Numeric-aware compare (ascending) of the natural-comparison kind used by the
 * upstream `localeCompare(…, { numeric: true })` — text runs compare via zh
 * collation, digit runs compare by value ("识字 2" < "识字 10").
 */
fun naturalCompare(a: String, b: String): Int {
    var ai = 0
    var bi = 0
    while (ai < a.length || bi < b.length) {
        val aDigit = ai < a.length && a[ai].isDigit()
        val bDigit = bi < b.length && b[bi].isDigit()
        if (aDigit && bDigit) {
            // Compare digit runs by numeric value (leading zeros stripped; longer
            // zero-free run wins when value ties). Real data has no leading zeros.
            val aEnd = digitRunEnd(a, ai)
            val bEnd = digitRunEnd(b, bi)
            val za = a.substring(ai, aEnd).trimStart('0')
            val zb = b.substring(bi, bEnd).trimStart('0')
            val c = when {
                za.length != zb.length -> za.length.compareTo(zb.length)
                else -> za.compareTo(zb)
            }
            if (c != 0) return c
            ai = aEnd
            bi = bEnd
        } else if (aDigit != bDigit) {
            // Digit run vs. text run — unreachable for the real data shapes
            // (a run kind mismatch would require names like "A1" vs "AB");
            // compare the remaining tails textually as a sane fallback.
            return zhTextCompare(a.substring(ai), b.substring(bi))
        } else {
            val aEnd = textRunEnd(a, ai)
            val bEnd = textRunEnd(b, bi)
            val c = zhTextCompare(a.substring(ai, aEnd), b.substring(bi, bEnd))
            if (c != 0) return c
            ai = aEnd
            bi = bEnd
        }
    }
    return 0
}

/** End index of the digit run starting at [start] (caller guarantees a digit there). */
private fun digitRunEnd(s: String, start: Int): Int {
    var i = start
    while (i < s.length && s[i].isDigit()) i++
    return i
}

/** End index of the non-digit run starting at [start]. */
private fun textRunEnd(s: String, start: Int): Int {
    var i = start
    while (i < s.length && !s[i].isDigit()) i++
    return i
}

/**
 * Order built-in library labels / category names as the upstream library
 * generator does: 第X册 by volume, then grade → term; otherwise natural zh
 * compare. Returns a negative/zero/positive comparator result.
 */
fun compareLabels(a: String, b: String): Int {
    val va = VOL_PREFIX_RE.matchAt(a, 0)
    val vb = VOL_PREFIX_RE.matchAt(b, 0)
    if (va != null && vb != null) {
        val ra = ceNum(va.groupValues[1])
        val rb = ceNum(vb.groupValues[1])
        if (ra != rb) return ra.compareTo(rb)
        return naturalCompare(a.substring(va.value.length), b.substring(vb.value.length))
    }
    val gradeRe = Regex("""^([一二三四五六七八九])([上下全])""")
    val ma = gradeRe.matchAt(a, 0)
    val mb = gradeRe.matchAt(b, 0)
    if (ma != null && mb != null) {
        val ra = GRADE_RANK[ma.groupValues[1]] ?: 99
        val rb = GRADE_RANK[mb.groupValues[1]] ?: 99
        if (ra != rb) return ra.compareTo(rb)
        val ta = TERM_RANK[ma.groupValues[2]] ?: 9
        val tb = TERM_RANK[mb.groupValues[2]] ?: 9
        if (ta != tb) return ta.compareTo(tb)
    }
    return naturalCompare(a, b)
}

/**
 * Built-in library entry id (`default_<category>_<label>`), the stable storage
 * key persisted by favorites/wrong-words history (AGENTS.md "Persistence").
 * Labels are stable — renaming a data file orphans stored ids.
 */
fun builtinListId(category: String, label: String): String = "default_${category}_$label"
