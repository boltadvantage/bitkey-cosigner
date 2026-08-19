package build.wallet.platform.filepicker

import okio.ByteString

/** Records what would have been written; [result] models success or cancellation. */
class FileSaverFake(
  var result: Boolean = true,
) : FileSaver {
  data class Saved(
    val suggestedName: String,
    val mimeType: String,
    val content: ByteString,
  )

  val saved = mutableListOf<Saved>()

  override suspend fun saveFile(
    suggestedName: String,
    mimeType: String,
    content: ByteString,
  ): Boolean {
    saved.add(Saved(suggestedName, mimeType, content))
    return result
  }

  fun reset() {
    saved.clear()
    result = true
  }
}
