package org.yangtse.hearwrite.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * Camera + album launchers shared by the OCR entry points (首页 拍照识词 and
 * the dictation finish card's 拍照批改): one fixed cache file overwritten per
 * shot, handed to the system camera through FileProvider — which needs no
 * CAMERA permission as long as the app does not hold it — and the system
 * Photo Picker for album picks. Both paths are single-shot and report a
 * launch failure (no camera app, no picker) through [onError] instead of
 * crashing.
 */
class OcrImagePicker internal constructor(
    /** True while a picker/camera is open — disable re-entry until it returns. */
    val busy: Boolean,
    val launchCamera: () -> Unit,
    val launchGallery: () -> Unit,
)

/**
 * Remember the picker. [onPicked] receives every fresh Uri (cancelled picks
 * report nothing); [onError] gets the Chinese message of a failed launch.
 */
@Composable
fun rememberOcrImagePicker(
    onPicked: (Uri) -> Unit,
    onError: (String) -> Unit,
): OcrImagePicker {
    val context = LocalContext.current
    var pickerOpen by remember { mutableStateOf(false) }

    // One fixed cache file, overwritten per shot; FileProvider hands the
    // camera app a writable content Uri.
    val cameraFile = remember { File(context.cacheDir, "ocr/capture.jpg") }
    val cameraUri = remember {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", cameraFile)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        pickerOpen = false
        if (uri != null) onPicked(uri)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        pickerOpen = false
        if (ok) onPicked(cameraUri)
    }

    return OcrImagePicker(
        busy = pickerOpen,
        launchCamera = {
            pickerOpen = true
            cameraFile.parentFile?.mkdirs()
            runCatching { cameraLauncher.launch(cameraUri) }.onFailure {
                pickerOpen = false
                onError("无法启动相机")
            }
        },
        launchGallery = {
            pickerOpen = true
            runCatching {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }.onFailure {
                pickerOpen = false
                onError("无法打开相册")
            }
        },
    )
}
