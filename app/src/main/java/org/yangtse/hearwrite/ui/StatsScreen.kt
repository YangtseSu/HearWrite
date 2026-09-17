package org.yangtse.hearwrite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.yangtse.hearwrite.domain.DayStat
import org.yangtse.hearwrite.domain.SessionKind
import org.yangtse.hearwrite.ui.theme.AppCardCorner
import java.time.LocalDate

private val TREND_BARS_HEIGHT = 120.dp

/**
 * Bar floors. A day with no run draws a short grey stub, so the fortnight reads
 * as a continuous axis and a rest day is a visible mark rather than a hole in
 * the chart (the grey branch used to be dead code: `fillMaxHeight(0f)` — both
 * min and max — is a zero-height box). A day that *did* have a run draws at
 * least a little taller and in the primary colour: 1 word against a 300-word
 * busiest day is 0.4dp, which would otherwise render below the rest stub and
 * read as "nothing happened".
 */
private val TREND_REST_STUB_HEIGHT = 3.dp
private val TREND_BAR_MIN_HEIGHT = 6.dp

/**
 * Runs the 最近记录 list shows before 查看全部. The record has no upper bound (a
 * year of daily use is ~350 rows), and this page is a summary — the full list
 * stays one tap away.
 */
private const val RECENT_PREVIEW_LIMIT = 20

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
    onOpenLibraryPreview: (category: String, label: String) -> Unit,
    viewModel: StatsViewModel = viewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val cleared by viewModel.cleared.collectAsStateWithLifecycle()
    val messages = rememberMessageController()
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    // 查看全部 on 最近记录: screen state, not list state — a record update
    // (a new run landing) must not silently collapse the list the user opened.
    var showAllRecent by rememberSaveable { mutableStateOf(false) }

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
            // The 最近记录 group has no upper bound, so the page is one lazy
            // list: a bounded card (概览 / 趋势 / 高频错词) is a single item and
            // each recorded run is its own row, composing only what is on
            // screen. A nested LazyColumn inside this one would be a crash, so
            // the record's card is drawn per row instead (see [SectionCardRow]).
            // The list is capped to a reading measure and centred, so a tablet
            // or desktop window does not stretch every card edge to edge; the
            // Box only centres, the list still fills the viewport's height.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxHeight().contentWidth().padding(innerPadding),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    when {
                        // A whole-viewport item, so the spinner / empty state / error
                        // card sits where it did when the page was a scroll column.
                        ui.loading -> item {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator() }
                        }

                        ui.loadFailed -> item {
                            LoadFailed(
                                onRetry = viewModel::retryLoad,
                                modifier = Modifier.fillParentMaxSize(),
                            )
                        }

                        ui.summary.runs == 0 -> item {
                            EmptyStats(modifier = Modifier.fillParentMaxSize())
                        }

                        else -> statsContent(
                            ui = ui,
                            showAllRecent = showAllRecent,
                            onToggleRecent = { showAllRecent = !showAllRecent },
                            onJumpToSource = onOpenLibraryPreview,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The settled page: 概览, 趋势, 高频错词 and 最近记录. Split out of [StatsScreen]
 * only because the `when` above would otherwise be the body of a lazy block.
 * [showAllRecent] is screen state (see [StatsScreen]) so the list does not
 * collapse again on every record update.
 */
private fun LazyListScope.statsContent(
    ui: StatsUiState,
    showAllRecent: Boolean,
    onToggleRecent: () -> Unit,
    onJumpToSource: (category: String, label: String) -> Unit,
) {
    item(key = "overview") { OverviewCard(ui) }
    item(key = "trend") { TrendCard(ui.trend) }
    if (ui.topWrong.isNotEmpty()) {
        item(key = "wrong_header") { SettingsSectionHeader("高频错词") }
        // The cap is the only signal the page has (the view model hands it the
        // truncated list), so the note appears exactly when the list is full —
        // which is also the only case where more words may exist.
        if (ui.topWrong.size == TOP_WRONG_LIMIT) {
            item(key = "wrong_note") {
                Text(
                    "仅显示错得最多的 $TOP_WRONG_LIMIT 个词；" +
                        "完整错词本见首页「更多 → 错词本」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                )
            }
        }
        item(key = "wrong_card") {
            SettingsCard {
                ui.topWrong.forEachIndexed { index, mark ->
                    WrongWordStatRow(
                        mark = mark,
                        divider = index != ui.topWrong.lastIndex,
                        onJumpToSource = onJumpToSource,
                    )
                }
            }
        }
    }
    item(key = "recent_header") { SettingsSectionHeader("最近记录") }
    // The record itself: bounded rows on screen, the whole list one tap away.
    val shown = if (showAllRecent) ui.recent else ui.recent.take(RECENT_PREVIEW_LIMIT)
    itemsIndexed(shown, key = { _, row -> row.id }) { index, row ->
        SectionCardRow(first = index == 0, last = index == shown.lastIndex) {
            SessionRowItem(
                row = row,
                divider = index != shown.lastIndex,
                onJumpToSource = onJumpToSource,
            )
        }
    }
    if (ui.recent.size > RECENT_PREVIEW_LIMIT) {
        item(key = "recent_more") {
            TextButton(
                onClick = onToggleRecent,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
            ) {
                Text(
                    if (showAllRecent) {
                        "收起"
                    } else {
                        "查看全部 ${ui.recent.size} 场记录"
                    },
                )
            }
        }
    }
}

/**
 * One row of a group card that lives in the page's LazyColumn — the record.
 * A card cannot wrap a lazy list (`Surface` + `Column` would compose every
 * row), so each row draws its own slice of it: the card colour, and the
 * 20dp corner only on the first/last row of the group. Interior rows are
 * square, so the group still reads as one card.
 */
@Composable
private fun SectionCardRow(
    first: Boolean,
    last: Boolean,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(
            topStart = if (first) AppCardCorner else 0.dp,
            topEnd = if (first) AppCardCorner else 0.dp,
            bottomStart = if (last) AppCardCorner else 0.dp,
            bottomEnd = if (last) AppCardCorner else 0.dp,
        ),
        content = content,
    )
}

/** The record could not be read: say so, and offer the way back. */
@Composable
private fun LoadFailed(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(start = 40.dp, end = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Text(
            "听写记录读取失败",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "记录仍保存在本机，请稍后重试。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("重试") }
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
    // Tabular figures ("tnum"): every digit keeps the same advance width, so
    // the figures stop jittering as the numbers change. Copied once for the
    // card's six cells rather than per cell.
    val valueStyle = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // Flush with the section cards below it: the row of cards used to
            // start on two different left baselines (this one was inset 16dp
            // and its text landed at 32dp, the rest at 16dp).
            .padding(top = 12.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("共听写 ${summary.runs} 场", style = MaterialTheme.typography.titleMedium)
            Text(
                "正式听写 ${summary.dictationRuns} 场 · 复习错词 ${summary.reviewRuns} 场",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            ) {
                StatFigure(
                    label = "听写词数",
                    value = "${summary.words}",
                    valueStyle = valueStyle,
                    modifier = Modifier.weight(1f),
                )
                StatFigure(
                    label = "错词率",
                    value = formatPercent(summary.wrongRate),
                    valueStyle = valueStyle,
                    modifier = Modifier.weight(1f),
                )
                StatFigure(
                    label = "累计用时",
                    value = formatDuration(summary.durationSec),
                    valueStyle = valueStyle,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            ) {
                StatFigure(
                    label = "听写天数",
                    value = "${summary.studyDays}",
                    valueStyle = valueStyle,
                    modifier = Modifier.weight(1f),
                )
                StatFigure(
                    label = "连续天数",
                    value = "${summary.streakDays} 天",
                    valueStyle = valueStyle,
                    modifier = Modifier.weight(1f),
                    // The streak alone cannot say whether today is already
                    // part of it: 连续 3 天 reads the same for a streak that
                    // ended yesterday and one extended this morning.
                    hint = if (ui.todayStudied) "今日已打卡" else null,
                )
                StatFigure(
                    label = "累计错词",
                    value = "${summary.wrong}",
                    valueStyle = valueStyle,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One headline figure: a large value over its label, optionally over a state
 * [hint]. [modifier] carries the caller's `weight`, so the three cells of a
 * row split it evenly — the widest value (累计用时) can no longer push the row
 * past its share. [valueStyle] is the caller's tabular-figure style.
 *
 * The value takes one line, which keeps the row's three labels on one
 * baseline, and lifts that clamp when the cell genuinely cannot fit it — a
 * duration at the top of the system font scale needs three lines in a third
 * of a phone row. The number is what the cell is for, so it wraps rather than
 * losing digits, and no hardcoded font size fights the user's scale.
 */
@Composable
private fun StatFigure(
    label: String,
    value: String,
    valueStyle: TextStyle,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    var wrapsValue by remember(value) { mutableStateOf(false) }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = valueStyle,
            maxLines = if (wrapsValue) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // Runs after every layout; the flag stops at the first real
            // ellipsis, so the wider re-measure settles on the next pass.
            onTextLayout = { layout ->
                if (!wrapsValue && layout.lineCount > 0 && layout.isLineEllipsized(0)) {
                    wrapsValue = true
                }
            },
            // The cell's own width, so the measurement the callback reports is
            // the one the row gave us.
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * `date · runs · words` — the one wording shared by the selected-day readout
 * and the bars' TalkBack description, which joins the same three parts with `，`
 * (a screen reader pauses there; a sighted reader reads ` · `). Reusing one
 * builder is what keeps the two from drifting. The date carries its weekday:
 * the axis below spells a weekday per bar, so the readout has to say the same
 * thing rather than a bare day number.
 */
private fun daySummary(day: DayStat, separator: String = " · "): String =
    "${formatDayWithWeekday(day.date)}$separator${day.runs} 场$separator${day.words} 词"

/**
 * 14-day word trend as plain bars (no chart library). Bar height is the day's
 * dictated word count relative to the window's busiest day, whose count labels
 * the plot's top-left (`最高 300 词`).
 *
 * The axis is one weekday character per bar instead of two dates: the window is
 * exactly two weeks, so the weekday is the one label that carries the rhythm —
 * it shows a gap was the weekend rather than just "some day off" — and a single
 * character fits every slot, where a `9月2日` label per bar would overflow.
 * The window's two end dates sit under it as the absolute anchor.
 *
 * A day with no run draws a short grey stub ([TREND_REST_STUB_HEIGHT]) rather
 * than nothing at all: a zero-height bar was invisible, so a rest day and a
 * blank stretch of chart looked identical, and the grey 休息日 branch was
 * unreachable code.
 *
 * A sighted reader can tap a column: the readout line under the chart spells
 * that day out ([daySummary]) and the column takes a 2dp primary outline. The
 * line is reserved in both states — the hint is what makes the tap
 * discoverable, and a permanent line keeps the card from jumping when the
 * selection changes. The selection is ephemeral screen state.
 *
 * Every bar also carries its own TalkBack description (date，场次，词数) — a
 * bare bar announces nothing.
 */
@Composable
private fun TrendCard(trend: List<DayStat>) {
    val maxWords = trend.maxOfOrNull { it.words }?.coerceAtLeast(1) ?: 1
    // The tapped day, held as a date rather than a row: resolving it against
    // the list below means a refreshed window can never leave a stale readout.
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val selectedDay = trend.firstOrNull { it.date == selectedDate }
    SettingsSectionHeader("最近 14 天")
    SettingsCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "最高 $maxWords 词",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TREND_BARS_HEIGHT),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    trend.forEach { day ->
                        val isSelected = day.date == selectedDate
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                // Tap before description: clickable is the
                                // merging semantics node, so the description
                                // rides the same TalkBack stop as the action
                                // instead of becoming a second one.
                                .clickable(onClickLabel = "查看当天数据") {
                                    selectedDate = if (isSelected) null else day.date
                                }
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            width = 2.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(4.dp),
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .semantics { contentDescription = daySummary(day, "，") },
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            // Height in dp rather than a fillMaxHeight fraction:
                            // a fraction of zero has no minimum to lift. Each
                            // floor is the one its case needs — a rest day's
                            // stub, or the taller floor a real day keeps no
                            // matter how small its share (see the constants).
                            val fraction = (day.words.toFloat() / maxWords).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(
                                        if (day.words > 0) {
                                            maxOf(
                                                TREND_BAR_MIN_HEIGHT,
                                                TREND_BARS_HEIGHT * fraction,
                                            )
                                        } else {
                                            TREND_REST_STUB_HEIGHT
                                        },
                                    )
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
                // The weekday axis: one character per slot, aligned with the
                // bars by the same spacing and weight. Decorative — each bar
                // already announces its own date and counts — so the characters
                // are cleared from the semantics tree instead of turning into
                // fourteen more TalkBack stops.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clearAndSetSemantics {},
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    trend.forEach { day ->
                        Text(
                            formatWeekday(day.date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (trend.isNotEmpty()) {
                Text(
                    "${formatDay(trend.first().date)} 至 ${formatDay(trend.last().date)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                if (selectedDay != null) {
                    daySummary(selectedDay)
                } else {
                    "点按柱子查看当天数据"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (selectedDay != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun WrongWordStatRow(
    mark: WrongWordRow,
    divider: Boolean,
    onJumpToSource: (category: String, label: String) -> Unit,
) {
    // Rows whose source is a built-in list open it, like the 错词本 drawer's
    // 查看词表; the rest stay read-only.
    val jump = mark.jump
    SettingsRow(
        title = mark.word,
        supporting = buildString {
            append("错 ${mark.errorCount} 次 · 最近 ${formatStamp(mark.lastWrongAt)}")
            // The drawer groups by source under a header; here each row stands
            // alone, so the source rides the row's own supporting line.
            append(" · ")
            append(mark.sourceTitle ?: "未知来源")
        },
        divider = divider,
        trailing = if (jump != null) {
            { RowChevron() }
        } else {
            null
        },
        onClick = jump?.let { target ->
            { onJumpToSource(target.category, target.label) }
        },
        onClickLabel = if (jump != null) "查看词表" else null,
    )
}

@Composable
private fun SessionRowItem(
    row: SessionRow,
    divider: Boolean,
    onJumpToSource: (category: String, label: String) -> Unit,
) {
    val jump = row.jump
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
        trailing = if (jump != null) {
            { RowChevron() }
        } else {
            null
        },
        onClick = jump?.let { target ->
            { onJumpToSource(target.category, target.label) }
        },
        // The row reads as a timestamp and figures; what a tap does — open the
        // word list it was dictated from — is nowhere in that text.
        onClickLabel = if (jump != null) "查看词表" else null,
    )
}
