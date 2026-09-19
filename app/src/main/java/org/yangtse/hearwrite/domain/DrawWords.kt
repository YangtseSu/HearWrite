package org.yangtse.hearwrite.domain

import kotlin.random.Random

/**
 * 多选词表 → 抽词听写 (Roadmap #9) pool logic — pure, so the draw contract is
 * unit-testable without assets.
 */

/**
 * Candidate pool of several word lists: concatenated in list order, dropping
 * a row whose **speakable headword** already appeared. The headword is the
 * 错词本 key (AGENTS.md "Persistence"), so the same word picked up by two
 * lists is one candidate and every drawn row resolves back to the book like
 * any other run's row. First occurrence wins — an earlier list's row (with
 * its columns) is kept.
 */
fun <T : SpeakableWord> dedupeByHeadword(rows: List<T>): List<T> {
    val seen = HashSet<String>()
    return rows.filter { seen.add(it.speak) }
}

/**
 * Draw [count] distinct rows from [pool] at random, in draw order: a shuffle
 * + take, so every subset of size [count] is equally likely — no replacement.
 * [count] is clamped to the pool (X ≥ 总词数 = 全量随机, Roadmap #9); a
 * non-positive [count] draws nothing. [random] is injectable for tests.
 */
fun <T : SpeakableWord> sampleWords(
    pool: List<T>,
    count: Int,
    random: Random = Random.Default,
): List<T> {
    if (count <= 0 || pool.isEmpty()) return emptyList()
    return pool.shuffled(random).take(count.coerceAtMost(pool.size))
}
