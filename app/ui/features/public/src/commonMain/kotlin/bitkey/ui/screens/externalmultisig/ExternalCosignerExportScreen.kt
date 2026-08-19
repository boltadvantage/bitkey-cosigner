package bitkey.ui.screens.externalmultisig

import androidx.compose.runtime.*
import bitkey.ui.framework.Navigator
import bitkey.ui.framework.Screen
import bitkey.ui.framework.ScreenPresenter
import build.wallet.analytics.events.screen.context.NfcEventTrackerScreenIdContext
import build.wallet.bitcoin.BitcoinNetworkType
import build.wallet.bitkey.hardware.HwSpendingPublicKey
import build.wallet.compose.collections.immutableListOf
import build.wallet.di.ActivityScope
import build.wallet.di.BitkeyInject
import build.wallet.externalmultisig.ExternalCosignerExport
import build.wallet.compose.coroutines.rememberStableCoroutineScope
import build.wallet.platform.data.MimeType
import build.wallet.platform.filepicker.FileSaver
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.core.form.FormBodyModel
import build.wallet.statemachine.core.form.FormHeaderModel
import build.wallet.statemachine.core.form.FormMainContentModel
import build.wallet.statemachine.nfc.NfcSessionUIStateMachine
import build.wallet.statemachine.nfc.NfcSessionUIStateMachineProps
import build.wallet.statemachine.nfc.NfcSessionUIStateMachineProps.HardwareVerification.NotRequired
import build.wallet.statemachine.core.ScreenPresentationStyle.Modal
import build.wallet.ui.model.StandardClick
import build.wallet.ui.model.button.ButtonModel
import build.wallet.ui.model.list.ListGroupModel
import build.wallet.ui.model.list.ListGroupStyle
import build.wallet.ui.model.list.ListItemModel
import build.wallet.ui.model.toolbar.ToolbarAccessoryModel.IconAccessory.Companion.CloseAccessory
import build.wallet.ui.model.toolbar.ToolbarModel
import kotlinx.coroutines.launch
import okio.ByteString.Companion.encodeUtf8

/**
 * Derives the Bitkey's BIP48 external-multisig cosigner key and hands it to the
 * user as files a coordinator can import.
 *
 * Holds no state between uses: the key is re-derived from the hardware on every
 * run, so the app can be installed, used and uninstalled without losing
 * anything. That is deliberate — see [ExternalCosignerExport].
 *
 * @param accountIndex the BIP48 account. Defaults to 0; distinct indices give
 * distinct cosigner keys for separate multisig wallets.
 */
data class ExternalCosignerExportScreen(
  val network: BitcoinNetworkType,
  val accountIndex: UInt = 0u,
) : Screen

