package org.yangtse.hearwrite.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri

/**
 * 设置 → 关于: app identity, feature blurb, data/asset provenance and the
 * 项目主页 / 开源许可 actions (opened in the system browser).
 */
@Composable
fun AboutSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                .versionName
        }.getOrNull().orEmpty()
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
    }

    SettingsSubPage(title = "关于", onBack = onBack) {
        // App identity header.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "听写",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("听写 HearWrite", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                if (versionName.isBlank()) {
                    "面向中国学生的中英文听写训练应用"
                } else {
                    "版本 $versionName"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSectionHeader("简介")
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "导入词表或拍摄课本 → 逐词朗读并倒计时默写 → 标记错词、复习巩固。内置 10 套教材词库，" +
                        "支持有道词典真人发音、系统语音与自定义 OpenAI 兼容音源。无账号、无广告、无内购，" +
                        "所有数据只保存在本机。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        SettingsSectionHeader("数据与许可")
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "内置词表来自教材整理；英文释义数据基于 ECDICT（MIT 许可）；组词与多音字数据由" +
                        "《现代汉语常用词表（草案）》生成；词表处理与发音逻辑整理自开源应用 alice。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        SettingsSectionHeader("更多")
        SettingsCard {
            SettingsRow(
                title = "项目主页",
                supporting = "GitHub：YangtseSu/HearWrite",
                leading = {
                    Icon(
                        Icons.Outlined.Code,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                divider = true,
                onClick = { openUrl("https://github.com/YangtseSu/HearWrite") },
            )
            SettingsRow(
                title = "开源许可",
                supporting = "GPL-3.0-or-later",
                leading = {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                divider = false,
                onClick = { openUrl("https://github.com/YangtseSu/HearWrite/blob/main/LICENSE") },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
