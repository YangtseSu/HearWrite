package org.yangtse.hearwrite.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.yangtse.hearwrite.HearWriteApplication

/** Top-level navigation routes. Finish (听写结束) is a DictationScreen end state, not a route. */
object Routes {
    const val HOME = "home"
    const val DICTATION = "dictation"
    const val SETTINGS = "settings"
    const val LIBRARY = "library"
    const val LIBRARY_LIST = "library_list/{category}"
    const val LIBRARY_PREVIEW = "library_preview/{category}/{label}"

    /** Route to one category's list screen; [category] is URL-encoded (Chinese names). */
    fun libraryList(category: String) = "library_list/${Uri.encode(category)}"

    /** Route to a list preview; both args URL-encoded (labels contain spaces). */
    fun libraryPreview(category: String, label: String) =
        "library_preview/${Uri.encode(category)}/${Uri.encode(label)}"
}

@Composable
fun HearWriteApp() {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as HearWriteApplication

    /** Stage the prepared lines (slice → shuffle already applied) and start. */
    val startDictation: (List<String>) -> Unit = { lines ->
        app.dictationSession.lines = lines
        navController.navigate(Routes.DICTATION)
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onStartDictation = startDictation,
                onOpenLibrary = { navController.navigate(Routes.LIBRARY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.DICTATION) {
            DictationScreen(onClose = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onClose = { navController.popBackStack() })
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenCategory = { category -> navController.navigate(Routes.libraryList(category)) },
                onOpenList = { category, label ->
                    navController.navigate(Routes.libraryPreview(category, label))
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LIBRARY_LIST) { entry ->
            val category = checkNotNull(entry.arguments?.getString("category"))
            LibraryListsScreen(
                onOpenList = { label ->
                    navController.navigate(Routes.libraryPreview(category, label))
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LIBRARY_PREVIEW) {
            LibraryPreviewScreen(
                onLoadToDraft = { lines ->
                    // Stage the import before leaving; HomeScreen consumes it
                    // on return and lands in 展示态 with the list loaded.
                    app.requestDraftImport(lines.joinToString("\n"))
                    navController.popBackStack(Routes.HOME, false)
                },
                onStartDictation = startDictation,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
