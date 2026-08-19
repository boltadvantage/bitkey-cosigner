package bitkey.ui.screens.externalmultisig

import build.wallet.analytics.events.screen.id.EventTrackerScreenId

enum class ExternalMultisigScreenId : EventTrackerScreenId {
  /** Explains what the cosigner export is, before any hardware tap. */
  EXTERNAL_COSIGNER_EXPORT_INTRO,

  /** Shows the derived cosigner key and offers to save it. */
  EXTERNAL_COSIGNER_EXPORT_RESULT,
}
