package org.yangtse.hearwrite.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.data.HistoryEntry
import org.yangtse.hearwrite.data.WrongWordMark
import org.yangtse.hearwrite.domain.DayStat
import org.yangtse.hearwrite.domain.DictationSession
import org.yangtse.hearwrite.domain.SessionKind
import org.yangtse.hearwrite.domain.StatsSummary
import org.yangtse.hearwrite.domain.TREND_DAYS
import org.yangtse.hearwrite.domain.dailyStats
import org.yangtse.hearwrite.domain.summarize
import java.time.LocalDate
import java.time.ZoneId

/**
 * One recorded run as the 最近记录 list shows it. [jump] carries the built-in
 * list the run came from so the row offers the same 查看词表 jump the 错词本
 * drawer does; null (a history row, a 抽词听写 pool, a list that is gone)
 * leaves the row read-only.
 */
data class SessionRow(
    val id: Long,
    val startedAt: Long,
    val sourceTitle: String?,
    val totalWords: Int,
    val wrongCount: Int,
    val durationSec: Long,
    val kind: SessionKind,
    val jump: SourceJump?,
) {
    val correctCount: Int get() = (totalWords - wrongCount).coerceAtLeast(0)
}

/**
 * One 高频错词 row as the page shows it: the mark plus its resolved source
 * title (the drawer's own wording, via the shared resolver) and the built-in
 * list it came from, when there is one to jump to.
 */
data class WrongWordRow(
    val word: String,
    val errorCount: Int,
    val lastWrongAt: Long,
    val sourceTitle: String?,
    val jump: SourceJump?,
)

/** Everything 听写统计 renders. */
data class StatsUiState(
    val loading: Boolean = true,
    /**
     * The record could not be read at all (Room threw). The page says so and
     * offers 重试; it used to fall back to the empty state, which read as
     * "you never dictated anything" and hid a real failure (AGENTS.md: an
     * async failure must surface, not be dressed up as a state).
     */
    val loadFailed: Boolean = false,
    val summary: StatsSummary,
    val trend: List<DayStat>,
    /**
     * True when today already carries a run — today's is the window's last row
     * (`dailyStats` zero-fills up to and including today). Derived here rather
     * than read off [trend] in the composable so the 连续天数 hint stays a
     * function of the same window the chart draws.
     */
    val todayStudied: Boolean,
    val topWrong: List<WrongWordRow>,
    val recent: List<SessionRow>,
)

/**
 * How many wrong words the 高频错词 section lists. The page cannot see the
 * book's true size (it is handed this capped list), so the screen prints its
 * 「仅显示…」 note exactly when the list hits this cap.
 */
const val TOP_WRONG_LIMIT = 10

