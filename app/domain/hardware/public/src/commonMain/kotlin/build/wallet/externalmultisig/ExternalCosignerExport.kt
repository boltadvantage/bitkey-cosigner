package build.wallet.externalmultisig

import build.wallet.bitcoin.keys.DescriptorPublicKey
import build.wallet.bitkey.hardware.HwSpendingPublicKey

/**
 * Renders the Bitkey's external cosigner key into files a coordinator such as
 * Sparrow can consume, so the key can be moved across an air gap on a USB drive
 * rather than transcribed by hand.
 *
 * Two artifacts are produced deliberately:
 *
 * - [toManualEntryText] is the fallback that cannot break. Every coordinator
 *   lets you type an xpub, a derivation path and a master fingerprint into a
 *   keystore by hand, and those three values are all this file contains.
 * - [toColdcardMultisigJson] is the convenience path — Coldcard's multisig
 *   export shape, which Sparrow imports directly.
 *
 * The JSON is best-effort: Coldcard emits SLIP-132 (`Zpub`) in the `p2wsh`
 * field where we emit a standard `xpub`, since re-encoding version bytes would
 * mean pulling base58 into this layer for no gain in the manual path. Sparrow is
 * generally tolerant of standard xpubs here, but if an import is ever rejected
 * the text file is the answer, not a bug to chase.
 */
object ExternalCosignerExport {
  /**
   * Base filename (no extension) for exports of [key], e.g. `bitkey-cosigner-A1B2C3D4`.
   *
   * Includes the fingerprint so multiple exports on one drive stay distinguishable
   * and a stale file is obvious at a glance.
   */
  fun fileBaseName(key: HwSpendingPublicKey): String = "bitkey-cosigner-${xfp(key)}"

  /**
   * Master fingerprint in the uppercase form coordinators conventionally display.
   */
  fun xfp(key: HwSpendingPublicKey): String = key.key.origin.fingerprint.uppercase()

  /**
   * Full origin derivation path in `m/`-prefixed form, e.g. `m/48'/0'/0'/2'`.
   *
   * [DescriptorPublicKey.Origin.derivationPath] is stored without the leading
   * `m`, and hardened steps may come back as `h` or `H` depending on the
   * producer; both are normalised to `'` because that is what coordinators show.
   */
  fun originPath(key: HwSpendingPublicKey): String {
    val path = key.key.origin.derivationPath.replace('h', '\'').replace('H', '\'')
    return if (path.startsWith("/")) "m$path" else "m/$path"
  }

  /**
   * Human-readable export. The three fields a keystore needs, plus the full
   * BIP380 key expression for coordinators that accept one directly.
   */
  fun toManualEntryText(key: HwSpendingPublicKey): String {
    val dpk = key.key
    return buildString {
      appendLine("Bitkey external multisig cosigner")
      appendLine("=================================")
      appendLine()
      appendLine("Enter these three values into the coordinator's keystore:")
      appendLine()
      appendLine("Master fingerprint : ${xfp(key)}")
      appendLine("Derivation path    : ${originPath(key)}")
      appendLine("xpub               : ${dpk.xpub}")
      appendLine()
      appendLine("Full key expression (BIP380):")
      appendLine(dpk.dpub)
      appendLine()
      appendLine("This is a PUBLIC key. It reveals your addresses to anyone who holds it,")
      appendLine("but it cannot spend. The private key never leaves the Bitkey hardware.")
      appendLine()
      appendLine("NOTE: this key has no seed backup. If the Bitkey device is lost or")
      appendLine("destroyed, this leg of the multisig cannot be restored from a phrase.")
      appendLine("Keep the wallet's full output descriptor backed up separately -- it is")
      appendLine("what makes the remaining signers able to recover the funds.")
    }
  }

  /**
   * Coldcard-style multisig export, which Sparrow imports as a keystore.
   */
  fun toColdcardMultisigJson(key: HwSpendingPublicKey): String {
    val dpk = key.key
    return buildString {
      appendLine("{")
      appendLine("""  "xfp": "${xfp(key)}",""")
      appendLine("""  "p2wsh_deriv": "${originPath(key)}",""")
      appendLine("""  "p2wsh": "${dpk.xpub}"""")
      append("}")
    }
  }
}
