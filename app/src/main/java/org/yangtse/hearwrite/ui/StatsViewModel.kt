package org.yangtse.hearwrite.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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

/** One recorded run as the 最近记录 list shows it (source title resolved). */
data class SessionRow(
    val id: Long,
    val startedAt: Long,
    val sourceTitle: String?,
    val totalWords: Int,
    val wrongCount: Int,
    val durationSec: Long,
    val kind: SessionKind,
) {
    val correctCount: Int get() = (totalWords - wrongCount).coerceAtLeast(0)
}

/** Everything 听写统计 renders. */
data class StatsUiState(
    val loading: Boolean = true,
    val summary: StatsSummary,
    val trend: List<DayStat>,
    val topWrong: List<WrongWordMark>,
    val recent: List<SessionRow>,
)

/** How many wrong words the 高频错词 section lists. */
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

    val uiState: StateFlow<StatsUiState> = combine(
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
        StatsUiState(
            loading = false,
            summary = summarize(sessions, zone, today),
            trend = dailyStats(sessions, zone, endDay = today, days = TREND_DAYS),
            topWrong = marks.take(TOP_WRONG_LIMIT),
            recent = sessions.map { it.toRow(history, titles) },
        )
    }
        // A Room failure must not leave the page spinning forever — fall back
        // to the settled empty state (the page then shows 暂无听写记录).
        .catch { e ->
            Log.w(TAG, "stats flow failed; showing the empty record", e)
            emit(emptyState(loading = false))
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyState(loading = true),
        )

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
    ) = SessionRow(
        id = id,
        startedAt = startedAt,
        sourceTitle = resolveSourceTitle(sourceLabel, history, titles),
        totalWords = totalWords,
        wrongCount = wrongCount,
        durationSec = durationSec,
        kind = kind,
    )

    companion object {
        private const val TAG = "StatsViewModel"

        /** Seed / fallback state: the empty record, optionally still loading. */
        private fun emptyState(loading: Boolean) = StatsUiState(
            loading = loading,
            summary = summarize(emptyList(), ZoneId.systemDefault(), LocalDate.now()),
            trend = emptyList(),
            topWrong = emptyList(),
            recent = emptyList(),
        )
    }
}
