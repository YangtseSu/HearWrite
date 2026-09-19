package org.yangtse.hearwrite.domain

/**
 * Test fixture: `word | pos | gloss` lines as the **resolved** rows every
 * runtime consumer takes — [resolveWord] composed with no dictionary behind it,
 * which is exactly the shape a run's rows take when the lexicon has nothing to
 * add. Cases stay readable as raw list lines.
 *
 * Tests that need dictionary data drive the real repository seam instead
 * (`LexiconRepositoryTest`, `WordDisplayTest`).
 */
fun resolvedRows(vararg lines: String): List<ResolvedWord> = lines.map(::resolved)

fun resolved(line: String): ResolvedWord = resolvedRow(parseWordLine(line))

fun resolvedRow(row: WordRow): ResolvedWord = resolveWord(row, entry = null, hanzi = null)

/** A whole stored list (a built-in asset or a history row) as resolved rows. */
fun resolvedText(text: String): List<ResolvedWord> = parseWordRows(text).map(::resolvedRow)
