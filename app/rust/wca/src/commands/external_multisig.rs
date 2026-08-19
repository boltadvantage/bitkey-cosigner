//! Support for using the Bitkey hardware key as a cosigner in an externally
//! managed multisig wallet (e.g. a Sparrow 2-of-3 alongside a Trezor and a Jade).
//!
//! # Why this is a separate command
//!
//! W1 hardware is a fully generic BIP32 signer: `derive_key_descriptor_and_sign`
//! signs an arbitrary 32-byte hash at an arbitrary derivation path, and the
//! firmware signing policy is compiled out (`policy_init(.., SECURE_FALSE)` in
//! `key_manager_task.c`). The device has no display, so it cannot show the user
//! what it is about to sign. In the stock app that is safe, because the only
//! PSBTs ever presented come from the user's own Bitkey wallet.
//!
//! Once we accept PSBTs from an external source that assumption is gone. A
//! malicious or malformed PSBT could carry `bip32_derivation` entries pointing
//! at the *Bitkey wallet's own* keys and the hardware would sign them blind.
//!
//! # The guard
//!
//! Bitkey's own spending keys live under BIP84 (`m/84'/coin'/account'`), derived
//! by `generate_keys::bip84_path`. External cosigner keys live under BIP48
//! script-type 2 (`m/48'/coin'/account'/2'`), the standard path for P2WSH
//! multisig and the default Sparrow uses.
//!
//! These two subtrees are disjoint at the purpose level, so the guard is a
//! whitelist rather than a threshold: every input this command signs must derive
//! under `48'`. No number of Bitkey account bumps can ever reach `m/48'`, so
//! there is no collision to reason about.
//!
//! The guard fails closed — if *any* signable input in the PSBT is outside the
//! external subtree, the whole operation is rejected rather than partially
//! signed.

use bitcoin::{
    bip32::{ChildNumber, DerivationPath, Fingerprint},
    psbt::Psbt as PartiallySignedTransaction,
};
use next_gen::prelude::*;

use crate::{
    command_interface::command, errors::CommandError, fwpb::BtcNetwork, yield_from_,
    SpendingKeyResult,
};

use super::generate_keys::derive;
use super::sign_transaction::sign_transaction;

/// BIP48: multi-signature hierarchy for deterministic wallets.
pub const EXTERNAL_COSIGNER_PURPOSE: u32 = 48;

/// BIP48 script type 2 = P2WSH (native segwit multisig).
pub const EXTERNAL_COSIGNER_SCRIPT_TYPE: u32 = 2;

fn coin_type(network: BtcNetwork) -> u32 {
    match network {
        BtcNetwork::Bitcoin => 0,
        _ => 1,
    }
}

/// The derivation path for an external multisig cosigner key:
/// `m/48'/coin'/account'/2'`.
pub fn external_cosigner_path(
    network: BtcNetwork,
    account_index: u32,
) -> Result<DerivationPath, CommandError> {
    let path = [
        ChildNumber::from_hardened_idx(EXTERNAL_COSIGNER_PURPOSE),
        ChildNumber::from_hardened_idx(coin_type(network)),
        ChildNumber::from_hardened_idx(account_index),
        ChildNumber::from_hardened_idx(EXTERNAL_COSIGNER_SCRIPT_TYPE),
    ]
    .into_iter()
    .collect::<Result<Vec<_>, _>>()
    .map_err(|_| CommandError::InvalidArguments)?;

    Ok(path.as_slice().into())
}

/// True if `path` descends from `m/48'` — i.e. belongs to the external
/// cosigner subtree rather than the Bitkey wallet's own BIP84 tree.
fn is_external_cosigner_path(path: &DerivationPath) -> bool {
    matches!(
        path.into_iter().next(),
        Some(ChildNumber::Hardened {
            index: EXTERNAL_COSIGNER_PURPOSE
        })
    )
}

