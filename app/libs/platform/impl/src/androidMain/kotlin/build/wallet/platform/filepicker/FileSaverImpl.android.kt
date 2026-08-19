package build.wallet.platform.filepicker

import android.app.Activity
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import build.wallet.di.ActivityScope
import build.wallet.di.BitkeyInject
import build.wallet.logging.logWarn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.ByteString
import kotlin.coroutines.resume

/**
 * Writes files via `ACTION_CREATE_DOCUMENT`, so the user picks the destination
 * from the system document picker — which includes USB/OTG storage.
 *
 * Deliberately not the share sheet. Sharing only surfaces destinations that
 * other installed apps advertise, so whether a file can be written to a drive
 * depends on what else happens to be on the phone. That is a poor foundation for
 * a workflow whose entire point is moving files to and from removable media.
 *
 * Registration mirrors [FilePickerImpl]: the three-argument
 * [androidx.activity.result.ActivityResultRegistry.register] overload takes no
 * [androidx.lifecycle.LifecycleOwner], so it can be called on demand without
 * touching the activity or the manifest.
 */
@BitkeyInject(ActivityScope::class)
class FileSaverImpl(
  private val activity: Activity,
) : FileSaver {
  override suspend fun saveFile(
    suggestedName: String,
    mimeType: String,
    content: ByteString,
  ): Boolean {
    val componentActivity = activity as? ComponentActivity ?: run {
      logWarn { "Saving files requires a ComponentActivity." }
      return false
    }

    val uri = suspendCancellableCoroutine<Uri?> { continuation ->
      val key = "file-saver-${continuation.hashCode()}"
      var launcher: androidx.activity.result.ActivityResultLauncher<String>? = null

      launcher = componentActivity.activityResultRegistry.register(
        key,
        ActivityResultContracts.CreateDocument(mimeType)
      ) { result: Uri? ->
        launcher?.unregister()
        if (continuation.isActive) continuation.resume(result)
      }

      continuation.invokeOnCancellation { launcher.unregister() }
      launcher.launch(suggestedName)
    } ?: return false // User cancelled.

    return withContext(Dispatchers.IO) {
      runCatching {
        activity.contentResolver.openOutputStream(uri, "wt")?.use { output ->
          output.write(content.toByteArray())
          output.flush()
        } ?: error("Could not open $uri for writing")
        true
      }.getOrElse { error ->
        // Removable media can be pulled between choosing a location and writing
        // to it. Report failure rather than letting the caller assume success.
        logWarn(throwable = error) { "Could not write the chosen file." }
        false
      }
    }
  }
}
