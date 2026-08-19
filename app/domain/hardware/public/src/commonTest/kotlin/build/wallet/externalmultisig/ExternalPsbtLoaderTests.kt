package build.wallet.externalmultisig

import build.wallet.bdk.bindings.BdkAddress
import build.wallet.bdk.bindings.BdkAddressBuilder
import build.wallet.bdk.bindings.BdkError
import build.wallet.bdk.bindings.BdkNetwork
import build.wallet.bdk.bindings.BdkPartiallySignedTransaction
import build.wallet.bdk.bindings.BdkPartiallySignedTransactionBuilder
import build.wallet.bdk.bindings.BdkResult
import build.wallet.bdk.bindings.BdkOutPoint
import build.wallet.bdk.bindings.BdkScript
import build.wallet.bdk.bindings.BdkTransaction
import build.wallet.bdk.bindings.BdkTxIn
import build.wallet.bdk.bindings.BdkTxOut
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import io.kotest.matchers.types.shouldBeInstanceOf

private object FakeScript : BdkScript {
  override val rawOutputScript: List<UByte> = emptyList()
}

private class FakeTx(
  private val outputs: List<BdkTxOut>,
  private val inputs: List<BdkTxIn> = emptyList(),
) : BdkTransaction {
  override fun txid() = "txid"

  override fun serialize(): List<UByte> = emptyList()

  override fun weight() = 0uL

  override fun size() = 0uL

  override fun vsize() = 0uL

  override fun input() = inputs

  override fun output() = outputs
}

private class FakePsbt(
  private val tx: BdkTransaction,
  private val fee: ULong?,
) : BdkPartiallySignedTransaction {
  override fun feeAmount() = fee

  override fun txid() = "psbt-txid"

  override fun serialize() = "base64"

  override fun extractTx() = tx
}

private class FakePsbtBuilder(
  private val result: BdkResult<BdkPartiallySignedTransaction>,
) : BdkPartiallySignedTransactionBuilder {
  var lastInput: String? = null

  override fun build(psbtBase64: String): BdkResult<BdkPartiallySignedTransaction> {
    lastInput = psbtBase64
    return result
  }
}

private class FakeAddress(private val value: String) : BdkAddress {
  override fun asString() = value

  override fun scriptPubkey() = FakeScript

  override fun network() = BdkNetwork.BITCOIN

  override fun isValidForNetwork(network: BdkNetwork) = true
}

/** Renders every script as [address], or fails them all when [address] is null. */
private class FakeAddressBuilder(private val address: String?) : BdkAddressBuilder {
  override fun build(address: String, bdkNetwork: BdkNetwork): BdkResult<BdkAddress> =
    BdkResult.Err(BdkError.Generic(null, null))

  override fun build(script: BdkScript, network: BdkNetwork): BdkResult<BdkAddress> =
    this.address?.let { BdkResult.Ok(FakeAddress(it)) }
      ?: BdkResult.Err(BdkError.Generic(null, null))
}

private fun txOut(sats: ULong) = BdkTxOut(value = sats, scriptPubkey = FakeScript)

private fun loader(
  psbt: BdkResult<BdkPartiallySignedTransaction>,
  address: String? = "bc1qexample",
) = ExternalPsbtLoader(FakePsbtBuilder(psbt), FakeAddressBuilder(address))