/// Reject the PSBT unless every input we would sign derives under `m/48'`.
///
/// Only entries matching `origin_fingerprint` are considered: other cosigners'
/// keys (the Trezor, the Jade) are none of our business and are ignored.
///
/// Fails closed — one bad input rejects the entire PSBT.
pub fn enforce_external_cosigner_only(
    psbt: &PartiallySignedTransaction,
    origin_fingerprint: Fingerprint,
) -> Result<(), CommandError> {
    for input in psbt.inputs.iter() {
        for (fingerprint, derivation_path) in input.bip32_derivation.values() {
            if *fingerprint != origin_fingerprint {
                continue;
            }
            if !is_external_cosigner_path(derivation_path) {
                return Err(CommandError::ExternalCosignerPathRejected);
            }
        }
    }

    Ok(())
}

/// Derive the external cosigner key at `m/48'/coin'/account'/2'`.
///
/// Returns a descriptor public key string carrying origin fingerprint,
/// derivation path and xpub — everything Sparrow needs to register the Bitkey
/// as one leg of a multisig:
///
/// ```text
/// [a1b2c3d4/48'/0'/0'/2']xpub6E.../*
/// ```
#[generator(yield(Vec<u8>), resume(Vec<u8>))]
fn derive_external_cosigner_key(
    network: BtcNetwork,
    account_index: u32,
) -> Result<SpendingKeyResult, CommandError> {
    let path = match external_cosigner_path(network, account_index) {
        Ok(path) => path,
        Err(e) => return Err(e),
    };
    yield_from_!(derive(network, path))
}

command!(DeriveExternalCosignerKey = derive_external_cosigner_key -> SpendingKeyResult,
    network: BtcNetwork,
    account_index: u32
);

/// Sign an externally supplied PSBT, but only for keys in the external
/// cosigner subtree.
///
/// Identical to [`super::SignTransaction`] except for the guard, which runs
/// before any hardware interaction — a rejected PSBT never reaches the device.
#[generator(yield(Vec<u8>), resume(Vec<u8>))]
fn sign_external_transaction(
    psbt: PartiallySignedTransaction,
    origin_fingerprint: Fingerprint,
    async_sign: bool,
) -> Result<PartiallySignedTransaction, CommandError> {
    if let Err(e) = enforce_external_cosigner_only(&psbt, origin_fingerprint) {
        return Err(e);
    }

    yield_from_!(sign_transaction(psbt, origin_fingerprint, async_sign))
}

