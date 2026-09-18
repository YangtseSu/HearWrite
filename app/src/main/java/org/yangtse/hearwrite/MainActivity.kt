package org.yangtse.hearwrite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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
            // 动态取色 (Material You): off by default, so the curated palette
            // only gives way when the user asks for it (设置 → 外观).
            val dynamicColor by app.settingsRepository.dynamicColor
                .collectAsStateWithLifecycle(initialValue = false)
            // System bar icons follow the app's own mode, not the system uiMode:
            // enableEdgeToEdge()'s default SystemBarStyle.auto resolves the icon
            // tint from `resources` once in onCreate, so 主题=深色 on a light
            // system painted dark icons onto the app's dark bars (invisible).
            // Edge-to-edge leaves both bars transparent, so the icons are the
            // only thing that has to track the theme.
            val view = LocalView.current
            SideEffect {
                window.isNavigationBarContrastEnforced = false
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            HearWriteTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                HearWriteApp()
            }
        }
    }
}
