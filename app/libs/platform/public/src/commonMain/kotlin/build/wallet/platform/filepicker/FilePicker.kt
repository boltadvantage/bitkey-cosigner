package build.wallet.platform.filepicker

import okio.ByteString

/**
 * Opens the platform's document picker and reads back the chosen file.
 *
 * Reading is deliberately part of the same call. The platform hands back a
 * permission-scoped handle that is only valid for a short window, so a caller
 * that received a path and read it later would work in testing and fail in the
 * field.
 */
interface FilePicker {
  /**
   * Shows the picker and returns the chosen file, or null if the user backed out.
   *
   * @param mimeTypes MIME types to offer. Pass a wildcard type when the file's
   * extension is not reliably registered on the platform, which is the case
   * for `.psbt` — otherwise the picker greys the file out.
   */
  suspend fun pickFile(mimeTypes: List<String> = listOf("*/*")): PickedFile?

  data class PickedFile(
    /** Display name, when the platform reports one. Never trust it as a path. */
    val name: String?,
    val content: ByteString,
  )
}
