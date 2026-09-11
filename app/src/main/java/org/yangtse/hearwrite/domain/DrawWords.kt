package org.yangtse.hearwrite.domain

import kotlin.random.Random

/**
 * 多选词库 → 抽词听写 (Roadmap #9) pool logic — pure, so the draw contract is
 * unit-testable without assets.
 */

/**
 * Candidate pool of several word lists: concatenated in list order, dropping
 * a line whose **speakable headword** already appeared. The headword is the
 * 错词本 key (AGENTS.md "Persistence"), so the same word picked up by two
 * lists is one candidate and every drawn line resolves back to the book like
 * any other run's line. First occurrence wins — an earlier list's line
 * (with its columns) is kept.
 */
fun dedupeByHeadword(lines: List<String>): List<String> {
    val seen = HashSet<String>()
    return lines.filter { seen.add(speakTextFromEntry(it)) }
}

/**
 * Draw [count] distinct lines from [pool] at random, in draw order: a
 * shuffle + take, so every subset of size [count] is equally likely — no
 * replacement. [count] is clamped to the pool (X ≥ 总词数 = 全量随机, Roadmap
 * #9); a non-positive [count] draws nothing. [random] is injectable for tests.
 */
fun sampleWords(
    pool: List<String>,
    count: Int,
    random: Random = Random.Default,
): List<String> {
    if (count <= 0 || pool.isEmpty()) return emptyList()
    return pool.shuffled(random).take(count.coerceAtMost(pool.size))
}
