package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 多选词库 action bar for 抽词听写 (Roadmap #9), pinned to the bottom of both
 * library screens while selection mode is on: the ticked-list count, 退出多选
 * and the 抽词听写 entry (disabled with nothing ticked). The two screens are
 * separate destinations, so the bar is shared — the selection itself lives in
 * the process-scoped store both screens observe.
 */
@Composable
fun LibrarySelectionBar(
    selectedCount: Int,
    onStartDraw: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Edge-to-edge, IME not resizing the window: the bar owns the
            // navigation-bar and keyboard space so its buttons stay tappable.
            .navigationBarsPadding()
            .imePadding(),
    ) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "已选 $selectedCount 个词表",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onExit) { Text("退出多选") }
            Button(onClick = onStartDraw, enabled = selectedCount > 0) { Text("抽词听写") }
        }
    }
}
