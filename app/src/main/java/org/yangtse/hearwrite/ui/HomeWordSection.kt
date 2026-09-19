package org.yangtse.hearwrite.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.yangtse.hearwrite.domain.WordRow
import org.yangtse.hearwrite.domain.glossNeedsExpansion
import org.yangtse.hearwrite.ui.theme.wordHead

/** 示例 content: English words with gloss columns (朗读释义 demo-able). */
private const val SAMPLE_EN = "apple | n. | 苹果\nbanana | n. | 香蕉\nschool | n. | 学校\nbook | n. | 书\ncar | n. | 汽车"

/** 示例 content: bare 汉字词语 (zh-CN dictation). */
private const val SAMPLE_CJK = "香蕉\n学校\n苹果\n月亮\n生日"

/** 示例 labels — also the keys the pending-sample confirm is saved under. */
private const val SAMPLE_EN_LABEL = "英文示例"
private const val SAMPLE_CJK_LABEL = "汉字示例"

/**
 * Length of the 编辑/展示 fade (AUDIT D1 动-2), matched to the dictation
 * stage's pane fade so the app has one motion temper rather than two.
 */
private const val SURFACE_FADE_MS = 200

/**
 * 单词列表 section (alice's WordInputSection + section header): the header row
 * carries the title, the parsed-count badge and the 编辑/完成 toggle; the body
 * is the parsed display list (展示态, non-empty) where tapping a row moves the
 * 起始词 and long glosses expand from their own trailing chevron, or the paste
 * textarea otherwise — both 编辑态 and an empty 展示态 look like the editor
 * (textarea + 示例 / 清空 footer), so starting from scratch is seamless;
 * typing on the empty 展示态 flips it to 编辑态 before the first change lands.
 * Entering 编辑态 focuses that textarea (the 完成/编辑 toggle is the only way
 * in). 清空 and the 示例 presets overwrite user text, so each confirms first
 * over a non-blank draft and fills straight away over a blank one; the count
 * is shown once, by the header badge. Deletion rewrites the draft line by line.
 */
