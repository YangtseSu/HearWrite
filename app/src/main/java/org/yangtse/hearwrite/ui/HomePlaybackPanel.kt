package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.yangtse.hearwrite.domain.MAX_INTERVAL_SEC
import org.yangtse.hearwrite.domain.MIN_INTERVAL_SEC

/**
 * Home bottom playback panel (alice's PlaybackControls): the 间隔 slider,
 * 自动播放 / 随机顺序 switches and the primary 开始听写 button. 间隔 and
 * 自动播放 persist to the DataStore keys shared with 设置 and the dictation
 * session; 随机顺序 is session-local. The button stays enabled at 0 words so
 * the screen can toast why nothing starts, and shows the enrich/record
 * spinner while [starting].
 */
@Composable
fun HomePlaybackPanel(
    intervalSec: Double,
    autoNext: Boolean,
    shuffle: Boolean,
    wordCount: Int,
    starting: Boolean,
    onIntervalChange: (Double) -> Unit,
    onAutoNextChange: (Boolean) -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "间隔",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = {
                        onIntervalChange(
                            (intervalSec - 0.5).coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC),
                        )
                    },
                    enabled = intervalSec > MIN_INTERVAL_SEC,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = "减少间隔")
                }
                Text(
                    String.format(Locale.ROOT, "%.1fs", intervalSec),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { contentDescription = "听写间隔秒数" },
                )
                IconButton(
                    onClick = {
                        onIntervalChange(
                            (intervalSec + 0.5).coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC),
                        )
                    },
                    enabled = intervalSec < MAX_INTERVAL_SEC,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "增加间隔")
                }
                Spacer(Modifier.weight(1f))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "自动播放",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = autoNext,
                        onCheckedChange = onAutoNextChange,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .semantics { contentDescription = "自动播放下一个词" },
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "随机顺序",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = shuffle,
                        onCheckedChange = onShuffleChange,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .semantics { contentDescription = "随机打乱词序" },
                    )
                }
            }

            Button(
                onClick = onStart,
                enabled = !starting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(52.dp),
            ) {
                if (starting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("整理词表…")
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始听写")
                    if (wordCount > 0) {
                        Spacer(Modifier.width(10.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f),
                        ) {
                            Text(
                                "$wordCount 词",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
