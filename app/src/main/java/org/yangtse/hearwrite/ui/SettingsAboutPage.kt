package org.yangtse.hearwrite.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yangtse.hearwrite.R

/** Shipped copy of the repository's `LICENSE` (GPL-3.0), rendered in-app. */
private const val LICENSE_ASSET = "licenses/GPL-3.0.txt"

/**
 * 设置 → 关于: app identity, feature blurb, data/asset provenance and the
 * 项目主页 / 开源许可 actions. 开源许可 opens [LicensesSettingsPage], which
 * renders the bundled GPL-3.0 text inside the app instead of handing the user
 * off to a browser; 项目主页 stays the external GitHub link.
 */
@Composable
fun AboutSettingsPage(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                .versionName
        }.getOrNull().orEmpty()
    }

    SettingsSubPage(title = "关于", onBack = onBack) {
        // The sub-page shell installs this window's message channel, so the
        // 项目主页 failure below can report itself here.
        val messages = LocalMessages.current

        // A device with no browser (or none registered for https) resolves
        // nothing at all: swallowing that left the tap looking dead.
        fun openUrl(url: String) {
            val opened = runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            }.isSuccess
            if (!opened) messages.show("无法打开链接，请检查是否已安装浏览器")
        }

        // App identity header.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The launcher icon as the app logo (the tile colour is part of the
            // mark; values-night overrides it for 深色 mode).
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colorResource(R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = "应用图标",
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(16.dp))
            HearWriteWordmark(tileSize = 44.dp)
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
                    "导入词表或拍摄课本 → 逐词朗读并倒计时默写 → 标记错词、复习巩固。内置 10 套教材词库与课标字表，支持有道词典真人发音、" +
                        "系统语音与自定义 OpenAI 兼容音源。无账号、无广告、无内购，所有数据只保存在本机。",
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
                supporting = "GPL-3.0-or-later · 应用内全文",
                leading = {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailing = { SettingsChevronTrailing() },
                divider = false,
                onClick = onOpenLicenses,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 设置 → 关于 → 开源许可: the bundled GPL-3.0 text, read from the shipped
 * `assets/licenses/GPL-3.0.txt` off the main thread and rendered in the page's
 * own scroll container. A missing or unreadable asset degrades to a Chinese
 * message rather than an empty page or a crash.
 */
@Composable
fun LicensesSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var licenseText by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            licenseText = withContext(Dispatchers.IO) {
                context.assets.open(LICENSE_ASSET).bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Defensive asset boundary: a missing/unreadable license must
            // degrade to a Chinese message, never crash the page.
            failed = true
        }
    }

    SettingsSubPage(title = "开源许可", onBack = onBack) {
        val text = licenseText
        when {
            failed -> Text(
                "无法读取许可证文本，请到项目主页查看 LICENSE 文件。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
            text == null -> Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "正在加载许可证…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
