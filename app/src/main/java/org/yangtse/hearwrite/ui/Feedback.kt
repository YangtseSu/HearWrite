package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// The app's one-shot confirmation channel. Every 已保存 / 已删除 / 已载入 …
// message goes through a Snackbar anchored to the screen that raised it,
// instead of the fire-and-forget `Toast`s that used to float over the bottom of
// the window, outside the app's own surface: a Toast is not anchored, not
// tappable, not dismissable, and — being a system window — not in the semantics
// tree the accessibility services read.
//
// Window routing is the subtlety here. `ModalBottomSheet` and `AlertDialog` are
// `ComponentDialog`s: **separate windows** drawn above the screen, so a Snackbar
// hosted by the screen would animate behind their scrim and never be seen.
// [MessageHostScope] therefore installs a nested controller (CompositionLocal:
// innermost provider wins) and renders its host inside that window, so a message
// raised by a control inside a sheet stays in the sheet. The composition context
// is shared with the dialog, so the override reaches its content.

/**
 * Fire-and-forget confirmation messages for the current window. Obtained from
 * [LocalMessages] (or [rememberMessageController]); rendered by [MessageHost].
 * [show] is safe to call from any UI callback: it launches on the host's scope
 * and replaces any current message rather than queueing, so repeated taps cannot
 * build a backlog of stale confirmations.
 */
@Immutable
class MessageController internal constructor(
    internal val state: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    fun show(text: String) {
        scope.launch {
            state.currentSnackbarData?.dismiss()
            state.showSnackbar(
                message = text,
                withDismissAction = false,
                duration = SnackbarDuration.Short,
            )
        }
    }

    /**
     * A confirmation carrying one 撤销 action (a single-row delete: the rows
     * are removed on one tap with no confirm dialog, so the way back must live
     * in the message). The action form holds [SnackbarDuration.Long] so there
     * is time to take it back, and [onAction] runs only if it is pressed —
     * letting the message time out leaves the deletion standing.
     */
    fun show(text: String, actionLabel: String, onAction: () -> Unit) {
        scope.launch {
            state.currentSnackbarData?.dismiss()
            val result = state.showSnackbar(
                message = text,
                actionLabel = actionLabel,
                withDismissAction = false,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

/**
 * The message channel for the current window. No default: reading it without a
 * [MessageHost] or [MessageHostScope] above is a wiring bug — the message would
 * go nowhere — so it fails loudly, like every other programmer error.
 */
val LocalMessages = staticCompositionLocalOf<MessageController> {
    error("No MessageHost in scope: wrap the screen or sheet in MessageHost/MessageHostScope")
}

/** A controller to hand to [MessageHost]. */
@Composable
fun rememberMessageController(): MessageController {
    val scope = rememberCoroutineScope()
    return remember(scope) { MessageController(SnackbarHostState(), scope) }
}

/** Renders [controller]'s Snackbar where the message should appear. */
@Composable
fun MessageHost(
    controller: MessageController,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = controller.state, modifier = modifier)
}

/**
 * Provides a fresh message channel to [content] and renders its host in the same
 * window — the sheet/dialog counterpart of hosting at screen level, so a control
 * inside [content] gets its Snackbar inside the sheet rather than behind it.
 */
@Composable
fun MessageHostScope(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val controller = rememberMessageController()
    CompositionLocalProvider(LocalMessages provides controller) {
        Box(modifier = modifier.fillMaxWidth()) {
            content()
            MessageHost(controller, Modifier.align(Alignment.BottomCenter))
        }
    }
}
