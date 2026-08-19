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
 * Coldcard itself emits SLIP-132 (`Zpub`) in the `p2wsh` field where we emit a
 * standard `xpub`. Verified against Sparrow: it accepts the standard `xpub`, so
 * no version-byte re-encoding is needed and base58 stays out of this layer.
 *
 * The import route is not obvious — in Sparrow the file must be imported via the
 * *Coldcard* keystore option specifically, not the generic airgapped-import
 * entry. [toManualEntryText] says so, because the person doing the import will
 * be standing at an offline machine with no way to look it up.
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
      appendLine("To import into Sparrow:")
      appendLine("  New Wallet -> Multi Signature -> select your quorum, then on this")
      appendLine("  cosigner's slot choose Coldcard -> Import file, and pick the .json")
      appendLine("  file next to this one. Sparrow reads the Coldcard export shape;")
      appendLine("  the generic airgapped-import entry will not accept it.")
      appendLine()
      appendLine("  Then confirm the keystore shows the fingerprint and derivation path")
      appendLine("  above. If either differs, fix it before going further -- a wrong path")
      appendLine("  silently derives a different wallet.")
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
