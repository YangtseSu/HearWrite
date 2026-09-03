package org.yangtse.hearwrite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.yangtse.hearwrite.domain.ThemeMode
import org.yangtse.hearwrite.ui.HearWriteApp
import org.yangtse.hearwrite.ui.theme.HearWriteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 主题 setting (设置 → 外观): system/light/dark, persisted in DataStore.
            val app = applicationContext as HearWriteApplication
            val themeMode by app.settingsRepository.theme
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            HearWriteTheme(darkTheme = darkTheme) {
                HearWriteApp()
            }
        }
    }
}