@Composable
fun WordListSection(
    draft: String,
    /** 展示态 rows: the draft resolved against the offline lexicon. */
    rows: List<WordRow>,
    displayMode: Boolean,
    wordCount: Int,
    startIndex: Int,
    /** True until the persisted draft has been read (see HomeViewModel). */
    loading: Boolean,
    onDraftChange: (String) -> Unit,
    onToggleDisplayMode: () -> Unit,
    onStartIndexChange: (Int) -> Unit,
    onDeleteWord: (Int) -> Unit,
    onFillSample: (String) -> Unit,
    onClear: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fieldFocus = remember { FocusRequester() }
    // 编辑 must land on a field that is already focused with the keyboard up.
    // Keyed on the mode flipping into 编辑态 rather than on the textarea
    // appearing: over an empty draft the section renders that textarea in
    // 展示态 too, so "the editor is on screen" cannot distinguish the two and
    // 完成 would pop the IME back up over a list the user never asked to edit.
    // The flag starts at the mode of the first composition, so composing the
    // section in 编辑态 (already focused elsewhere, or restored state) is not
    // treated as entering it.
    var wasEditing by remember { mutableStateOf(!displayMode) }
    LaunchedEffect(displayMode) {
        if (!displayMode && !wasEditing) {
            fieldFocus.requestFocus()
        }
        wasEditing = !displayMode
    }
    // 清空/示例 replace whatever the user typed, so they ask first — except
    // over a draft that is blank, where the 示例 presets fill straight away
    // (清空 is disabled there: see the footer).
    //
    // Both are questions the user is mid-answer on, so they survive a
    // configuration change: a rotation used to dismiss 清空草稿？ without an
    // answer (the same defect as 结束听写？ on the dictation screen, AUDIT C6).
    // The sample is held by its label, which is all rememberSaveable can carry.
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var pendingSample by rememberSaveable { mutableStateOf<String?>(null) }
    // A 展示态 whose list is empty renders the editor, so filling it is the
    // sample preset's job and the mode must flip with it (see emptyDisplay use).
    val emptyDisplay = displayMode && wordCount == 0

    fun requestSample(
        label: String,
        sample: String,
    ) {
        if (draft.isBlank()) {
            if (emptyDisplay) onToggleDisplayMode()
            onFillSample(sample)
        } else {
            pendingSample = label
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
            TextButton(onClick = onToggleDisplayMode, enabled = !loading) {
                Icon(
                    if (displayMode) Icons.Filled.Edit else Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (displayMode) "编辑" else "完成")
            }
        }
        if (loading) {
            // The persisted draft is a DataStore round trip; until it lands the
            // section must not claim "共 0 词" over an empty editor (and 开始听写
            // in that window answered 请先输入单词列表 for a list about to
            // appear). One card-shaped placeholder keeps the section's geometry
            // so the real content does not jump in.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "正在读取草稿…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            return@Column
        }
        // The display list and the editor are deliberately the same card —
        // the editor is skinned with the list's fill, hairline and 16 dp
        // roundness so the two read as one container — so the mode switch
        // fades between them instead of cutting, which made it look like a
        // different surface arriving rather than the same one changing
        // shape (AUDIT D1 动-2). The header above and the confirms below
        // stay outside the fade; the editor brings its own footer inside
        // it so the card keeps its height on both sides.
        Crossfade(
            targetState = displayMode && wordCount > 0,
            animationSpec = tween(SURFACE_FADE_MS),
            label = "wordSectionBody",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { showList ->
            if (showList) {
                // The list fills the faded slot (the crossfade's box owns the
                // weight now); the editor below fills it too, so the card keeps
                // its height across the switch.
                WordDisplayList(
                    rows = rows,
                    startIndex = startIndex,
                    onStartIndexChange = onStartIndexChange,
                    onDeleteWord = onDeleteWord,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Editing surface: the real 编辑态, or 展示态 whose list is empty
                    // — an empty list keeps the editor's look (textarea + 示例/清空
                    // footer) so it is indistinguishable from editing. The first
                    // change made on that empty 展示态 flips the mode first: the
                    // header becomes 完成 and the parsed list never snaps in
                    // mid-keystroke; entering via the 编辑 button changes nothing but
                    // the header and focus (smooth by construction).
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
                        // Alignment spacer only: 共 N 词 already sits in the header's
                        // CountBadge right above this row, so repeating it here would
                        // state the same number twice in one view.
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { requestSample(SAMPLE_EN_LABEL, SAMPLE_EN) }) {
                            Text("英文示例")
                        }
                        TextButton(onClick = { requestSample(SAMPLE_CJK_LABEL, SAMPLE_CJK) }) {
                            Text("汉字示例")
                        }
                        // Pressable at 0 words this button only opened a confirm dialog
                        // for an already-empty draft — nothing to clear.
                        TextButton(onClick = { confirmClear = true }, enabled = wordCount > 0) {
                            Text("清空")
                        }
                    }
                    }
            }
        }

        // The two destructive footer actions ask first, because both throw away
        // text the user typed: the dialog names what is lost and colors 清空 /
        // 覆盖 as the app's other clear confirms do. A blank draft has nothing
        // to lose — the 示例 presets fill it straight away (and 清空 is disabled).
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("清空草稿？") },
                text = { Text("当前草稿将被清空，内容无法恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClear = false
                        onClear()
                    }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text("取消") }
                },
            )
        }
        pendingSample?.let { label ->
            val sample = if (label == SAMPLE_CJK_LABEL) SAMPLE_CJK else SAMPLE_EN
            AlertDialog(
                onDismissRequest = { pendingSample = null },
                title = { Text("覆盖当前草稿？") },
                text = { Text("将用${label}替换当前的草稿内容，原有内容不会保留。") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingSample = null
                        // Replacing an existing list keeps 展示态: the sample
                        // lands as the new parsed list, same as 完成 would show.
                        onFillSample(sample)
                    }) { Text("覆盖", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingSample = null }) { Text("取消") }
                },
            )
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
 * 展示态 word list: index + word + 词性/释义 meta (2-line clamp) + a per-row
 * delete button. A row tap does exactly one thing — it moves the 起始词
 * (the row's radio state, echoed by the panel's 从第 N 词开始). Long glosses
 * expand from their own trailing chevron, shown only when
 * [glossNeedsExpansion] says the text would truncate, so a tap on the row
 * never both reorders the listening and reshapes the row. The cursor row
 * (起始词) is tinted with a leading primary bar. Rows are keyed positionally —
 * content shifts with the list, never remounting mid-typing (there is no
 * typing here; the textarea owns editing). The rows arrive already resolved
 * (the draft itself stays as typed; HomeViewModel owns the lexicon pass).
 */
@Composable
private fun WordDisplayList(
    rows: List<WordRow>,
    startIndex: Int,
    onStartIndexChange: (Int) -> Unit,
    onDeleteWord: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Expansion resets with the list; index bookkeeping on delete is not
    // worth it (alice shifts the set — same user-visible effect).
    var expanded by remember(rows) { mutableStateOf(emptySet<Int>()) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        // No bottom slack: the section's column already yields the playback
        // panel's measured height (see HomeScreen), so extra trailing space
        // here only parked an unexplained gap under the last row.
        LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
            itemsIndexed(rows) { index, entry ->
                val isCursor = index == startIndex
                val meta = listOfNotNull(entry.pos, entry.gloss).joinToString(" ")
                // 2-line clamp: offer expansion for multi-sense glosses or
                // long text that would visibly truncate.
                val expandable = glossNeedsExpansion(entry.gloss)
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
                            onClick = { onStartIndexChange(index) },
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
                            text = entry.display,
                            style = MaterialTheme.typography.wordHead,
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
                    // Expansion has its own control so the row tap stays a
                    // single action (起始词). Rendered only for glosses the
                    // 2-line clamp would actually truncate: a chevron on every
                    // row would promise content that is not there. Same nested
                    // IconButton-in-selectable-row pattern as 删除 below.
                    if (expandable) {
                        IconButton(
                            onClick = {
                                expanded = if (index in expanded) {
                                    expanded - index
                                } else {
                                    expanded + index
                                }
                            },
                        ) {
                            Icon(
                                if (index in expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (index in expanded) "收起释义" else "展开释义",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    // Default IconButton size (48dp minimum touch target); the
                    // icon stays 20dp so the row keeps its visual density.
                    IconButton(onClick = { onDeleteWord(index) }) {
                        Icon(
                            Icons.Filled.Cancel,
                            contentDescription = "删除 ${entry.display}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                }
                if (index < rows.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}
