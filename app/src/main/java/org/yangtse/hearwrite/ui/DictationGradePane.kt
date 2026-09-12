package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.yangtse.hearwrite.data.OCR_DISCLAIMER
import org.yangtse.hearwrite.domain.AnswerVerdict
import org.yangtse.hearwrite.domain.GradedAnswer
import org.yangtse.hearwrite.domain.GradeResult
import org.yangtse.hearwrite.ui.theme.hearWriteSemantics
import org.yangtse.hearwrite.ui.theme.wordHead

/**
 * 拍照批改 pane (Roadmap #11) — the finish card's photo-grading surface.
 *
 * The machine's verdicts are a **proposal**: every non-正确 answer is listed
 * with what the student wrote, the doubtful ones are badged 存疑, and nothing
 * reaches the 错词本 until the human confirms the ticks. The recognition
 * language is the run's own ([org.yangtse.hearwrite.domain.isCjkRun]) — there
 * is no picker to get wrong.
 */
@Composable
fun DictationGradePane(
    result: GradeResult?,
    selected: Set<Int>,
    busy: Boolean,
    phase: String,
    error: String?,
    pickerBusy: Boolean,
    /** True when the last picture can be recognized again (network/服务 error). */
    retryable: Boolean,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onRetry: () -> Unit,
    onToggle: (Int) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "拍照批改",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("返回成绩") }
        }

        if (busy) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    phase.ifEmpty { "识别中…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        error?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Row {
                        if (retryable) {
                            TextButton(onClick = onRetry, enabled = !busy) { Text("重试") }
                        }
                        TextButton(onClick = onCamera, enabled = !busy && !pickerBusy) {
                            Text("重新拍照")
                        }
                    }
                }
            }
        }

        when {
            result == null -> GradeEmptyState(
                pickerBusy = pickerBusy,
                busy = busy,
                onCamera = onCamera,
                onGallery = onGallery,
            )

            result.expectedTotal == 0 -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) { Text("本场没有可批改的词") }

            else -> GradeReview(
                result = result,
                selected = selected,
                busy = busy,
                onToggle = onToggle,
                onRecapture = onCamera,
                onConfirm = onConfirm,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Pre-scan state: what a photo gives you, then the two capture actions. */
@Composable
private fun GradeEmptyState(
    pickerBusy: Boolean,
    busy: Boolean,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.CameraAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            "拍下学生的作答纸",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "自动与本次词表逐词比对，标出写错的词；AI 识图可能存在误差，逐条核对后再记入错词本",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Button(
            onClick = onCamera,
            enabled = !busy && !pickerBusy,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        ) {
            Icon(Icons.Filled.CameraAlt, contentDescription = null)
            Text("拍照批改", modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
            onClick = onGallery,
            enabled = !busy && !pickerBusy,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
            Text("从相册选择", modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            OCR_DISCLAIMER,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** Post-scan state: the proposed verdicts, their ticks, and the confirm. */
@Composable
private fun GradeReview(
    result: GradeResult,
    selected: Set<Int>,
    busy: Boolean,
    onToggle: (Int) -> Unit,
    onRecapture: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 题号 order first (EXTRA items have slot 0 and sink to the end).
    val ordered = result.items.withIndex().sortedBy { (_, item) -> if (item.slot == 0) Int.MAX_VALUE else item.slot }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "正确 ${result.correctCount} 词 · 错词 ${result.wrongCount}" +
                (if (result.missingCount > 0) " · 漏答 ${result.missingCount}" else "") +
                (if (result.extraCount > 0) " · 多余 ${result.extraCount}" else ""),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (result.doubtCount > 0) {
            Text(
                "有 ${result.doubtCount} 处需人工核对（标「存疑」）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (result.mismatched) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(
                    "识别到的作答与本场词表差别较大，可能拍错了页面，请核对或重新拍照",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(ordered) { _, (index, item) ->
                GradeRow(
                    item = item,
                    checked = index in selected,
                    onToggle = { onToggle(index) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onRecapture,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("重新拍照") }
            Button(
                onClick = onConfirm,
                enabled = !busy && selected.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("确认错词（${selected.size}）") }
        }
        Text(
            OCR_DISCLAIMER,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** One judged answer: what was dictated, what was written, and its tick. */
@Composable
private fun GradeRow(
    item: GradedAnswer,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // The 正确 badge takes the container pair (successContainer background +
    // onSuccessContainer label) exactly like errorContainer/onErrorContainer:
    // the two travel together, so the label can never land on a background it
    // does not contrast with.
    val correct = item.verdict == AnswerVerdict.CORRECT
    val (label, color) = when (item.verdict) {
        AnswerVerdict.CORRECT -> "正确" to hearWriteSemantics.onSuccessContainer
        AnswerVerdict.WRONG -> (if (item.doubt) "错词 · 存疑" else "错词") to colors.error
        AnswerVerdict.MISSING -> (if (item.doubt) "漏答 · 存疑" else "漏答") to colors.tertiary
        AnswerVerdict.EXTRA -> "多余作答" to colors.outline
    }
    val bookable = item.verdict != AnswerVerdict.EXTRA
    Surface(
        color = colors.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (item.slot > 0) {
                    Surface(color = colors.surfaceVariant, shape = CircleShape) {
                        Text(
                            "${item.slot}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    item.expected ?: "额外一行",
                    style = MaterialTheme.typography.wordHead,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // The written answer is the evidence the human is asked to
                // verify: a 1-line clamp hides exactly the characters that
                // differ on a near miss (the case the pane exists for), so
                // wrap instead of truncating. 3 lines fits any single entry;
                // the ellipsis stays as a last resort for a stray long line.
                Text(
                    if (item.answer.isNullOrEmpty()) "未识别到作答" else "写：${item.answer}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.doubt || item.outOfOrder) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.doubt) {
                            Icon(
                                Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = null,
                                tint = colors.tertiary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Text(
                            buildString {
                                if (item.outOfOrder) append("顺序不符")
                                if (item.outOfOrder && item.doubt) append(" · ")
                                if (item.doubt) append("存疑：请对照纸面核对")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.tertiary,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
            if (correct) {
                // Container pair, taken together like
                // errorContainer/onErrorContainer: the 正确 label sits on its
                // own success background instead of a bare coloured word.
                Surface(
                    color = hearWriteSemantics.successContainer,
                    shape = CircleShape,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = hearWriteSemantics.onSuccessContainer,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            } else {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            if (bookable) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.semantics {
                        contentDescription = "把 ${item.expected ?: ""} 记入错词本"
                    },
                )
            } else {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = "多余的作答，无法对应到词",
                    tint = colors.outline,
                    modifier = Modifier.padding(horizontal = 12.dp).size(18.dp),
                )
            }
        }
    }
}