@BitkeyInject(ActivityScope::class)
class ExternalCosignerExportScreenPresenter(
  private val nfcSessionUIStateMachine: NfcSessionUIStateMachine,
  private val fileSaver: FileSaver,
) : ScreenPresenter<ExternalCosignerExportScreen> {
  @Composable
  override fun model(
    navigator: Navigator,
    screen: ExternalCosignerExportScreen,
  ): ScreenModel {
    val scope = rememberStableCoroutineScope()
    var uiState: State by remember { mutableStateOf(State.Intro) }

    return when (val state = uiState) {
      State.Intro -> ExportIntroBodyModel(
        onBack = { navigator.goTo(ExternalMultisigHomeScreen) },
        onExport = { uiState = State.Tapping }
      ).asModalScreen()

      State.Tapping -> nfcSessionUIStateMachine.model(
        NfcSessionUIStateMachineProps(
          session = { session, commands ->
            commands.deriveExternalCosignerKey(
              session = session,
              network = screen.network,
              accountIndex = screen.accountIndex
            )
          },
          onSuccess = { key -> uiState = State.Exported(key) },
          onCancel = { uiState = State.Intro },
          // Required: firmware lists IPC_PROTO_DERIVE_KEY_DESCRIPTOR_CMD under its
          // authenticated commands, so the device answers UNAUTHENTICATED unless it
          // has been unlocked first. Reading a public key intuitively should not
          // need a fingerprint, but the hardware disagrees, and the resulting
          // failure surfaces only as a generic NFC error.
          needsAuthentication = true,
          // This app deliberately has no account to check the hardware against.
          hardwareVerification = NotRequired,
          screenPresentationStyle = Modal,
          eventTrackerContext = NfcEventTrackerScreenIdContext.METADATA
        )
      )

      is State.Exported -> ExportResultBodyModel(
        fingerprint = ExternalCosignerExport.xfp(state.key),
        derivationPath = ExternalCosignerExport.originPath(state.key),
        xpub = state.key.key.xpub,
        onSaveJson = {
          scope.launch {
            fileSaver.saveFile(
              suggestedName = "${ExternalCosignerExport.fileBaseName(state.key)}.json",
              mimeType = MimeType.JSON.name,
              content = ExternalCosignerExport.toColdcardMultisigJson(state.key).encodeUtf8()
            )
          }
        },
        onSaveText = {
          scope.launch {
            fileSaver.saveFile(
              suggestedName = "${ExternalCosignerExport.fileBaseName(state.key)}.txt",
              mimeType = MimeType.TEXT_PLAIN.name,
              content = ExternalCosignerExport.toManualEntryText(state.key).encodeUtf8()
            )
          }
        },
        onDone = { navigator.goTo(ExternalMultisigHomeScreen) }
      ).asModalScreen()
    }
  }

  private sealed interface State {
    data object Intro : State

    data object Tapping : State

    data class Exported(val key: HwSpendingPublicKey) : State
  }
}

private data class ExportIntroBodyModel(
  override val onBack: () -> Unit,
  val onExport: () -> Unit,
) : FormBodyModel(
    id = ExternalMultisigScreenId.EXTERNAL_COSIGNER_EXPORT_INTRO,
    onBack = onBack,
    toolbar = ToolbarModel(leadingAccessory = CloseAccessory(onBack)),
    header = FormHeaderModel(
      headline = "Export cosigner key",
      subline = "Tap your Bitkey to read its public key for an external multisig " +
        "wallet. Save the files to a USB drive and import them into your " +
        "coordinator on your offline machine.\n\n" +
        "This exports a public key only. It cannot spend, and nothing is " +
        "stored on this phone."
    ),
    primaryButton = ButtonModel(
      text = "Tap Bitkey to export",
      size = ButtonModel.Size.Footer,
      onClick = StandardClick(onExport)
    )
  )

private data class ExportResultBodyModel(
  val fingerprint: String,
  val derivationPath: String,
  val xpub: String,
  val onSaveJson: () -> Unit,
  val onSaveText: () -> Unit,
  val onDone: () -> Unit,
) : FormBodyModel(
    id = ExternalMultisigScreenId.EXTERNAL_COSIGNER_EXPORT_RESULT,
    onBack = onDone,
    toolbar = ToolbarModel(leadingAccessory = CloseAccessory(onDone)),
    header = FormHeaderModel(
      headline = "Cosigner key",
      subline = "Save both files to your USB drive. Import the .json into your " +
        "coordinator; keep the .txt as a readable fallback and a record of " +
        "the derivation path."
    ),
    mainContentList = immutableListOf(
      FormMainContentModel.ListGroup(
        listGroupModel = ListGroupModel(
          items = immutableListOf(
            ListItemModel(title = "Master fingerprint", sideText = fingerprint, enabled = true),
            ListItemModel(title = "Derivation path", sideText = derivationPath, enabled = true),
            // Shown truncated by the row; the files carry it in full.
            ListItemModel(title = "xpub", sideText = xpub, enabled = true)
          ),
          style = ListGroupStyle.DIVIDER
        )
      )
    ),
    primaryButton = ButtonModel(
      text = "Save .json for coordinator",
      size = ButtonModel.Size.Footer,
      onClick = StandardClick(onSaveJson)
    ),
    secondaryButton = ButtonModel(
      text = "Save .txt fallback",
      treatment = ButtonModel.Treatment.Secondary,
      size = ButtonModel.Size.Footer,
      onClick = StandardClick(onSaveText)
    )
  )
