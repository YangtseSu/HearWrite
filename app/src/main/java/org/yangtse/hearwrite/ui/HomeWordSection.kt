package org.yangtse.hearwrite.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.yangtse.hearwrite.domain.glossNeedsExpansion
import org.yangtse.hearwrite.domain.parseWordEntries

/** 示例 content: English words with gloss columns (朗读释义 demo-able). */
private const val SAMPLE_EN = "apple | n. | 苹果\nbanana | n. | 香蕉\nschool | n. | 学校\nbook | n. | 书\ncar | n. | 汽车"

/** 示例 content: bare 汉字词语 (zh-CN dictation). */
private const val SAMPLE_CJK = "香蕉\n学校\n苹果\n月亮\n生日"

/**
 * 单词列表 section (alice's WordInputSection + section header): the header row
 * carries the title, the parsed-count badge and the 编辑/完成 toggle; the body
 * is the parsed display list (展示态, non-empty) where tapping a row selects
 * the 起始词 and long meta text expands, or the paste textarea otherwise —
 * both 编辑态 and an empty 展示态 look like the editor (textarea + 共 N 词 /
 * 示例 / 清空 footer), so starting from scratch is seamless; typing on the
 * empty 展示态 flips it to 编辑态 before the first change lands. Deletion
 * rewrites the draft line by line.
 */
@Composable
fun WordListSection(
    draft: String,
    displayMode: Boolean,
    wordCount: Int,
    startIndex: Int,
    onDraftChange: (String) -> Unit,
    onToggleDisplayMode: () -> Unit,
    onStartIndexChange: (Int) -> Unit,
    onDeleteWord: (Int) -> Unit,
    onFillSample: (String) -> Unit,
    onClear: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Auto-focus when 完成 lands on an empty draft: with no list to show,
    // the section drops straight back to the textarea, and the user's first
    // keystroke must land in it — not require a second tap on the field.
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(displayMode) {
        if (!displayMode && draft.isBlank()) {
            fieldFocus.requestFocus()
        }
    }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "单词列表",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (wordCount > 0) {
                Spacer(Modifier.width(8.dp))
                CountBadge(wordCount)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onScan) {
                Icon(
                    Icons.Filled.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("拍照识词")
            }
            TextButton(onClick = onToggleDisplayMode) {
                Icon(
                    if (displayMode) Icons.Filled.Edit else Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (displayMode) "编辑" else "完成")
            }
        }
        if (displayMode && wordCount > 0) {
            WordDisplayList(
                draft = draft,
                startIndex = startIndex,
                onStartIndexChange = onStartIndexChange,
                onDeleteWord = onDeleteWord,
                modifier = Modifier.weight(1f),
            )
        } else {
            // Editing surface: the real 编辑态, or 展示态 whose list is empty
            // — an empty list keeps the editor's look (textarea + 示例/清空
            // footer) so it is indistinguishable from editing. The first
            // change made on that empty 展示态 flips the mode first: the
            // header becomes 完成 and the parsed list never snaps in
            // mid-keystroke; entering via the 编辑 button changes nothing but
            // the header and focus (smooth by construction).
            val emptyDisplay = displayMode && wordCount == 0
            // One-shot empty→edit flip: burst keystrokes (IME commits, fast
            // typing, injected text) can all arrive before the recomposition,
            // each re-running this old lambda — an unguarded toggle would
            // flip 展示态 back and forth and land the word in the display
            // list mid-entry. Flip at most once per empty-展示态 session.
            var flippedFromEmpty by remember { mutableStateOf(false) }
            LaunchedEffect(displayMode, wordCount) {
                if (displayMode && wordCount == 0) flippedFromEmpty = false
            }
            // Skinned to match the display list's card: same 16dp roundness,
            // same surfaceContainerLow fill and outlineVariant hairline, so
            // the editing surface reads as the same container as the rows
            // it becomes once 完成.
            val cardBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            OutlinedTextField(
                value = draft,
                onValueChange = { value ->
                    if (emptyDisplay && !flippedFromEmpty) {
                        flippedFromEmpty = true
                        onToggleDisplayMode()
                    }
                    onDraftChange(value)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // The editor is the whole section body: it stretches to
                    // fill the leftover column height (like the display list
                    // does), so a large pasted list is visible at once and
                    // no space below the card goes to waste.
                    .weight(1f)
                    .focusRequester(fieldFocus),
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = cardBorder,
                    unfocusedBorderColor = cardBorder,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                placeholder = {
                    Text(
                        "在此粘贴或输入词表，每行一个词\n支持：词 | 词性 | 释义",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "共 $wordCount 词",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (emptyDisplay) onToggleDisplayMode()
                        onFillSample(SAMPLE_EN)
                    },
                ) { Text("英文示例") }
                TextButton(
                    onClick = {
                        if (emptyDisplay) onToggleDisplayMode()
                        onFillSample(SAMPLE_CJK)
                    },
                ) { Text("汉字示例") }
                TextButton(onClick = onClear) { Text("清空") }
            }
        }
    }
}

/** Small pill showing the parsed word count next to the section title. */
@Composable
private fun CountBadge(count: Int) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            "$count 词",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/**
 * 展示态 word list: index + word + 词性/释义 meta (2-line clamp, tap the row
 * again to expand) + a per-row delete button. The cursor row (起始词) is
 * tinted with a leading primary bar. Rows are keyed positionally — content
 * shifts with the draft, never remounting mid-typing (there is no typing
 * here; the textarea owns editing).
 */
@Composable
private fun WordDisplayList(
    draft: String,
    startIndex: Int,
    onStartIndexChange: (Int) -> Unit,
    onDeleteWord: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember(draft) { parseWordEntries(draft) }
    // Expansion resets with the draft; index bookkeeping on delete is not
    // worth it (alice shifts the set — same user-visible effect).
    var expanded by remember(draft) { mutableStateOf(emptySet<Int>()) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 72.dp)) {
            itemsIndexed(entries) { index, entry ->
                val isCursor = index == startIndex
                val meta = listOfNotNull(entry.pos, entry.meaning).joinToString(" ")
                // 2-line clamp: offer expansion for multi-sense glosses or
                // long text that would visibly truncate.
                val expandable = glossNeedsExpansion(entry.meaning)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .heightIn(min = 52.dp)
                        .background(
                            if (isCursor) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .selectable(
                            selected = isCursor,
                            role = Role.RadioButton,
                            onClick = {
                                onStartIndexChange(index)
                                if (expandable) {
                                    expanded = if (index in expanded) {
                                        expanded - index
                                    } else {
                                        expanded + index
                                    }
                                }
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(
                                if (isCursor) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                },
                            ),
                    )
                    Text(
                        text = (index + 1).toString().padStart(2, '0'),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isCursor) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .padding(start = 9.dp)
                            .width(28.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            text = entry.word,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isCursor) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (meta.isNotEmpty()) {
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (index in expanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    // Default IconButton size (48dp minimum touch target); the
                    // icon stays 20dp so the row keeps its visual density.
                    IconButton(onClick = { onDeleteWord(index) }) {
                        Icon(
                            Icons.Filled.Cancel,
                            contentDescription = "删除 ${entry.word}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                }
                if (index < entries.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}
