package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.yangtse.hearwrite.domain.MAX_INTERVAL_SEC
import org.yangtse.hearwrite.domain.MAX_SPEECH_RATE
import org.yangtse.hearwrite.domain.MIN_INTERVAL_SEC
import org.yangtse.hearwrite.domain.MIN_SPEECH_RATE
import org.yangtse.hearwrite.domain.ThemeMode

/**
 * 设置 → 主题: radio choice list (跟随系统 / 浅色 / 深色), applied live.
 */
@Composable
fun ThemeSettingsPage(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    SettingsSubPage(title = "主题", onBack = onBack) {
        SettingsCard {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SettingsRadioRow(
                    title = themeLabel(mode),
                    selected = theme == mode,
                    divider = index < ThemeMode.entries.size - 1,
                    onClick = { viewModel.onThemeChange(mode) },
                )
            }
        }
    }
}

/**
 * 设置 → 语速 / 默认间隔: one slider per page with the live value beside the
 * label (Android-settings style). Changes apply immediately and persist.
 */
@Composable
fun SpeechRateSettingsPage(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val speechRate by viewModel.speechRate.collectAsStateWithLifecycle()
    SettingsSliderPage(
        title = "语速",
        valueLabel = "语速",
        valueText = formatRate(speechRate),
        caption = "同时影响英文与中文语音的朗读速度",
        value = speechRate,
        valueRange = MIN_SPEECH_RATE..MAX_SPEECH_RATE,
        steps = 9, // 0.1 steps → 11 stops incl. endpoints
        sliderDescription = "听写语速",
        onValueChange = viewModel::onSpeechRateChange,
        onBack = onBack,
    )
}

@Composable
fun IntervalSettingsPage(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val intervalSec by viewModel.intervalSec.collectAsStateWithLifecycle()
    SettingsSliderPage(
        title = "默认间隔",
        valueLabel = "默认间隔",
        valueText = formatInterval(intervalSec),
        caption = "每个词之间的等待秒数，听写页也可实时调整",
        value = intervalSec.toFloat(),
        valueRange = MIN_INTERVAL_SEC.toFloat()..MAX_INTERVAL_SEC.toFloat(),
        steps = 17, // 0.5 s steps
        sliderDescription = "默认听写间隔秒数",
        onValueChange = viewModel::onIntervalChange,
        onBack = onBack,
    )
}

/** Shared slider-page layout: label + current value, slider, caption. */
@Composable
private fun SettingsSliderPage(
    title: String,
    valueLabel: String,
    valueText: String,
    caption: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    sliderDescription: String,
    onValueChange: (Float) -> Unit,
    onBack: () -> Unit,
) {
    SettingsSubPage(title = title, onBack = onBack) {
        SettingsSectionHeader(title)
        SettingsCard {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        valueLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        valueText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = valueRange,
                    steps = steps,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = sliderDescription },
                )
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
