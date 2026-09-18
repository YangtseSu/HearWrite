package org.yangtse.hearwrite.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.yangtse.hearwrite.HearWriteApplication
import org.yangtse.hearwrite.domain.builtinListId
import org.yangtse.hearwrite.domain.WordRow

/** Top-level navigation routes. Finish (听写结束) is a DictationScreen end state, not a route. */
object Routes {
    const val HOME = "home"
    const val DICTATION = "dictation"
    const val SETTINGS = "settings"
    const val SETTINGS_OCR = "settings_ocr"
    const val LIBRARY = "library"
    const val STATS = "stats"
    const val LIBRARY_LIST = "library_list/{category}"
    const val LIBRARY_PREVIEW = "library_preview/{category}/{label}"
    const val LIBRARY_DRAW = "library_draw"

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

    /** Stage the prepared session (rows + provenance) and start dictation. */
    val startSession: (List<WordRow>, String?) -> Unit = { rows, sourceLabel ->
        app.dictationSession.stage(rows, sourceLabel)
        navController.navigate(Routes.DICTATION) { launchSingleTop = true }
    }

    // Every push is single-top: a double tap used to stack the screen twice,
    // so one 返回 left the user on the same page. LIBRARY_DRAW already did
    // this; STATS / SETTINGS / LIBRARY / DICTATION did not — and neither did
    // the parameterized destinations (library_list / library_preview), which
    // are equally reachable by a fast double tap on a category card or a list
    // row. One helper, so a new destination cannot forget it.
    val openTop: (String) -> Unit = { route ->
        navController.navigate(route) { launchSingleTop = true }
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onStartDictation = startSession,
                onOpenLibrary = { openTop(Routes.LIBRARY) },
                onOpenLibraryPreview = { category, label ->
                    // 错词本 source jump: lands on the list's preview directly.
                    openTop(Routes.libraryPreview(category, label))
                },
                onOpenSettings = { openTop(Routes.SETTINGS) },
                onOpenStats = { openTop(Routes.STATS) },
                // 拍照识词 sheet 的 修改/去设置: one entry opened on the OCR
                // provider form. Back first lands on the in-screen hub
                // (goHub), then pops to Home — no second hub entry beneath,
                // which would show two consecutive identical hubs.
                onOpenOcrSettings = { openTop(Routes.SETTINGS_OCR) },
            )
        }
        composable(Routes.DICTATION) {
            // Every exit funnels through onClose (finish card, stop dialog,
            // back confirmation) — always land Home, whatever page launched
            // the session; the pop clears any library stack left behind.
            DictationScreen(
                onClose = { navController.popBackStack(Routes.HOME, false) },
                // The finish card's 听写统计 follows the same landing rule: the
                // dictation entry is spent (a stopped run records nothing and
                // `DictationSessionStore.take()` is one-shot), so it is cleared
                // with the library stack and 听写统计 opens above Home. One
                // navigate with popUpTo, not pop-then-push: two calls are two
                // stack mutations that can be observed in between.
                onOpenStats = {
                    navController.navigate(Routes.STATS) {
                        popUpTo(Routes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onClose = { navController.popBackStack() })
        }
        composable(Routes.STATS) {
            StatsScreen(
                onBack = { navController.popBackStack() },
                // The record rows jump back to the list a run came from, the
                // same destination the 错词本 drawer's 查看词表 opens.
                onOpenLibraryPreview = { category, label ->
                    openTop(Routes.libraryPreview(category, label))
                },
            )
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
                onOpenCategory = { category -> openTop(Routes.libraryList(category)) },
                onOpenList = { category, label ->
                    openTop(Routes.libraryPreview(category, label))
                },
                onOpenDraw = { openTop(Routes.LIBRARY_DRAW) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LIBRARY_LIST) { entry ->
            val category = checkNotNull(entry.arguments?.getString("category"))
            LibraryListsScreen(
                onOpenList = { label ->
                    openTop(Routes.libraryPreview(category, label))
                },
                onOpenDraw = { openTop(Routes.LIBRARY_DRAW) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LIBRARY_DRAW) {
            LibraryDrawScreen(
                onStartDictation = { rows, sourceLabel ->
                    // The drawn session is a normal run: staged rows + the
                    // multi-list provenance for its 错词本 marks (Roadmap #9).
                    app.librarySelection.setActive(false)
                    startSession(rows, sourceLabel)
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LIBRARY_PREVIEW) { entry ->
            // The preview knows which built-in list it shows; wrong marks of
            // a run started here carry that list's id (Roadmap #1 source).
            val previewSource = builtinListId(
                checkNotNull(entry.arguments?.getString("category")),
                checkNotNull(entry.arguments?.getString("label")),
            )
            LibraryPreviewScreen(
                onLoadToDraft = { lines ->
                    // Stage only — do NOT navigate. The list is handed to
                    // Home's draft and lands in the editor on the next Home
                    // entry (the bus is consumed there), so the user keeps
                    // their place in the 词库 instead of being thrown back to
                    // Home and having to re-walk four taps (B8). The preview
                    // screen confirms in place; navigating here would destroy
                    // that confirmation with the screen.
                    app.requestDraftImport(lines.joinToString("\n"))
                },
                onStartDictation = { rows ->
                    startSession(rows, previewSource)
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
