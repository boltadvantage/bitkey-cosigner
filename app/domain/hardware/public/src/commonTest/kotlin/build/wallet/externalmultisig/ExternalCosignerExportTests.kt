package build.wallet.externalmultisig

import build.wallet.bitkey.hardware.HwSpendingPublicKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private const val XPUB =
  "xpub6ERApfZwUNrhLCkDtcHTcxd75RbzS1ed54G1LkBUHQVHQKqhMkhgbmJbZRkrgZw4koxb5JaHWkY4ALHY2gr" +
    "BGRjaDMzQLcgJvLJuZZvRcEL"

/** As the device returns it: BIP48 origin, apostrophe hardening. */
private fun key(origin: String = "[a1b2c3d4/48'/0'/0'/2']") =
  HwSpendingPublicKey("$origin$XPUB/*")

class ExternalCosignerExportTests : FunSpec({
  test("fingerprint is uppercased for display") {
    ExternalCosignerExport.xfp(key()) shouldBe "A1B2C3D4"
  }

  test("origin path is m-prefixed") {
    ExternalCosignerExport.originPath(key()) shouldBe "m/48'/0'/0'/2'"
  }

  test("h and H hardening notations normalise to apostrophes") {
    // Producers vary; coordinators display apostrophes, so both must converge.
    ExternalCosignerExport.originPath(key("[a1b2c3d4/48h/0h/0h/2h]")) shouldBe "m/48'/0'/0'/2'"
    ExternalCosignerExport.originPath(key("[a1b2c3d4/48H/0H/0H/2H]")) shouldBe "m/48'/0'/0'/2'"
  }

  test("filename carries the fingerprint so stale exports are obvious") {
    ExternalCosignerExport.fileBaseName(key()) shouldBe "bitkey-cosigner-A1B2C3D4"
  }

  test("manual entry text carries the three keystore fields") {
    val text = ExternalCosignerExport.toManualEntryText(key())
    text shouldContain "A1B2C3D4"
    text shouldContain "m/48'/0'/0'/2'"
    text shouldContain XPUB
  }

  test("manual entry text states the key cannot spend") {
    // This file is meant to be moved on a USB stick and left lying around.
    // Anyone reading it should know what it is and is not.
    ExternalCosignerExport.toManualEntryText(key()) shouldContain "cannot spend"
  }

  test("manual entry text warns there is no seed backup") {
    // The single most consequential property of this setup, and the one most
    // likely to be forgotten by the time it matters.
    val text = ExternalCosignerExport.toManualEntryText(key())
    text shouldContain "no seed backup"
    text shouldContain "output descriptor"
  }

  test("manual entry text names the Coldcard import route") {
    // Verified against Sparrow: the generic airgapped-import entry rejects this
    // shape; it has to go in via the Coldcard keystore option. Whoever does the
    // import is standing at an offline machine and cannot look that up.
    val text = ExternalCosignerExport.toManualEntryText(key())
    text shouldContain "Coldcard"
    text shouldContain "Import file"
  }

  test("manual entry text tells the user to verify the path after import") {
    ExternalCosignerExport.toManualEntryText(key()) shouldContain "silently derives a different wallet"
  }

  test("coldcard json has the fields Sparrow reads") {
    val json = ExternalCosignerExport.toColdcardMultisigJson(key())
    json shouldContain """"xfp": "A1B2C3D4""""
    json shouldContain """"p2wsh_deriv": "m/48'/0'/0'/2'""""
    json shouldContain """"p2wsh": "$XPUB""""
  }

  test("coldcard json is syntactically well formed") {
    val json = ExternalCosignerExport.toColdcardMultisigJson(key())
    json.trim().startsWith("{") shouldBe true
    json.trim().endsWith("}") shouldBe true
    // Three fields, so exactly two separating commas.
    json.count { it == ',' } shouldBe 2
  }

  test("export reflects a non-zero account index rather than assuming zero") {
    val third = key("[a1b2c3d4/48'/0'/3'/2']")
    ExternalCosignerExport.originPath(third) shouldBe "m/48'/0'/3'/2'"
    ExternalCosignerExport.toColdcardMultisigJson(third) shouldContain "m/48'/0'/3'/2'"
  }

  test("testnet coin type survives export") {
    val testnet = key("[a1b2c3d4/48'/1'/0'/2']")
    ExternalCosignerExport.originPath(testnet) shouldBe "m/48'/1'/0'/2'"
  }
})
