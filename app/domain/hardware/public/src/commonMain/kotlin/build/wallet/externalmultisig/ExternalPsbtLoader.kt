package build.wallet.externalmultisig

import build.wallet.bdk.bindings.BdkAddressBuilder
import build.wallet.bdk.bindings.BdkNetwork
import build.wallet.bdk.bindings.BdkPartiallySignedTransactionBuilder
import build.wallet.bdk.bindings.BdkResult
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

/**
 * Parses an externally supplied PSBT — one this app did not build, arriving from
 * a USB drive — into the [ExternalPsbtSummary] shown before signing.
 *
 * Everything here is deliberately read-only and total: any malformed input has
 * to produce a [LoadError] rather than an exception or a partial summary. The
 * file being parsed came from outside the app's control, which is the whole
 * reason this class exists separately from the app's own PSBT handling.
 */
class ExternalPsbtLoader(
  private val psbtBuilder: BdkPartiallySignedTransactionBuilder,
  private val addressBuilder: BdkAddressBuilder,
) {
  sealed class LoadError(val displayMessage: String) {
    /** The file was not a PSBT at all — wrong file picked, or truncated copy. */
    data object NotAPsbt : LoadError(
      "This file isn't a valid PSBT. Check you picked the right file, and that " +
        "it copied to the drive completely."
    )

    /** Parsed, but carries no outputs. Nothing meaningful could be signed. */
    data object NoOutputs : LoadError(
      "This PSBT has no outputs. There is nothing to sign."
    )
  }

  fun load(
    psbtBase64: String,
    network: BdkNetwork,
  ): Result<ExternalPsbtSummary, LoadError> {
    val psbt = when (val built = psbtBuilder.build(psbtBase64.trim())) {
      is BdkResult.Ok -> built.value
      is BdkResult.Err -> return Err(LoadError.NotAPsbt)
    }

    val tx = psbt.extractTx()
    val txOutputs = tx.output()
    if (txOutputs.isEmpty()) return Err(LoadError.NoOutputs)

    val outputs = txOutputs.map { txOut ->
      // A script we cannot render is shown as unrenderable rather than dropped:
      // a missing row would read as "no output here", which is exactly the
      // misreading that matters on a signing screen.
      val address = when (val built = addressBuilder.build(txOut.scriptPubkey, network)) {
        is BdkResult.Ok -> built.value.asString()
        is BdkResult.Err -> null
      }
      ExternalPsbtSummary.Output(address = address, sats = txOut.value)
    }

    return Ok(
      ExternalPsbtSummary(
        txid = psbt.txid(),
        outputs = outputs,
        inputCount = tx.input().size,
        // BDK reports null when the PSBT lacks the UTXO data needed to compute a
        // fee. Passed through as null so the UI can say "unknown" rather than
        // display a confidently wrong number.
        feeSats = psbt.feeAmount()
      )
    )
  }
}
