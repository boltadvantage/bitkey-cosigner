package bitkey.ui.screens.externalmultisig

import build.wallet.analytics.events.screen.id.EventTrackerScreenId

enum class ExternalMultisigScreenId : EventTrackerScreenId {
  /** Testing-only warning gate, shown before anything else. */
  EXTERNAL_MULTISIG_WARNING,

  /** Entry point for the external-multisig build. */
  EXTERNAL_MULTISIG_HOME,

  /** Explains what the cosigner export is, before any hardware tap. */
  EXTERNAL_COSIGNER_EXPORT_INTRO,

  /** Shows the derived cosigner key and offers to save it. */
  EXTERNAL_COSIGNER_EXPORT_RESULT,

  /** Prompts for the PSBT file to sign. */
  EXTERNAL_PSBT_SIGN_PICK,

  /** Shows the transaction details and the verification warning. */
  EXTERNAL_PSBT_SIGN_REVIEW,

  /** Offers to save the signed PSBT back out. */
  EXTERNAL_PSBT_SIGN_DONE,
}
