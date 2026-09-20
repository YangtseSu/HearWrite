package org.yangtse.hearwrite.ui

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The **decode half** of the 选定识别区域 crop step, shared by 拍照识词
 * ([HomeViewModel]) and 拍照批改 ([DictationViewModel]): both pick or capture a
 * photo, show a spinner while the source is decoded off the main thread, and
 * hand the decoded [Bitmap] to their own confirmation path. The confirmation
 * halves differ — one replaces the draft with the recognized lines, the other
 * grades the answers — but the machinery below was character-for-character
 * identical in both screens apart from identifier names, and the 2026-09
 * audit's rule ("use the shared components, never hand-roll a second version")
 * is exactly what such a pair violates on the next change.
 *
 * It owns:
 *
 * - **the decode [Job] and the session id.** [start] bumps the id, so a decode
 *   that lands after a cancel or after a newer pick is *stale*: the coroutine
 *   that resumed with it recycles its own result instead of publishing it.
 *   [cancel] bumps too — job cancellation alone does not stop a coroutine that
 *   has already returned from the suspension point and is running its last
 *   statements, so the id (not the `Job`) is what makes "the result is dead"
 *   true.
 * - **[bitmap]/[loading]**, the public state the crop overlay renders.
 * - **exactly one `recycle()` per decoded bitmap.** Every path that drops the
 *   slot recycles what it drops: [start] the previous pick, [cancel]/[recycle]
 *   the one held, [start]'s stale branch its own result. [take] is the hand-off
 *   — the taken bitmap becomes the caller's to recycle exactly once (in its
 *   `finally`), so the caller never has to know whether the decode had landed.
 *
 * [decode] is the seam — `OcrService.decodeCropSource` in production, a fake in
 * tests, the same shape as [org.yangtse.hearwrite.data.LexiconRepository]'s
 * asset reader. It returns null on failure (never throws a failure into the
 * caller); the caller's `onError` renders the Chinese message, because the
 * wording belongs to its surface.
 */
class CropSessionHost(
    private val decode: suspend (Uri) -> Bitmap?,
) {

    private var job: Job? = null

    /** Bumped by [start]/[cancel]; a decode compares it when it lands. */
    private var session = 0

    private val _bitmap = MutableStateFlow<Bitmap?>(null)
    /** Decoded source shown in the crop overlay (decode in flight = null). */
    val bitmap: StateFlow<Bitmap?> = _bitmap.asStateFlow()

    private val _loading = MutableStateFlow(false)
    /** True while the picked image is being decoded for the crop overlay. */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * Drop whatever previous pick is held and decode [uri] in [scope]. The
     * cancel and the loading flag both happen **before** the first suspension
     * point, so a fast second pick replaces the first decode instead of
     * racing it. A null decode calls [onError] with the slot left empty — the
     * overlay closes itself.
     */
    fun start(scope: CoroutineScope, uri: Uri, onError: () -> Unit) {
        cancel()
        val active = ++session
        _loading.value = true
        job = scope.launch {
            val decoded = decode(uri)
            if (active != session) {
                decoded?.recycle()
                return@launch
            }
            _loading.value = false
            if (decoded == null) {
                onError()
            } else {
                _bitmap.value = decoded
            }
        }
    }

    /**
     * Leave the crop step without confirming: cancel the decode in flight,
     * invalidate a result it may still deliver, and recycle the decoded source.
     * [start] runs this before decoding the next pick.
     */
    fun cancel() {
        session++
        job?.cancel()
        job = null
        _loading.value = false
        _bitmap.value?.recycle()
        _bitmap.value = null
    }

    /**
     * Hand the decoded source to the confirmation path and clear the slot:
     * null when nothing has landed (the caller then has nothing to confirm).
     * The returned bitmap is the caller's to `recycle()` exactly once.
     */
    fun take(): Bitmap? {
        val taken = _bitmap.value ?: return null
        _bitmap.value = null
        return taken
    }

    /**
     * Claim-reclaim on teardown (`onCleared`) — [cancel], named for the call
     * site where the ViewModel goes away rather than the user dropping the crop.
     */
    fun recycle() = cancel()
}
