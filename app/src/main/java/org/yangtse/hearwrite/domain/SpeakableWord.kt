package org.yangtse.hearwrite.domain

/**
 * What a **run-level** rule reads off a word: its speakable headword (the
 * 错词本 key) and its [WordKind] (`docs/2026-09-18-DATA-MODEL.md` §1.1).
 *
 * A parsed [WordRow] and a resolved [ResolvedWord] both answer it, which is
 * what lets the rules that only ever look at the headword run on either side
 * of the dictionary lookup instead of being duplicated there: the 抽词听写
 * pool dedupes and samples over authored rows as readily as over resolved
 * ones, and a run's language is decided from the same fields whether the draft
 * has been resolved or not.
 */
interface SpeakableWord {
    val speak: String
    val kind: WordKind
}
