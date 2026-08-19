package build.wallet.externalmultisig

import okio.ByteString

/**
 * Normalises a PSBT file's bytes into the base64 form the signing path expects.
 *
 * Coordinators disagree about what a `.psbt` file contains. Sparrow writes raw
 * binary by default; plenty of other tools (and anything that has passed through
 * a QR code, a clipboard or an email) write base64 text. A user moving files on a
 * USB drive has no reason to know or care which they have, so both are accepted.
 *
 * Detection keys off the BIP174 magic `0x70 0x73 0x62 0x74 0xFF` ("psbt" + 0xFF),
 * which every PSBT starts with. In base64 that same prefix renders as `cHNidP`,
 * so the two forms are distinguishable without guessing at encodings.
 */
object PsbtBytes {
  /** BIP174 magic: "psbt" followed by 0xFF. */
  private val MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte())

  /** The magic as it appears once base64-encoded. */
  private const val BASE64_MAGIC_PREFIX = "cHNidP"

  /**
   * Returns the PSBT as base64, or null if [bytes] is not a PSBT in either form.
   *
   * Returning null rather than throwing keeps the caller's error handling in one
   * place: a wrong file picked off a drive is an expected outcome here, not an
   * exceptional one.
   */
  fun toBase64(bytes: ByteString): String? {
    if (hasBinaryMagic(bytes)) return bytes.base64()

    // Text form. Tolerate the whitespace that files pick up in transit — a
    // trailing newline is close to universal for text written by a coordinator.
    val text = runCatching { bytes.utf8() }.getOrNull()?.trim() ?: return null
    return if (text.startsWith(BASE64_MAGIC_PREFIX)) text else null
  }

  private fun hasBinaryMagic(bytes: ByteString): Boolean {
    if (bytes.size < MAGIC.size) return false
    return MAGIC.indices.all { bytes[it] == MAGIC[it] }
  }
}