command!(SignExternalTransaction = sign_external_transaction -> PartiallySignedTransaction,
    psbt: PartiallySignedTransaction,
    origin_fingerprint: Fingerprint,
    async_sign: bool
);

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use bitcoin::{
        absolute::LockTime,
        bip32::{DerivationPath, Fingerprint},
        psbt::{Input, Psbt as PartiallySignedTransaction},
        secp256k1::PublicKey,
        transaction::Version,
        OutPoint, Sequence, Transaction, TxIn, TxOut, Witness,
    };

    use super::*;

    const OURS: &str = "0c5f9a1e";
    const THEIRS: &str = "51135a9c";

    // Arbitrary valid compressed secp256k1 points; only their identity matters here.
    const PUBKEY_A: &str = "02f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9";
    const PUBKEY_B: &str = "03defdea4cdb677750a420fee807eacf21eb9898ae79b9768766e4faa04a2d4a34";

    fn empty_psbt() -> PartiallySignedTransaction {
        let tx = Transaction {
            version: Version::TWO,
            lock_time: LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint::null(),
                script_sig: Default::default(),
                sequence: Sequence::ENABLE_RBF_NO_LOCKTIME,
                witness: Witness::new(),
            }],
            output: vec![TxOut {
                value: bitcoin::Amount::from_sat(10_000),
                script_pubkey: Default::default(),
            }],
        };
        PartiallySignedTransaction::from_unsigned_tx(tx).unwrap()
    }

    fn psbt_with_derivation(
        entries: &[(&str, &str, &str)], // (pubkey, fingerprint, path)
    ) -> PartiallySignedTransaction {
        let mut psbt = empty_psbt();
        let mut input = Input::default();
        for (pubkey, fingerprint, path) in entries {
            input.bip32_derivation.insert(
                PublicKey::from_str(pubkey).unwrap(),
                (
                    Fingerprint::from_str(fingerprint).unwrap(),
                    DerivationPath::from_str(path).unwrap(),
                ),
            );
        }
        psbt.inputs = vec![input];
        psbt
    }

    fn ours() -> Fingerprint {
        Fingerprint::from_str(OURS).unwrap()
    }

    #[test]
    fn builds_bip48_p2wsh_path() {
        assert_eq!(
            external_cosigner_path(BtcNetwork::Bitcoin, 0).unwrap(),
            DerivationPath::from_str("m/48'/0'/0'/2'").unwrap()
        );
        assert_eq!(
            external_cosigner_path(BtcNetwork::Testnet, 3).unwrap(),
            DerivationPath::from_str("m/48'/1'/3'/2'").unwrap()
        );
    }

    #[test]
    fn accepts_external_cosigner_path() {
        let psbt = psbt_with_derivation(&[(PUBKEY_A, OURS, "m/48'/0'/0'/2'/0/5")]);
        assert!(enforce_external_cosigner_only(&psbt, ours()).is_ok());
    }

    #[test]
    fn rejects_bitkey_wallet_path() {
        // The attack this guard exists to stop: a PSBT that asks the hardware to
        // sign for the user's real Bitkey wallet under the guise of an external
        // multisig spend. The W1 has no display, so nothing else would catch it.
        let psbt = psbt_with_derivation(&[(PUBKEY_A, OURS, "m/84'/0'/0'/0/5")]);
        assert!(matches!(
            enforce_external_cosigner_only(&psbt, ours()),
            Err(CommandError::ExternalCosignerPathRejected)
        ));
    }

    #[test]
    fn rejects_bitkey_wallet_path_at_high_account_index() {
        // An account-index floor would have to be chosen carefully and could in
        // principle be reached by repeated recoveries. A purpose-field whitelist
        // cannot be.
        let psbt = psbt_with_derivation(&[(PUBKEY_A, OURS, "m/84'/0'/9999'/0/0")]);
        assert!(enforce_external_cosigner_only(&psbt, ours()).is_err());
    }

    #[test]
    fn rejects_mixed_psbt_wholesale() {
        // One good input does not license a bad one; the guard fails closed.
        let psbt = psbt_with_derivation(&[
            (PUBKEY_A, OURS, "m/48'/0'/0'/2'/0/5"),
            (PUBKEY_B, OURS, "m/84'/0'/0'/0/1"),
        ]);
        assert!(enforce_external_cosigner_only(&psbt, ours()).is_err());
    }

    #[test]
    fn ignores_other_cosigners() {
        // The Trezor and Jade legs carry their own fingerprints and arbitrary
        // paths. Not our keys, not our concern.
        let psbt = psbt_with_derivation(&[
            (PUBKEY_A, OURS, "m/48'/0'/0'/2'/0/5"),
            (PUBKEY_B, THEIRS, "m/84'/0'/0'/0/1"),
        ]);
        assert!(enforce_external_cosigner_only(&psbt, ours()).is_ok());
    }

    #[test]
    fn accepts_psbt_with_no_keys_of_ours() {
        // Nothing to sign is not an error here; sign_transaction reports the
        // empty-signables case itself.
        let psbt = psbt_with_derivation(&[(PUBKEY_B, THEIRS, "m/84'/0'/0'/0/1")]);
        assert!(enforce_external_cosigner_only(&psbt, ours()).is_ok());
    }

    #[test]
    fn rejects_unhardened_purpose_lookalike() {
        // m/48/... (unhardened) is not m/48'/... and must not pass.
        let psbt = psbt_with_derivation(&[(PUBKEY_A, OURS, "m/48/0'/0'/2'/0/5")]);
        assert!(enforce_external_cosigner_only(&psbt, ours()).is_err());
    }
}
