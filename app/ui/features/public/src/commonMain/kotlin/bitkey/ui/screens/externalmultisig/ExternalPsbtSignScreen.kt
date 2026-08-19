package bitkey.ui.screens.externalmultisig

import androidx.compose.runtime.*
import bitkey.ui.framework.Navigator
import bitkey.ui.framework.Screen
import bitkey.ui.framework.ScreenPresenter
import build.wallet.analytics.events.screen.context.NfcEventTrackerScreenIdContext
import build.wallet.bdk.bindings.BdkAddressBuilder
import build.wallet.bdk.bindings.BdkPartiallySignedTransactionBuilder
import build.wallet.bitcoin.BitcoinNetworkType
import build.wallet.bitcoin.bdk.bdkNetwork
import build.wallet.compose.collections.immutableListOf
import build.wallet.compose.coroutines.rememberStableCoroutineScope
import build.wallet.di.ActivityScope
import build.wallet.di.BitkeyInject
import build.wallet.externalmultisig.ExternalPsbtLoader
import build.wallet.externalmultisig.ExternalPsbtSummary
import build.wallet.externalmultisig.PsbtBytes
import build.wallet.platform.data.MimeType
import build.wallet.platform.filepicker.FilePicker
import build.wallet.platform.sharing.SharingManager
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.core.ScreenPresentationStyle.Modal
import build.wallet.statemachine.core.form.FormBodyModel
import build.wallet.statemachine.core.form.FormHeaderModel
import build.wallet.statemachine.core.form.FormMainContentModel
import build.wallet.statemachine.nfc.NfcSessionUIStateMachine
import build.wallet.statemachine.nfc.NfcSessionUIStateMachineProps
import build.wallet.statemachine.nfc.NfcSessionUIStateMachineProps.HardwareVerification.NotRequired
import build.wallet.ui.model.StandardClick
import build.wallet.ui.model.button.ButtonModel
import build.wallet.ui.model.list.ListGroupModel
import build.wallet.ui.model.list.ListGroupStyle
import build.wallet.ui.model.list.ListItemModel
import build.wallet.ui.model.toolbar.ToolbarAccessoryModel.IconAccessory.Companion.CloseAccessory
import build.wallet.ui.model.toolbar.ToolbarModel
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlinx.coroutines.launch
import okio.ByteString.Companion.encodeUtf8

/**
 * Signs a PSBT that came from an external coordinator, with the Bitkey acting as
 * one leg of a multisig it knows nothing about.
 *
 * The whole flow is stateless: pick a file, show it, tap, hand the signed file
 * back. Nothing is persisted, so the app can be uninstalled the moment it is
 * done.
 *
 * The device cannot verify any of this — it has no screen and will sign whatever
 * hash it is handed. The details on this screen, and the warning above them, are
 * the only opportunity anyone gets to notice a tampered PSBT, and they are only
 * worth anything if checked against the coordinator that built it. See
 * [ExternalPsbtSummary].
 */
data class ExternalPsbtSignScreen(
  val network: BitcoinNetworkType,
  val accountIndex: UInt = 0u,
) : Screen

@BitkeyInject(ActivityScope::class)
class ExternalPsbtSignScreenPresenter(
  private val nfcSessionUIStateMachine: NfcSessionUIStateMachine,
  private val filePicker: FilePicker,
  private val sharingManager: SharingManager,
  private val psbtBuilder: BdkPartiallySignedTransactionBuilder,
  private val addressBuilder: BdkAddressBuilder,
) : ScreenPresenter<ExternalPsbtSignScreen> {
  @Composable
  override fun model(
    navigator: Navigator,
    screen: ExternalPsbtSignScreen,
  ): ScreenModel {
    val scope = rememberStableCoroutineScope()
    var uiState: State by remember { mutableStateOf(State.Intro()) }
    val loader = remember { ExternalPsbtLoader(psbtBuilder, addressBuilder) }

    fun pick() {
      scope.launch {
        val picked = filePicker.pickFile()
          ?: return@launch // User backed out; stay where we are.

        val base64 = PsbtBytes.toBase64(picked.content)
        if (base64 == null) {
          uiState = State.Intro(
            error = "That file isn't a PSBT. Pick the transaction file your " +
              "coordinator exported."
          )
          return@launch
        }

        val loaded = loader.load(base64, screen.network.bdkNetwork)
        val summary = loaded.get()
        uiState = if (summary != null) {
          State.Review(base64 = base64, summary = summary, fileName = picked.name)
        } else {
          State.Intro(error = loaded.getError()?.displayMessage)
        }
      }
    }

    return when (val state = uiState) {
      is State.Intro -> SignIntroBodyModel(
        error = state.error,
        onBack = { navigator.exit() },
        onPick = ::pick
      ).asModalScreen()

      is State.Review -> ReviewBodyModel(
        summary = state.summary,
        fileName = state.fileName,
        onBack = { uiState = State.Intro() },
        onSign = { uiState = State.Signing(state) }
      ).asModalScreen()

      is State.Signing -> nfcSessionUIStateMachine.model(
        NfcSessionUIStateMachineProps(
          session = { session, commands ->
            // Derive the cosigner key in the same tap purely to learn this
            // device's master fingerprint, which is what selects the inputs to
            // sign. Asking the user to carry it between screens, or storing it,
            // would both be worse: this app is meant to hold nothing.
            val key = commands.deriveExternalCosignerKey(
              session = session,
              network = screen.network,
              accountIndex = screen.accountIndex
            )
            commands.signExternalTransaction(
              session = session,
              psbtBase64 = state.review.base64,
              originFingerprint = key.key.origin.fingerprint
            )
          },
          onSuccess = { signed -> uiState = State.Signed(state.review, signed) },
          onCancel = { uiState = state.review },
          // Signing needs the device unlocked; deriving alone would not.
          needsAuthentication = true,
          hardwareVerification = NotRequired,
          screenPresentationStyle = Modal,
          eventTrackerContext = NfcEventTrackerScreenIdContext.SIGN_TRANSACTION
        )
      )

      is State.Signed -> SignedBodyModel(
        onSave = {
          sharingManager.shareFile(
            data = state.signedBase64.encodeUtf8(),
            mimeType = MimeType.TEXT_PLAIN,
            fileName = signedFileName(state.review.fileName),
            title = "Save signed transaction",
            completion = null
          )
        },
        onDone = { navigator.exit() }
      ).asModalScreen()
    }
  }

  /**
   * Names the output distinctly from the input. Overwriting the coordinator's
   * file on a shared drive would destroy the only unsigned copy if the signature
   * later turned out to be unusable.
   */
  private fun signedFileName(original: String?): String {
    val base = original?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "transaction"
    return "$base-signed.psbt"
  }

  private sealed interface State {
    data class Intro(val error: String? = null) : State

    data class Review(
      val base64: String,
      val summary: ExternalPsbtSummary,
      val fileName: String?,
    ) : State

    data class Signing(val review: Review) : State

    data class Signed(val review: Review, val signedBase64: String) : State
  }
}

