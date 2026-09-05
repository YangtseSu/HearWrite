package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.yangtse.hearwrite.data.OCR_DISCLAIMER
import org.yangtse.hearwrite.data.OcrLang

/**
 * 拍照识词 scan sheet (alice's camera sheet, minus the credits/paid-model
 * stack — BYOK only): recognition language tabs (英文/中文 pick the vision
 * prompt), a provider-config row (goes to 设置), the camera/album actions and
 * the AI disclaimer. Actions are disabled while an OCR run is in flight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrScanSheet(
    lang: OcrLang,
    onLangChange: (OcrLang) -> Unit,
    configured: Boolean,
    modelName: String,
    busy: Boolean,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text("拍照识词", style = MaterialTheme.typography.titleLarge)

            // ---- 识别语言 (picks the vision prompt) -------------------------
            Text(
                "识别语言",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
            )
            TabRow(selectedTabIndex = if (lang == OcrLang.ENGLISH) 0 else 1) {
                Tab(
                    selected = lang == OcrLang.ENGLISH,
                    onClick = { onLangChange(OcrLang.ENGLISH) },
                    text = { Text("英文") },
                )
                Tab(
                    selected = lang == OcrLang.CHINESE,
                    onClick = { onLangChange(OcrLang.CHINESE) },
                    text = { Text("中文") },
                )
            }

            // ---- OCR service row --------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (configured) "识别模型：$modelName" else "尚未配置 OCR 服务",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenSettings) {
                    Text(if (configured) "修改" else "去设置")
                }
            }
            if (!configured) {
                Text(
                    "拍照识词使用 OpenAI 兼容视觉接口，需在设置中填写自己的 API Key",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- disclaimer (OCR entry point) --------------------------------
            Text(
                OCR_DISCLAIMER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )

            // ---- actions ------------------------------------------------------
            Button(
                onClick = onCamera,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null)
                Text("拍照识词", modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(
                onClick = onGallery,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                Text("从相册选择", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
