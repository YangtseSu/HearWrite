package org.yangtse.hearwrite.data

/**
 * In-memory handoff of the prepared word list between the launching screen
 * (Home paste area or a library list preview) and the dictation screen —
 * route arguments would need URL escaping for CJK/pipes and large lists.
 * Written right before navigating; the DictationViewModel consumes it once
 * per session. Survives configuration changes (process death does not; the
 * session restarts from Home, same as upstream).
 */
class DictationSessionStore {
    /** Canonical list lines, slice → shuffle already applied by the caller. */
    @Volatile
    var lines: List<String> = emptyList()

    /**
     * Consume the staged list — one session, one read. An activity kill that
     * recreates the dictation ViewModel must not replay (or silently restart)
     * the old session; without a staged list the screen shows the empty
     * state and 返回 restarts from Home.
     */
    fun take(): List<String> {
        val staged = lines
        lines = emptyList()
        return staged
    }
}
