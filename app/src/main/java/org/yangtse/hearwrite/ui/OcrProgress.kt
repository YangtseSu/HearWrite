package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.yangtse.hearwrite.data.OCR_PROGRESS_RECOGNIZING

/**
 * In-flight OCR progress with a way out, shared by every recognition surface
 * (首页 拍照识词, the dictation finish card's 拍照批改, the scan sheet).
 *
 * A vision call is a 30-second read-timeout round trip; before this strip every
 * entry point showed *some* spinner — the Home pill (14dp, squeezed into the
 * header row), the 批改 pane's LinearProgressIndicator — but neither offered a
 * cancel, so a wrong crop or a slow provider had to be waited out. It also
 * replaced the header pill for a second reason: that pill shared a row with the
 * wordmark and three 48dp icons, which on a 360dp screen left it ≈0dp wide
 * while the recognition was running — the only progress feedback in the app,
 * truncated to a few glyphs.
 *
 * The phase text sits in a Polite live region: 处理图片中… → 识别中… is a real
 * state change, and a screen reader user must hear it without re-reading the
 * screen. The 取消 button is a plain text button (no icon) so it stays legible
 * at any font scale.
 */
@Composable
fun OcrProgressStrip(
    phase: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = phase.ifEmpty { OCR_PROGRESS_RECOGNIZING },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}