/**
 * 听写统计 (Roadmap #3): aggregates the local `sessions` record with the pure
 * `domain/Stats.kt` functions and resolves each run's source title the same
 * way the 错词本 drawer does. Everything stays on device; the page is
 * read-only apart from 清空记录.
 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HearWriteApplication
    private val sessionRepository = app.sessionRepository
    private val wrongWordsRepository = app.wrongWordsRepository
    private val historyRepository = app.historyRepository
    private val libraryRepository = app.libraryRepository

    /** Built-in list titles by list id, loaded once (source resolution). */
    private val libraryTitles = MutableStateFlow<Map<String, String>>(emptyMap())

    private val _cleared = MutableStateFlow(false)

    /** One-shot 清空记录 confirmation for the screen's toast. */
    val cleared: StateFlow<Boolean> = _cleared.asStateFlow()

    /**
     * Bumped by 重试 to re-subscribe the record after a failed read. The Room
     * flows are cold, so one more attempt is just a re-collection; the failed
     * state stays on screen until that attempt reports.
     */
    private val retry = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<StatsUiState> = retry
        .flatMapLatest { attempt ->
            flow {
                // 重试 shows the spinner again instead of the stale failure.
                if (attempt > 0) emit(emptyState(loading = true))
                emitAll(recordFlow())
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyState(loading = true),
        )

    private fun recordFlow(): Flow<StatsUiState> = combine(
        sessionRepository.observe(),
        wrongWordsRepository.observeMarks(),
        historyRepository.observe(),
        libraryTitles,
    ) { sessions: List<DictationSession>,
        marks: List<WrongWordMark>,
        history: List<HistoryEntry>,
        titles: Map<String, String>,
        ->
        // "Today" is read here (not inside the pure aggregators) so the pure
        // functions stay a function of their arguments.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val trend = dailyStats(sessions, zone, endDay = today, days = TREND_DAYS)
        StatsUiState(
            loading = false,
            summary = summarize(sessions, zone, today),
            trend = trend,
            // The window's last row is today by construction; a run there is
            // exactly 今日已打卡.
            todayStudied = (trend.lastOrNull()?.runs ?: 0) > 0,
            // Both rows resolve their source exactly like the 错词本 drawer
            // (same resolver, same wording) — the page used to print the run's
            // source out of a separate path, and the wrong words with no
            // source at all.
            topWrong = marks.take(TOP_WRONG_LIMIT).map { it.toRow(history, titles) },
            recent = sessions.map { it.toRow(history, titles) },
        )
    }
        // A Room failure must not leave the page spinning forever, and it must
        // not be dressed up as 暂无听写记录 either: the record exists, reading
        // it failed. The page then says so and offers 重试.
        .catch { e ->
            Log.w(TAG, "stats flow failed", e)
            emit(failedState())
        }

    /** Re-read the record after a failure (the error card's 重试). */
    fun retryLoad() {
        retry.value += 1
    }

    init {
        // Built-in titles are decoration: a failed scan degrades built-in
        // sources to 未知来源, it never blanks the page (AGENTS.md: launches catch).
        viewModelScope.launch {
            val titles = buildMap {
                try {
                    libraryRepository.categories().forEach { category ->
                        libraryRepository.lists(category.name).forEach { list ->
                            put(list.id, list.label)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "library titles unavailable", e)
                }
            }
            libraryTitles.value = titles
        }
    }

    /** Empty the whole record (the screen's confirm dialog owns the asking). */
    fun clearSessions() {
        viewModelScope.launch {
            try {
                sessionRepository.clear()
                _cleared.value = true
            } catch (e: Exception) {
                Log.w(TAG, "clearing sessions failed", e)
            }
        }
    }

    fun consumeCleared() {
        _cleared.value = false
    }

    private fun DictationSession.toRow(
        history: List<HistoryEntry>,
        titles: Map<String, String>,
    ): SessionRow {
        val title = resolveSourceTitle(sourceLabel, history, titles)
        val jump = resolveSourceJump(sourceLabel, title)
        return SessionRow(
            id = id,
            startedAt = startedAt,
            sourceTitle = title,
            totalWords = totalWords,
            wrongCount = wrongCount,
            durationSec = durationSec,
            kind = kind,
            jump = jump,
        )
    }

    private fun WrongWordMark.toRow(
        history: List<HistoryEntry>,
        titles: Map<String, String>,
    ): WrongWordRow {
        val title = resolveSourceTitle(sourceLabel, history, titles)
        val jump = resolveSourceJump(sourceLabel, title)
        return WrongWordRow(
            word = word,
            errorCount = errorCount,
            lastWrongAt = lastWrongAt,
            sourceTitle = title,
            jump = jump,
        )
    }

    companion object {
        private const val TAG = "StatsViewModel"

        /** Seed / fallback state: the empty record, optionally still loading. */
        private fun emptyState(loading: Boolean) = StatsUiState(
            loading = loading,
            summary = summarize(emptyList(), ZoneId.systemDefault(), LocalDate.now()),
            trend = emptyList(),
            todayStudied = false,
            topWrong = emptyList(),
            recent = emptyList(),
        )

        /** The read failed: same empty figures, but the page says why. */
        private fun failedState() = emptyState(loading = false).copy(loadFailed = true)
    }
}
