package org.yangtse.hearwrite.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yangtse.hearwrite.domain.DictationSession
import org.yangtse.hearwrite.domain.SessionKind

/**
 * Locks the 听写统计 record semantics (Roadmap #3) over the DAO seam:
 * - a recorded run round-trips its fields and kind;
 * - a review round is distinguished from a normal dictation;
 * - the wrong count is clamped into the run's own word count and the
 *   duration never goes negative (a caller bug must not poison the record);
 * - a zero-word run is never stored;
 * - an unknown stored kind degrades to DICTATION instead of dropping the row.
 * The SQL itself (SessionDao) is covered on device by the Room migration test.
 */
class SessionRepositoryTest {

    private class FakeSessionDao : SessionDao {
        val rows = MutableStateFlow<List<SessionEntity>>(emptyList())
        private var nextId = 1L

        override fun observeAll(): Flow<List<SessionEntity>> = rows

        override suspend fun insert(session: SessionEntity) {
            rows.value = rows.value + session.copy(id = nextId++)
        }

        override suspend fun clear() {
            rows.value = emptyList()
        }
    }

    private fun repo(): Pair<SessionRepository, FakeSessionDao> {
        val dao = FakeSessionDao()
        return SessionRepository(dao) to dao
    }

    @Test
    fun `record round-trips one run with its kind`() = runTest {
        val (repository, _) = repo()

        repository.record(
            startedAt = 1_700_000_000_000,
            sourceLabel = "default_人教版小学_一年级上",
            totalWords = 20,
            wrongCount = 3,
            durationSec = 240,
            kind = SessionKind.DICTATION,
        )
        repository.record(
            startedAt = 1_700_000_100_000,
            sourceLabel = null,
            totalWords = 5,
            wrongCount = 5,
            durationSec = 60,
            kind = SessionKind.REVIEW,
        )

        val sessions = repository.observe().firstValue()
        assertEquals(2, sessions.size)
        val first = sessions.first()
        assertEquals(1_700_000_000_000L, first.startedAt)
        assertEquals("default_人教版小学_一年级上", first.sourceLabel)
        assertEquals(20, first.totalWords)
        assertEquals(3, first.wrongCount)
        assertEquals(17, first.correctCount)
        assertEquals(240L, first.durationSec)
        assertEquals(SessionKind.DICTATION, first.kind)
        assertEquals(SessionKind.REVIEW, sessions.last().kind)
        assertEquals(0, sessions.last().correctCount)
    }

    @Test
    fun `wrong count clamps into the run and duration never goes negative`() = runTest {
        val (repository, _) = repo()

        repository.record(
            startedAt = 1L,
            sourceLabel = null,
            totalWords = 4,
            wrongCount = 9,
            durationSec = -5,
            kind = SessionKind.DICTATION,
        )

        val row = repository.observe().firstValue().single()
        assertEquals(4, row.wrongCount)
        assertEquals(0, row.correctCount)
        assertEquals(0L, row.durationSec)
    }

    @Test
    fun `a zero-word run is never stored`() = runTest {
        val (repository, _) = repo()
        repository.record(1L, null, totalWords = 0, wrongCount = 0, durationSec = 0, kind = SessionKind.DICTATION)
        assertTrue(repository.observe().firstValue().isEmpty())
    }

    @Test
    fun `an unknown stored kind degrades to a dictation run`() = runTest {
        val (repository, dao) = repo()
        dao.rows.value = listOf(
            SessionEntity(
                id = 7,
                startedAt = 42L,
                sourceLabel = null,
                totalWords = 10,
                wrongCount = 1,
                durationSec = 30,
                kind = "somethingNewer",
            )
        )
        val row = repository.observe().firstValue().single()
        assertEquals(SessionKind.DICTATION, row.kind)
        assertEquals(42L, row.startedAt)
    }

    @Test
    fun `clear drops the whole record`() = runTest {
        val (repository, _) = repo()
        repository.record(1L, null, 5, 1, 10, SessionKind.DICTATION)
        repository.record(2L, null, 5, 1, 10, SessionKind.REVIEW)
        assertEquals(2, repository.observe().firstValue().size)

        repository.clear()
        assertTrue(repository.observe().firstValue().isEmpty())
    }

    /** First emission of the DAO flow (the fake emits synchronously). */
    private suspend fun Flow<List<DictationSession>>.firstValue(): List<DictationSession> =
        first()
}
