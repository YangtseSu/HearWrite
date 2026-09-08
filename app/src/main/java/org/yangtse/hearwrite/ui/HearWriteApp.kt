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
    const val SETTINGS_OCR = "settings_ocr"
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
                // 拍照识词 sheet 的 修改/去设置: one entry opened on the OCR
                // provider form. Back first lands on the in-screen hub
                // (goHub), then pops to Home — no second hub entry beneath,
                // which would show two consecutive identical hubs.
                onOpenOcrSettings = {
                    navController.navigate(Routes.SETTINGS_OCR)
                },
            )
        }
        composable(Routes.DICTATION) {
            // Every exit funnels through onClose (finish card, stop dialog,
            // back confirmation) — always land Home, whatever page launched
            // the session; the pop clears any library stack left behind.
            DictationScreen(onClose = {
                navController.popBackStack(Routes.HOME, false)
            })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onClose = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_OCR) {
            // The scan sheet's 修改/去设置 lands directly on the OCR provider
            // form; its in-screen back shows the hub, and the hub's close
            // pops straight to Home (no separate SETTINGS entry beneath).
            SettingsScreen(
                initialPage = SettingsSubPage.OCR_PROVIDER,
                onClose = { navController.popBackStack() },
            )
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