private data class SignIntroBodyModel(
  val error: String?,
  override val onBack: () -> Unit,
  val onPick: () -> Unit,
) : FormBodyModel(
    id = ExternalMultisigScreenId.EXTERNAL_PSBT_SIGN_PICK,
    onBack = onBack,
    toolbar = ToolbarModel(leadingAccessory = CloseAccessory(onBack)),
    header = FormHeaderModel(
      headline = "Sign a transaction",
      subline = error
        ?: "Choose the transaction file your coordinator exported. You'll see " +
          "its details before anything is signed."
    ),
    primaryButton = ButtonModel(
      text = "Choose file",
      size = ButtonModel.Size.Footer,
      onClick = StandardClick(onPick)
    )
  )

private data class ReviewBodyModel(
  val summary: ExternalPsbtSummary,
  val fileName: String?,
  override val onBack: () -> Unit,
  val onSign: () -> Unit,
) : FormBodyModel(
    id = ExternalMultisigScreenId.EXTERNAL_PSBT_SIGN_REVIEW,
    onBack = onBack,
    toolbar = ToolbarModel(leadingAccessory = CloseAccessory(onBack)),
    header = FormHeaderModel(
      headline = "Check before signing",
      // The warning leads. It is the reason this screen exists.
      subline = ExternalPsbtSummary.VERIFICATION_WARNING
    ),
    mainContentList = immutableListOf(
      FormMainContentModel.ListGroup(
        listGroupModel = ListGroupModel(
          items = immutableListOf(
            *buildList {
              summary.outputs.forEachIndexed { index, output ->
                add(
                  ListItemModel(
                    title = output.address ?: ExternalPsbtSummary.UNRENDERABLE_OUTPUT_LABEL,
                    secondaryText = "Output ${index + 1}",
                    sideText = "${output.sats} sats",
                    enabled = true
                  )
                )
              }
              add(
                ListItemModel(
                  title = "Fee",
                  sideText = summary.feeSats?.let { "$it sats" } ?: "Unknown",
                  enabled = true
                )
              )
              add(
                ListItemModel(
                  title = "Total",
                  sideText = summary.totalSpentSats?.let { "$it sats" }
                    ?: "${summary.totalOutSats} sats + unknown fee",
                  enabled = true
                )
              )
              add(
                ListItemModel(
                  title = "Inputs",
                  sideText = summary.inputCount.toString(),
                  enabled = true
                )
              )
            }.toTypedArray()
          ),
          style = ListGroupStyle.DIVIDER
        )
      )
    ),
    primaryButton = ButtonModel(
      text = "Tap Bitkey to sign",
      size = ButtonModel.Size.Footer,
      onClick = StandardClick(onSign)
    )
  )

private data class SignedBodyModel(
  val onSave: () -> Unit,
  val onDone: () -> Unit,
) : FormBodyModel(
    id = ExternalMultisigScreenId.EXTERNAL_PSBT_SIGN_DONE,
    onBack = onDone,
    toolbar = ToolbarModel(leadingAccessory = CloseAccessory(onDone)),
    header = FormHeaderModel(
      headline = "Signed",
      subline = "Save the signed file back to your drive, then finish signing " +
        "with your other keys in your coordinator. This transaction is not " +
        "complete until it has enough signatures."
    ),
    primaryButton = ButtonModel(
      text = "Save signed transaction",
      size = ButtonModel.Size.Footer,
      onClick = StandardClick(onSave)
    ),
    secondaryButton = ButtonModel(
      text = "Done",
      treatment = ButtonModel.Treatment.Secondary,
      size = ButtonModel.Size.Footer,
      onClick = StandardClick(onDone)
    )
  )
