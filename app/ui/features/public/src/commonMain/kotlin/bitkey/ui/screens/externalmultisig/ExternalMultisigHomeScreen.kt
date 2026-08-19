package bitkey.ui.screens.externalmultisig

import androidx.compose.runtime.*
import bitkey.ui.framework.Navigator
import bitkey.ui.framework.Screen
import bitkey.ui.framework.ScreenPresenter
import build.wallet.bitcoin.BitcoinNetworkType
import build.wallet.compose.collections.immutableListOf
import build.wallet.di.ActivityScope
import build.wallet.di.BitkeyInject
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.core.form.FormBodyModel
import build.wallet.statemachine.core.form.FormHeaderModel
import build.wallet.statemachine.core.form.FormMainContentModel
import build.wallet.ui.model.StandardClick
import build.wallet.ui.model.button.ButtonModel
import build.wallet.ui.model.list.ListGroupModel
import build.wallet.ui.model.list.ListGroupStyle
import build.wallet.ui.model.list.ListItemModel
import build.wallet.statemachine.core.LabelModel
import build.wallet.ui.model.callout.CalloutModel
import build.wallet.ui.model.list.ListItemAccessory

/**
 * Entry point for the external-multisig build.
 *
 * This fork exists only to let the Bitkey act as a cosigner in a wallet managed
 * elsewhere. It has no account, creates none, and stores nothing — so it opens
 * straight here rather than into onboarding.
 *
 * Network is chosen per-run rather than configured, because there is no
 * persistent state to configure it in. Testnet is offered first deliberately:
 * the first end-to-end run of a new signing setup should not be for real money.
 */
data object ExternalMultisigHomeScreen : Screen

@BitkeyInject(ActivityScope::class)
class ExternalMultisigHomeScreenPresenter : ScreenPresenter<ExternalMultisigHomeScreen> {
  @Composable
  override fun model(
    navigator: Navigator,
    screen: ExternalMultisigHomeScreen,
  ): ScreenModel {
    var network: BitcoinNetworkType by remember { mutableStateOf(BitcoinNetworkType.TESTNET) }

    return ExternalMultisigHomeBodyModel(
      network = network,
      onToggleNetwork = {
        network = when (network) {
          BitcoinNetworkType.TESTNET -> BitcoinNetworkType.BITCOIN
          else -> BitcoinNetworkType.TESTNET
        }
      },
      onExport = { navigator.goTo(ExternalCosignerExportScreen(network = network)) },
      onSign = { navigator.goTo(ExternalPsbtSignScreen(network = network)) }
    ).asRootScreen()
  }
}

private data class ExternalMultisigHomeBodyModel(
  val network: BitcoinNetworkType,
  val onToggleNetwork: () -> Unit,
  val onExport: () -> Unit,
  val onSign: () -> Unit,
) : FormBodyModel(
    id = ExternalMultisigScreenId.EXTERNAL_MULTISIG_HOME,
    onBack = null,
    toolbar = null,
    header = FormHeaderModel(
      headline = "Bitkey cosigner",
      subline = "Use your Bitkey as one signer in a multisig wallet managed by " +
        "another coordinator. Nothing is stored on this phone."
    ),
    mainContentList = immutableListOf(
      // First thing on the screen, every launch. This is unreviewed, unaudited
      // software that asks a hardware wallet to sign things; anyone running it
      // should be told plainly before they touch a single control.
      FormMainContentModel.Callout(
        item = CalloutModel(
          title = "TESTING ONLY — DO NOT USE WITH REAL FUNDS",
          subtitle = LabelModel.StringModel(
            "This is unofficial, unaudited software. Use testnet only.\n\n" +
              "Do not put real bitcoin in a wallet built with this key, and do " +
              "not keep real bitcoin in your Bitkey app while testing it. Both " +
              "keys come from the same device.\n\n" +
              "Treat any coin it touches as money you are willing to lose."
          ),
          treatment = CalloutModel.Treatment.Danger
        )
      ),
      FormMainContentModel.ListGroup(
        listGroupModel = ListGroupModel(
          items = immutableListOf(
            ListItemModel(
              title = "Network",
              secondaryText = when (network) {
                BitcoinNetworkType.BITCOIN -> "Mainnet — real funds"
                else -> "Testnet — no real value"
              },
              sideText = network.name,
              onClick = onToggleNetwork,
              enabled = true,
              trailingAccessory = ListItemAccessory.drillIcon()
            )
          ),
          style = ListGroupStyle.DIVIDER
        )
      )
    ),
    primaryButton = ButtonModel(
      text = "Sign a transaction",
      size = ButtonModel.Size.Footer,
      onClick = StandardClick(onSign)
    ),
    secondaryButton = ButtonModel(
      text = "Export cosigner key",
      treatment = ButtonModel.Treatment.Secondary,
      size = ButtonModel.Size.Footer,
      onClick = StandardClick(onExport)
    )
  )