class ExternalPsbtLoaderTests : FunSpec({
  test("maps outputs, inputs and fee into the summary") {
    val psbt = FakePsbt(
      tx = FakeTx(
        outputs = listOf(txOut(100_000uL), txOut(50_000uL)),
        inputs = listOf(BdkTxIn(BdkOutPoint("prev", 0u), 0u, emptyList()))
      ),
      fee = 2_000uL
    )
    val result = loader(BdkResult.Ok(psbt)).load("base64", BdkNetwork.BITCOIN)

    val summary = result.get().shouldNotBeNull()
    summary.outputs.size shouldBe 2
    summary.totalOutSats shouldBe 150_000uL
    summary.inputCount shouldBe 1
    summary.feeSats shouldBe 2_000uL
  }

  test("a malformed PSBT surfaces as an error, not an exception") {
    val result = loader(BdkResult.Err(BdkError.Psbt(null, "bad")))
      .load("not-a-psbt", BdkNetwork.BITCOIN)
    result.getError().shouldBeInstanceOf<ExternalPsbtLoader.LoadError.NotAPsbt>()
  }

  test("unknown fee stays null rather than becoming zero") {
    // A fee rendered as 0 would look like a free transaction; the UI needs to be
    // able to say "unknown" instead.
    val psbt = FakePsbt(FakeTx(listOf(txOut(100_000uL))), fee = null)
    val summary = loader(BdkResult.Ok(psbt)).load("base64", BdkNetwork.BITCOIN)
      .get().shouldNotBeNull()
    summary.feeSats shouldBe null
    summary.totalSpentSats shouldBe null
  }

  test("undecodable scripts become unrenderable outputs, never dropped rows") {
    val psbt = FakePsbt(FakeTx(listOf(txOut(100_000uL), txOut(7uL))), fee = 1uL)
    val summary = loader(BdkResult.Ok(psbt), address = null)
      .load("base64", BdkNetwork.BITCOIN).get().shouldNotBeNull()

    summary.outputs.size shouldBe 2
    summary.hasUnrenderableOutput shouldBe true
    // Amounts still count, so the total cannot silently understate the spend.
    summary.totalOutSats shouldBe 100_007uL
  }

  test("whitespace around the base64 is tolerated") {
    // Text files copied across machines routinely pick up a trailing newline.
    val psbt = FakePsbt(FakeTx(listOf(txOut(1uL))), fee = 1uL)
    val builder = FakePsbtBuilder(BdkResult.Ok(psbt))
    ExternalPsbtLoader(builder, FakeAddressBuilder("bc1q"))
      .load("  cHNidP8B\n", BdkNetwork.BITCOIN)
    builder.lastInput shouldBe "cHNidP8B"
  }

  test("network is passed through to address rendering") {
    // Rendering testnet scripts with mainnet HRP would show addresses that look
    // plausible and are wrong.
    val psbt = FakePsbt(FakeTx(listOf(txOut(1uL))), fee = 1uL)
    var seen: BdkNetwork? = null
    val addresses = object : BdkAddressBuilder {
      override fun build(address: String, bdkNetwork: BdkNetwork): BdkResult<BdkAddress> =
        BdkResult.Err(BdkError.Generic(null, null))

      override fun build(script: BdkScript, network: BdkNetwork): BdkResult<BdkAddress> {
        seen = network
        return BdkResult.Ok(FakeAddress("tb1q"))
      }
    }
    ExternalPsbtLoader(FakePsbtBuilder(BdkResult.Ok(psbt)), addresses)
      .load("base64", BdkNetwork.TESTNET)
    seen shouldBe BdkNetwork.TESTNET
  }

  test("a PSBT with no outputs is rejected with a reason, not silently accepted") {
    // Nothing could meaningfully be signed, and a screen showing an empty output
    // list with a Sign button is worse than an explicit refusal.
    val psbt = FakePsbt(FakeTx(emptyList()), fee = null)
    loader(BdkResult.Ok(psbt)).load("base64", BdkNetwork.BITCOIN)
      .getError().shouldBeInstanceOf<ExternalPsbtLoader.LoadError.NoOutputs>()
  }

  test("every load error carries a message fit to show the user") {
    // These surface at an air-gapped machine where there is nothing to look up.
    listOf(ExternalPsbtLoader.LoadError.NotAPsbt, ExternalPsbtLoader.LoadError.NoOutputs)
      .forEach { it.displayMessage.isNotBlank() shouldBe true }
  }
})
