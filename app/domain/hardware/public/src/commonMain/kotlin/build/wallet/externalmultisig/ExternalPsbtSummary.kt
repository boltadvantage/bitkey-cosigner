package build.wallet.externalmultisig

/**
 * What the phone shows about an externally supplied PSBT before the user taps
 * the Bitkey.
 *
 * W1 hardware has no display, so it cannot show the user what it is signing and
 * cannot verify anything itself. The phone is the only surface where the
 * transaction can be seen at all — but the phone is also the online, untrusted
 * half of this setup. Showing the details here is therefore necessary but not
 * sufficient: the user has to compare them against Sparrow on the air-gapped
 * machine, which is the only place the transaction was built from a known
 * descriptor.
 *
 * That is why [VERIFICATION_WARNING] is not optional decoration. It is the only
 * thing standing between a tampered PSBT and a signature.
 *
 * Deliberately dependency-free so it can be exercised without BDK or a device;
 * the caller parses the PSBT and passes plain values in.
 */
data class ExternalPsbtSummary(
  val txid: String,
  val outputs: List<Output>,
  val inputCount: Int,
  /** Null when the PSBT does not carry enough information to compute a fee. */
  val feeSats: ULong?,
) {
  /**
   * A single destination.
   *
   * Note there is no `isChange` flag, and that is intentional. Identifying
   * change requires the wallet's output descriptor, which this app does not
   * have for an external multisig — the descriptor lives in Sparrow. Guessing
   * would be worse than not labelling: an output wrongly presented as "change"
   * is exactly how a user gets talked into signing away funds. Every output is
   * shown, unlabelled, and the user reconciles the list against Sparrow.
   */
  data class Output(
    /** Null when the scriptPubKey is not one we can render as an address. */
    val address: String?,
    val sats: ULong,
  )

  /** Sum of every output. */
  val totalOutSats: ULong get() = outputs.fold(0uL) { acc, o -> acc + o.sats }

  /** Total leaving the wallet: outputs plus fee. Null if the fee is unknown. */
  val totalSpentSats: ULong? get() = feeSats?.let { totalOutSats + it }

  /** True if any output's script could not be rendered as an address. */
  val hasUnrenderableOutput: Boolean get() = outputs.any { it.address == null }

  companion object {
    const val VERIFICATION_WARNING: String =
      "Your Bitkey has no screen and cannot verify this transaction. " +
        "It will sign whatever it is given. " +
        "Check every address and amount below against Sparrow on your offline " +
        "machine before you tap."

    /**
     * Shown when a script could not be decoded to an address. Refusing to sign
     * outright would be unhelpful — bare or non-standard scripts are legal —
     * but the user must not read a blank as "nothing there".
     */
    const val UNRENDERABLE_OUTPUT_LABEL: String = "Unrecognized output script"
  }
}
