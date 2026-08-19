package bitkey.ui.screens.externalmultisig

import androidx.compose.runtime.Composable
import bitkey.ui.framework.Navigator
import bitkey.ui.framework.Screen
import bitkey.ui.framework.ScreenPresenter
import build.wallet.compose.collections.immutableListOf
import build.wallet.di.ActivityScope
import build.wallet.di.BitkeyInject
import build.wallet.statemachine.core.LabelModel
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.core.form.FormBodyModel
import build.wallet.statemachine.core.form.FormHeaderModel
import build.wallet.statemachine.core.form.FormMainContentModel
import build.wallet.ui.model.StandardClick
import build.wallet.ui.model.button.ButtonModel
import build.wallet.ui.model.callout.CalloutModel

/**
 * Gate shown before anything else, every launch.
 *
 * Deliberately not dismissible-once-and-remembered. This app stores nothing by
 * design, and a testing-only warning that can be permanently dismissed stops
 * being a warning after the first run — which is exactly when someone is most
 * likely to have forgotten it and started moving real money.
 *
 * There is no "skip" and no way past it except acknowledging.
 */
data object ExternalMultisigWarningScreen : Screen

@BitkeyInject(ActivityScope::class)
class ExternalMultisigWarningScreenPresenter :
  ScreenPresenter<ExternalMultisigWarningScreen> {
  @Composable
  override fun model(
    navigator: Navigator,
    screen: ExternalMultisigWarningScreen,
  ): ScreenModel =
    WarningBodyModel(
      onAcknowledge = { navigator.goTo(ExternalMultisigHomeScreen) }
    ).asRootScreen()
}

private data class WarningBodyModel(
  val onAcknowledge: () -> Unit,
) : FormBodyModel(
    id = ExternalMultisigScreenId.EXTERNAL_MULTISIG_WARNING,
    // No back action: there is nothing behind this screen, and offering an exit
    // would only invite tapping past it.
    onBack = null,
    toolbar = null,
    header = FormHeaderModel(
      headline = "Testing only",
      subline = "Read this before you use this app."
    ),
    mainContentList = immutableListOf(
      FormMainContentModel.Callout(
        item = CalloutModel(
          title = "DO NOT USE WITH REAL FUNDS",
          subtitle = LabelModel.StringModel(
            "This is unofficial, unaudited software that asks your Bitkey to " +
              "sign bitcoin transactions. It is not made or reviewed by Block."
          ),
          treatment = CalloutModel.Treatment.Danger
        )
      ),
      FormMainContentModel.Explainer(
        items = immutableListOf(
          FormMainContentModel.Explainer.Statement(
            title = "Use testnet only",
            body = LabelModel.StringModel(
              "Do not put real bitcoin into a wallet built with a key " +
              "exported by this app."
            )
          ),
          FormMainContentModel.Explainer.Statement(
            title = "Empty your Bitkey app too",
            body = LabelModel.StringModel(
              "Do not keep real bitcoin in your normal Bitkey wallet while " +
              "testing this. Both keys come from the same seed on the same " +
              "device, so a bug here is not neatly contained to the multisig."
            )
          ),
          FormMainContentModel.Explainer.Statement(
            title = "Your Bitkey cannot check what it signs",
            body = LabelModel.StringModel(
              "It has no screen. Whatever this app shows you is the only " +
              "chance to spot a bad transaction, and only if you compare it " +
              "against the wallet software that built it."
            )
          ),
          FormMainContentModel.Explainer.Statement(
            title = "There is no seed backup for this key",
            body = LabelModel.StringModel(
              "If the device is lost or destroyed, this signer cannot be " +
              "restored from a phrase. Back up your wallet's output descriptor " +
              "separately."
            )
          )
        )
      )
    ),
    primaryButton = ButtonModel(
      text = "I understand — testnet only",
      size = ButtonModel.Size.Footer,
      onClick = StandardClick(onAcknowledge)
    )
  )
