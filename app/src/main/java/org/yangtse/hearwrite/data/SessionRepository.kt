package org.yangtse.hearwrite.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.yangtse.hearwrite.domain.DictationSession
import org.yangtse.hearwrite.domain.SessionKind

/**
 * The local dictation record (Roadmap #3 听写统计): one row per **completed**
 * run, purely on-device. [record] is called when the engine reaches the end
 * of its list (a stopped/abandoned run leaves no row); [observe] feeds the
 * stats page, which aggregates with the pure `domain/Stats.kt` functions.
 */
class SessionRepository(private val dao: SessionDao) {

    /** Every recorded run, newest first. */
    fun observe(): Flow<List<DictationSession>> =
        dao.observeAll().map { rows -> rows.map(::toSession) }

    /**
     * Record one finished run. Called once per completed run — the caller
     * (DictationViewModel) captures the numbers at completion so a review
     * round restarted from the finish card records as its own row.
     */
    suspend fun record(
        startedAt: Long,
        sourceLabel: String?,
        totalWords: Int,
        wrongCount: Int,
        durationSec: Long,
        kind: SessionKind,
    ) {
        if (totalWords <= 0) return
        dao.insert(
            SessionEntity(
                startedAt = startedAt,
                sourceLabel = sourceLabel,
                totalWords = totalWords,
                wrongCount = wrongCount.coerceIn(0, totalWords),
                durationSec = durationSec.coerceAtLeast(0),
                kind = kind.wire,
            )
        )
    }

    /** Drop the whole record (the stats page's 清空 with its confirm dialog). */
    suspend fun clear() = dao.clear()

    private fun toSession(entity: SessionEntity) = DictationSession(
        id = entity.id,
        startedAt = entity.startedAt,
        sourceLabel = entity.sourceLabel,
        totalWords = entity.totalWords,
        wrongCount = entity.wrongCount,
        durationSec = entity.durationSec,
        kind = SessionKind.fromWire(entity.kind),
    )
}
