# Bitkey Cosigner

A fork of [Block's Bitkey](https://github.com/proto-at-block/bitkey) that lets a
Bitkey W1 act as one signer in a multisig wallet managed by another coordinator —
for example a 2-of-3 in [Sparrow](https://sparrowwallet.com) alongside a Trezor
and a Jade.

The stock app only ever signs for its own 2-of-3 (phone key, hardware key, Block's
server key). This build does one thing instead: export the hardware's public key
for an external wallet, and sign PSBTs for that wallet. It creates no account,
stores nothing, and installs alongside the official app as `world.bitkey.debug`
so your real Bitkey wallet is untouched.

> **This is an unofficial fork.** It is not affiliated with, endorsed by, or
> supported by Block. Do not report issues with it to them.

## W1 only

This cannot work on W3 hardware, and that is not a limitation of the fork.

W1 is a generic BIP32 signer: `derive_key_descriptor_and_sign` signs an arbitrary
hash at an arbitrary derivation path. W3 removed that command. Instead it derives
child pubkeys from its own stored keyset, builds a BIP67-sorted 2-of-3 witness
script itself, and computes the sighash over *that* script — so it can only ever
produce signatures valid for a Bitkey-shaped descriptor. Feed it a foreign
multisig and the signature is invalid on-chain.

Check yours: the app's device info shows a **Model number**. `w1a-…` is W1;
`w3a-…` is W3. Physically, W1 has no screen.

## How it works

Cosigner keys are derived at `m/48'/coin'/account'/2'` — BIP48 script-type 2, the
standard path for P2WSH multisig and Sparrow's default. Your Bitkey's own wallet
lives under BIP84 (`m/84'/…`). The two subtrees are disjoint at the purpose level.

That matters, because **W1 has no display and will sign whatever hash it is
handed.** In the stock app that is safe: the only PSBTs it ever sees come from
your own wallet. Accepting PSBTs from outside removes that assumption — a crafted
PSBT could point `bip32_derivation` at your real Bitkey keys and get them signed
unseen.

So this fork refuses to sign any input deriving outside `m/48'`. It is a
whitelist on the purpose field, not an account-index threshold that repeated
recoveries could eventually reach, and it fails closed: one out-of-subtree input
rejects the entire PSBT, before any NFC traffic.

## Workflow

1. **Export** — tap the Bitkey, save the `.json` (Coldcard multisig format) and
   `.txt` (manual fallback) to a USB drive.
2. **Import into Sparrow** — New Wallet → Multi Signature → on this cosigner's
   slot choose **Coldcard → Import file**. The generic airgapped-import entry
   will not accept it. Confirm the fingerprint and derivation path afterwards.
3. **Sign** — build the transaction in Sparrow, save the PSBT to the drive, open
   it in this app, check the details, tap, save the signed file back.

Files move via the system document picker, which reaches USB/OTG storage in both
directions.

## Read this before using it with real money

**The Bitkey cannot verify what it signs.** It has no screen. The transaction
details this app shows are the only chance to notice a tampered PSBT, and they
are only worth anything if you check them against the coordinator that built the
transaction. The phone is the online half of the setup.

**The hardware key has no seed backup.** Bitkey never exposes a seed phrase for
the device key — recovery is meant to run through Block's server key and social
recovery, none of which apply to a foreign descriptor. If the device is lost or
destroyed, that leg of your multisig cannot be restored from a phrase. Your
quorum survives on the other signers, but plan the migration in advance.

**Back up the wallet's output descriptor separately.** Your Bitkey holds a key
but has no idea it belongs to your multisig, and the official app cannot tell
you. The descriptor is what makes the remaining signers able to recover funds.

**Both descriptors share a master fingerprint.** Your Bitkey wallet's descriptor
and your multisig descriptor carry the same 4-byte fingerprint, since both derive
from one seed. Anyone holding both can link them. There is no cryptographic
linkage — every level is hardened — but the metadata is conclusive.

**Test on testnet first.** All of it: export, import, fund, spend, sign,
broadcast.

## Building

```bash
git submodule update --init firmware/third-party/memfault-firmware-sdk firmware/third-party/nanopb
source bin/activate-hermit
cd app && just android-app-install
```

Needs Android SDK 35 and NDK 25.2.9519653. Use Hermit's rustup rather than a
system Rust — the repo pins 1.91.1 via `rust-toolchain.toml`, which Homebrew's
rustc ignores. Run Rust tests with `cargo nextest run`, not `cargo test`: the
`command!` macro keeps its generator in a `static mut` and segfaults under the
test thread pool.

The debug APK is large (~950MB, all ABIs unstripped). Fine over USB; build a
release variant with your own signing key for anything else — do not ship the
repo's public `debug.keystore`.

## What changed

| Area | Change |
|---|---|
| `app/rust/wca` | `DeriveExternalCosignerKey`, `SignExternalTransaction`, and the `m/48'` guard |
| `app/rust/firmware-ffi` | UDL declarations for both commands |
| `app/domain/hardware` | `NfcCommands` methods, W1 implementation, decorator forwarding |
| `build.wallet.externalmultisig` | Sparrow export formats, PSBT loader, summary, byte normalisation |
| `bitkey.ui.screens.externalmultisig` | Export screen, sign screen, home |
| `app/libs/platform` | `FilePicker` / `FileSaver` via SAF |
| `AppUiStateMachineImpl` | No-account state opens the cosigner home instead of onboarding |
| `AccountConfigServiceImpl` | Development builds use real hardware, not the simulator |

Upstream `main` is preserved in this repo, so `git diff main..sparrow-cosigner`
shows the complete change set.

## Licence

MIT, inherited from [proto-at-block/bitkey](https://github.com/proto-at-block/bitkey).
Bitkey is a trademark of Block, Inc.
