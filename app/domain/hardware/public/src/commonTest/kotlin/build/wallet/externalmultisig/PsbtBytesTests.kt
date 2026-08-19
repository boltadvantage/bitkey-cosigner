package build.wallet.externalmultisig

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/** A minimal but structurally real PSBT: magic, one empty global map, one input. */
private val BINARY_PSBT = "70736274ff01000000000000".decodeHex()

class PsbtBytesTests : FunSpec({
  test("binary PSBT is encoded to base64") {
    val result = PsbtBytes.toBase64(BINARY_PSBT)
    result.shouldStartWith("cHNidP")
    result shouldBe BINARY_PSBT.base64()
  }

  test("base64 PSBT text is passed through unchanged") {
    val base64 = BINARY_PSBT.base64()
    PsbtBytes.toBase64(base64.encodeUtf8()) shouldBe base64
  }

  test("trailing newline on text form is tolerated") {
    // Sparrow, editors, and every file that crosses a machine boundary.
    val base64 = BINARY_PSBT.base64()
    PsbtBytes.toBase64("$base64\n".encodeUtf8()) shouldBe base64
  }

  test("surrounding whitespace on text form is tolerated") {
    val base64 = BINARY_PSBT.base64()
    PsbtBytes.toBase64("  $base64  \r\n".encodeUtf8()) shouldBe base64
  }

  test("round trip: file bytes we write are readable by our own reader") {
    // The output of a signing run becomes the input of the next one.
    val base64 = BINARY_PSBT.base64()
    val written = PsbtBytes.toFileBytes(base64)
    PsbtBytes.toBase64(written) shouldBe base64
  }

  test("written files are binary, matching what coordinators produce") {
    PsbtBytes.toFileBytes(BINARY_PSBT.base64()) shouldBe BINARY_PSBT
  }

  test("undecodable base64 falls back to text rather than writing nothing") {
    // Something recoverable by hand beats an empty file.
    val garbage = "not valid base64 !!!"
    PsbtBytes.toFileBytes(garbage) shouldBe garbage.encodeUtf8()
  }

  test("a non-PSBT file is rejected rather than guessed at") {
    // Picking the wrong file off a drive is routine, not exceptional.
    PsbtBytes.toBase64("hello world".encodeUtf8()) shouldBe null
  }

  test("arbitrary binary without the magic is rejected") {
    PsbtBytes.toBase64("deadbeefdeadbeef".decodeHex()) shouldBe null
  }

  test("empty input is rejected without throwing") {
    PsbtBytes.toBase64(ByteArray(0).toByteString()) shouldBe null
  }

  test("input shorter than the magic is rejected without throwing") {
    // Guards the length check in hasBinaryMagic against an index crash.
    PsbtBytes.toBase64("7073".decodeHex()) shouldBe null
  }

  test("a truncated magic is not mistaken for a PSBT") {
    // "psbt" without the 0xFF terminator.
    PsbtBytes.toBase64("70736274".decodeHex()) shouldBe null
  }

  test("invalid UTF-8 that is not binary PSBT is rejected without throwing") {
    // A JPEG, say. Must not blow up on the utf8() decode attempt.
    PsbtBytes.toBase64("ffd8ffe000104a464946".decodeHex()) shouldBe null
  }
})
