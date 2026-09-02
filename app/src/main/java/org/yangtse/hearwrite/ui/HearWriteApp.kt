package org.yangtse.hearwrite.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/** Top-level navigation routes. Finish (听写结束) is a DictationScreen end state, not a route. */
object Routes {
    const val HOME = "home"
    const val DICTATION = "dictation"
    const val SETTINGS = "settings"
}

@Composable
fun HearWriteApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onStartDictation = { navController.navigate(Routes.DICTATION) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.DICTATION) {
            DictationScreen(onClose = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onClose = { navController.popBackStack() })
        }
    }
}
