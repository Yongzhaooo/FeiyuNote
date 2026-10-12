package com.feiyu.notes.ui

import com.feiyu.notes.R
import androidx.compose.ui.platform.LocalContext
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feiyu.notes.data.NotebookStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Translate the untouched preinstalled name without rewriting saved or user-edited templates. */
internal fun com.feiyu.notes.data.Template.displayName(context: android.content.Context): String =
    if (source == com.feiyu.notes.data.Template.BUILTIN_GUIDED && name in setOf("引导式讲解", "Guided explanation"))
        context.getString(R.string.guided_template_name) else name

internal fun com.feiyu.notes.data.Template.displayInstruction(context: android.content.Context): String =
    com.feiyu.notes.study.StudyPrompts.localizedTemplate(instruction,
        source == com.feiyu.notes.data.Template.BUILTIN_GUIDED, com.feiyu.notes.settings.AppLanguage.current(context))

/** Loads a value from the store and reloads it after every successful write. */
@Composable
fun <T> rememberStoreValue(store: NotebookStore, vararg keys: Any?, load: suspend NotebookStore.() -> T): State<T?> {
    val changes by store.changes.collectAsStateWithLifecycle()
    return produceState<T?>(null, changes, *keys) { value = store.load() }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String? = null, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm ?: context.getString(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.cancel)) } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    singleLine: Boolean = true,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = singleLine,
                    minLines = if (singleLine) 1 else 4,
                    modifier = Modifier.fillMaxWidth().testTag("text-input"),
                )
                extra()
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value.trim()); onDismiss() }) { Text(context.getString(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.cancel)) } },
    )
}

/** Downsampled thumbnail; the original file is never modified. */
@Composable
fun PhotoThumb(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) { decodeThumb(file) }
    }
    val image = bitmap
    if (image == null) {
        Text(if (file.isFile) context.getString(R.string.photo_loading) else context.getString(R.string.photo_missing), modifier)
    } else {
        Image(
            bitmap = image,
            contentDescription = context.getString(R.string.photo),
            contentScale = ContentScale.Fit,
            modifier = modifier.widthIn(max = 360.dp).heightIn(max = 280.dp),
        )
    }
}

private fun decodeThumb(file: File): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 720) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val degrees = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    val upright = if (degrees == 0f) bitmap
    else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    upright.asImageBitmap()
}.getOrNull()
