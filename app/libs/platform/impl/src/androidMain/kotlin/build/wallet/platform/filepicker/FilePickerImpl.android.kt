package build.wallet.platform.filepicker

import android.app.Activity
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toFile
import build.wallet.di.ActivityScope
import build.wallet.di.BitkeyInject
import build.wallet.logging.logWarn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.coroutines.resume

/**
 * Opens the system document picker via `ACTION_OPEN_DOCUMENT`.
 *
 * Registration uses the three-argument [androidx.activity.result.ActivityResultRegistry.register]
 * overload, which takes no [androidx.lifecycle.LifecycleOwner] and so may be
 * called at any point rather than only before the activity resumes. That keeps
 * this self-contained: no changes to the activity, and nothing added to the
 * manifest — which matters here, because an app that advertises itself as a
 * handler for a file type is announcing what it is for.
 *
 * The trade-off is that a registration without a lifecycle owner does not
 * survive process death mid-pick. The caller sees a cancellation and can retry,
 * which is the right outcome for a flow the user just initiated by hand.
 */
@BitkeyInject(ActivityScope::class)
class FilePickerImpl(
  private val activity: Activity,
) : FilePicker {
  override suspend fun pickFile(mimeTypes: List<String>): FilePicker.PickedFile? {
    val componentActivity = activity as? ComponentActivity ?: run {
      logWarn { "File picking requires a ComponentActivity." }
      return null
    }

    val uri = suspendCancellableCoroutine<Uri?> { continuation ->
      // A unique key per call: registrations are unregistered as soon as the
      // result lands, so reusing one across overlapping picks would collide.
      val key = "file-picker-${continuation.hashCode()}"
      var launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null

      launcher = componentActivity.activityResultRegistry.register(
        key,
        ActivityResultContracts.OpenDocument()
      ) { result: Uri? ->
        launcher?.unregister()
        if (continuation.isActive) continuation.resume(result)
      }

      continuation.invokeOnCancellation { launcher.unregister() }
      launcher.launch(mimeTypes.toTypedArray())
    } ?: return null

    return readUri(uri)
  }

  private suspend fun readUri(uri: Uri): FilePicker.PickedFile? =
    withContext(Dispatchers.IO) {
      val content: ByteString? = runCatching {
        activity.contentResolver.openInputStream(uri)?.use { it.readBytes().toByteString() }
      }.getOrElse { error ->
        // Removable media can disappear between the pick and the read, and the
        // permission grant is short-lived. Both are ordinary here.
        logWarn(throwable = error) { "Could not read the picked file." }
        null
      }

      content?.let { FilePicker.PickedFile(name = displayName(uri), content = it) }
    }

  private fun displayName(uri: Uri): String? =
    runCatching {
      activity.contentResolver
        .query(uri, null, null, null, null)
        ?.use { cursor ->
          val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
          if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: runCatching { uri.toFile().name }.getOrNull()
}
