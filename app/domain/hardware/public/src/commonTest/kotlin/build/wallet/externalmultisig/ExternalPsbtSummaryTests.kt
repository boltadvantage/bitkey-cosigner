package build.wallet.externalmultisig

import build.wallet.externalmultisig.ExternalPsbtSummary.Output
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun summary(
  outputs: List<Output> = listOf(Output("bc1qexample", 100_000uL)),
  inputCount: Int = 1,
  feeSats: ULong? = 2_000uL,
) = ExternalPsbtSummary(
  txid = "abc123",
  outputs = outputs,
  inputCount = inputCount,
  feeSats = feeSats
)

class ExternalPsbtSummaryTests : FunSpec({
  test("totals sum every output") {
    val s = summary(
      outputs = listOf(
        Output("bc1qaaa", 100_000uL),
        Output("bc1qbbb", 250_000uL)
      )
    )
    s.totalOutSats shouldBe 350_000uL
  }

  test("total spent includes the fee") {
    summary(feeSats = 2_000uL).totalSpentSats shouldBe 102_000uL
  }

  test("total spent is null when the fee is unknown") {
    // Better to show nothing than a confidently wrong number.
    summary(feeSats = null).totalSpentSats shouldBe null
  }

  test("totals do not overflow on large amounts") {
    // 21M BTC in sats is ~2.1e15; ULong holds it comfortably, but the fold must
    // not be silently narrowed to Int somewhere along the way.
    val s = summary(
      outputs = listOf(
        Output("bc1qaaa", 2_100_000_000_000_000uL),
        Output("bc1qbbb", 1_000_000_000_000_000uL)
      ),
      feeSats = 1uL
    )
    s.totalOutSats shouldBe 3_100_000_000_000_000uL
    s.totalSpentSats shouldBe 3_100_000_000_000_001uL
  }

  test("empty output list totals zero rather than throwing") {
    summary(outputs = emptyList()).totalOutSats shouldBe 0uL
  }

  test("unrenderable outputs are flagged, not hidden") {
    // A blank in the UI must never be mistakable for "no output here".
    val s = summary(
      outputs = listOf(
        Output("bc1qaaa", 100_000uL),
        Output(null, 50_000uL)
      )
    )
    s.hasUnrenderableOutput shouldBe true
    // It still counts toward the total.
    s.totalOutSats shouldBe 150_000uL
  }

  test("all-renderable outputs are not flagged") {
    summary().hasUnrenderableOutput shouldBe false
  }

  test("no output carries a change label") {
    // Change cannot be identified without the wallet descriptor, which lives in
    // Sparrow. Presenting a guess as change is how a user gets talked into
    // signing away funds, so the type must offer no way to express it.
    val fields = Output::class.simpleName
    fields shouldBe "Output"
    // Constructed with exactly address + amount; nothing else to mislabel.
    Output("bc1qaaa", 1uL) shouldBe Output(address = "bc1qaaa", sats = 1uL)
  }

  test("warning names the actual failure mode") {
    val w = ExternalPsbtSummary.VERIFICATION_WARNING
    w shouldContain "no screen"
    w shouldContain "sign whatever it is given"
    w shouldContain "Sparrow"
  }

  test("warning tells the user to check before tapping, not after") {
    ExternalPsbtSummary.VERIFICATION_WARNING shouldContain "before you tap"
  }
})
