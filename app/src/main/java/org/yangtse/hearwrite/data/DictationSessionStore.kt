package org.yangtse.hearwrite.data

import org.yangtse.hearwrite.domain.ResolvedWord

/**
 * In-memory handoff of the prepared word list between the launching screen
 * (Home paste area or a library list preview) and the dictation screen —
 * route arguments would need URL escaping for CJK/pipes and large lists.
 * Written right before navigating; the DictationViewModel consumes it once
 * per session. Survives configuration changes (process death does not; the
 * session restarts from Home, same as upstream).
 */
class DictationSessionStore {
    /** Resolved rows, slice → shuffle already applied by the caller. */
    @Volatile
    var rows: List<ResolvedWord> = emptyList()

    /**
     * Provenance of the staged rows for the 错词本 source label (Roadmap #1):
     * a built-in list id (`default_<category>_<label>`), the history row id
     * the list was recorded under, or the `multi:` label of a 抽词听写 pool
     * (Roadmap #9); null for bare-word sessions (听写错词 over the book,
     * manual headwords) whose wrong marks keep no source.
     */
    @Volatile
    var sourceLabel: String? = null

    /**
     * Stage one session: [rows] plus its optional [sourceLabel]. Written
     * right before navigating (Home records the list in history and hands the
     * row id; the library preview hands the built-in list id).
     */
    fun stage(rows: List<ResolvedWord>, sourceLabel: String?) {
        this.rows = rows
        this.sourceLabel = sourceLabel
    }

    /**
     * Consume the staged session — one session, one read. An activity kill
     * that recreates the dictation ViewModel must not replay (or silently
     * restart) the old session; without a staged list the screen shows the
     * empty state and 返回 restarts from Home.
     */
    fun take(): Session {
        val session = Session(rows, sourceLabel)
        rows = emptyList()
        sourceLabel = null
        return session
    }

    /** One staged session: resolved rows plus its provenance label. */
    data class Session(
        val rows: List<ResolvedWord>,
        val sourceLabel: String?,
    )
}
