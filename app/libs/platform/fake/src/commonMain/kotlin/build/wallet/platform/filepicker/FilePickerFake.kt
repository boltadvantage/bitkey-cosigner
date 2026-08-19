package build.wallet.platform.filepicker

import okio.ByteString

/** Returns [result] from every [pickFile] call; null models the user cancelling. */
class FilePickerFake(
  var result: FilePicker.PickedFile? = null,
) : FilePicker {
  var pickCallCount = 0
    private set

  var lastMimeTypes: List<String>? = null
    private set

  override suspend fun pickFile(mimeTypes: List<String>): FilePicker.PickedFile? {
    pickCallCount++
    lastMimeTypes = mimeTypes
    return result
  }

  fun returning(
    name: String?,
    content: ByteString,
  ) {
    result = FilePicker.PickedFile(name = name, content = content)
  }

  fun reset() {
    result = null
    pickCallCount = 0
    lastMimeTypes = null
  }
}
