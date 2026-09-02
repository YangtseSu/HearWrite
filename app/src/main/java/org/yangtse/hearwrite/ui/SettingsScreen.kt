package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.yangtse.hearwrite.domain.MAX_SPEECH_RATE
import org.yangtse.hearwrite.domain.MIN_SPEECH_RATE
import org.yangtse.hearwrite.domain.TtsSource
import java.util.Locale

/**
 * Playback settings: 语速 (system TTS rate, applied live), 朗读释义 and the
 * TTS source (有道词典/系统语音). Interval/auto-next are adjusted on the
 * dictation screen itself; Phase 10 consolidates the remaining settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val speechRate by viewModel.speechRate.collectAsStateWithLifecycle()
    val readTranslation by viewModel.readTranslation.collectAsStateWithLifecycle()
    val ttsSource by viewModel.ttsSource.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                "听写",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            HorizontalDivider()

            // ---- 语速 ------------------------------------------------------
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("语速", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        String.format(Locale.ROOT, "%.1f", speechRate),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = speechRate,
                    onValueChange = viewModel::onSpeechRateChange,
                    valueRange = MIN_SPEECH_RATE..MAX_SPEECH_RATE,
                    steps = 9, // 0.1 steps → 11 stops incl. endpoints
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "听写语速" },
                )
            }

            // ---- 朗读释义 ---------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("朗读释义", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "英文词朗读后跟读中文释义",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = readTranslation,
                    onCheckedChange = viewModel::onReadTranslationChange,
                    modifier = Modifier.semantics { contentDescription = "朗读释义" },
                )
            }

            Text(
                "语音",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            HorizontalDivider()

            // ---- 发音来源 ---------------------------------------------------
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text("发音来源", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "有道词典为真人词典发音，需要网络；断网或失败时自动改用系统语音",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = ttsSource == TtsSource.YOUDAO,
                        onClick = { viewModel.onTtsSourceChange(TtsSource.YOUDAO) },
                        label = { Text("有道词典") },
                    )
                    FilterChip(
                        selected = ttsSource == TtsSource.SYSTEM,
                        onClick = { viewModel.onTtsSourceChange(TtsSource.SYSTEM) },
                        label = { Text("系统语音") },
                    )
                }
            }

            Text(
                "间隔与自动播报可在听写页调整",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}
