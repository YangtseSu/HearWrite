package org.yangtse.hearwrite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.yangtse.hearwrite.domain.DayStat
import org.yangtse.hearwrite.domain.SessionKind

private val TREND_BARS_HEIGHT = 120.dp

/**
 * 听写统计 (Roadmap #3) — the local record of every completed run: headline
 * numbers, a 14-day word trend, the most-wrong words and the recent runs.
 * Pure aggregation lives in `domain/Stats.kt`; this page only renders it.
 * Nothing leaves the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = viewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val cleared by viewModel.cleared.collectAsStateWithLifecycle()
    val messages = rememberMessageController()
    var showClearDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(cleared) {
        if (cleared) {
            messages.show("已清空听写记录")
            viewModel.consumeCleared()
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空听写记录？") },
            text = { Text("将删除全部 ${ui.summary.runs} 场听写记录，错词本不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    viewModel.clearSessions()
                }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }

    CompositionLocalProvider(LocalMessages provides messages) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("听写统计") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    },
                    actions = {
                        if (ui.summary.runs > 0) {
                            IconButton(onClick = { showClearDialog = true }) {
                                Icon(
                                    Icons.Outlined.DeleteSweep,
                                    contentDescription = "清空听写记录",
                                )
                            }
                        }
                    },
                )
            },
            snackbarHost = { MessageHost(messages) },
        ) { innerPadding ->
            when {
                ui.loading -> Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                ui.summary.runs == 0 -> EmptyStats(modifier = Modifier.padding(innerPadding))

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 32.dp),
                ) {
                    OverviewCard(ui)
                    TrendCard(ui.trend)
                    if (ui.topWrong.isNotEmpty()) {
                        SettingsSectionHeader("高频错词")
                        SettingsCard {
                            ui.topWrong.forEachIndexed { index, mark ->
                                WrongWordRow(
                                    mark = mark,
                                    divider = index != ui.topWrong.lastIndex,
                                )
                            }
                        }
                    }
                    SettingsSectionHeader("最近记录")
                    SettingsCard {
                        ui.recent.forEachIndexed { index, row ->
                            SessionRowItem(row = row, divider = index != ui.recent.lastIndex)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStats(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.BarChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(
                "暂无听写记录",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "完整听完一场听写后，这里会记录场次、词数与错词情况。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 40.dp, end = 40.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun OverviewCard(ui: StatsUiState) {
    val summary = ui.summary
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("共听写 ${summary.runs} 场", style = MaterialTheme.typography.titleMedium)
            Text(
                "正式 ${summary.dictationRuns} 场 · 复习错词 ${summary.reviewRuns} 场",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatFigure("听写词数", "${summary.words}")
                StatFigure("错词率", formatPercent(summary.wrongRate))
                StatFigure("累计用时", formatDuration(summary.durationSec))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatFigure("听写天数", "${summary.studyDays}")
                StatFigure("连续天数", "${summary.streakDays}")
                StatFigure("累计错词", "${summary.wrong}")
            }
        }
    }
}

@Composable
private fun StatFigure(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * 14-day word trend as plain bars (no chart library): bar height is the day's
 * dictated word count relative to the window's busiest day. Every bar carries
 * its own TalkBack description (date · 场次 · 词数) — a bare bar announces
 * nothing.
 */
@Composable
private fun TrendCard(trend: List<DayStat>) {
    val maxWords = trend.maxOfOrNull { it.words }?.coerceAtLeast(1) ?: 1
    SettingsSectionHeader("最近 14 天")
    SettingsCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(TREND_BARS_HEIGHT),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                trend.forEach { day ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics {
                                contentDescription = "${formatDay(day.date)}，" +
                                    "${day.runs} 场，${day.words} 词"
                            },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        val fraction = day.words.toFloat() / maxWords
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(fraction.coerceIn(0f, 1f))
                                .background(
                                    color = if (day.words > 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                                ),
                        )
                    }
                }
            }
            if (trend.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        formatDay(trend.first().date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatDay(trend.last().date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun WrongWordRow(mark: org.yangtse.hearwrite.data.WrongWordMark, divider: Boolean) {
    SettingsRow(
        title = mark.word,
        supporting = "错 ${mark.errorCount} 次 · 最近 ${formatStamp(mark.lastWrongAt)}",
        divider = divider,
    )
}

@Composable
private fun SessionRowItem(row: SessionRow, divider: Boolean) {
    val kindLabel = if (row.kind == SessionKind.REVIEW) "复习错词" else "正式听写"
    val wrongText = if (row.wrongCount > 0) {
        "错 ${row.wrongCount} · 正确 ${row.correctCount}"
    } else {
        "全对"
    }
    SettingsRow(
        title = "${formatStamp(row.startedAt)} · $kindLabel",
        supporting = "${row.sourceTitle ?: "未知来源"} · ${row.totalWords} 词 · " +
            "$wrongText · ${formatDuration(row.durationSec)}",
        divider = divider,
    )
}
