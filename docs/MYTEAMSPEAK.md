# myTeamSpeak Identity Transfer

The Identities page provides optional myTeamSpeak sign-in and separate selection
dialogs for downloading and uploading identities. File import/export remains
separate. Nothing is uploaded merely by signing in or opening a dialog.

## Account Handling

- Passwords are not saved to preferences, backups, files or logs.
- Sessions and the decrypted account key are held in memory. Sign-out discards
  them; a new app process requires sign-in again.
- An optional two-factor code can be supplied at sign-in.
- Account recovery, password reset and replacement encryption keys are not
  implemented. Failed decryption never triggers a reset or overwrite.
- All requests use HTTPS with normal certificate validation, fixed myTeamSpeak
  endpoints, redirects disabled and connection/read timeouts.

## Transfers

Downloads add only checked identities and skip public UIDs already on the phone.
Uploads refresh the cloud list, skip existing UIDs, and send new selected items
with new UUIDs. Existing items and other item classes are not modified. Uploads
are verified by fetching again. Collisions and uncertain network outcomes are
reported without automatically retrying a write.

The implementation uses independently written protobuf framing and encryption
code. No official client APK, native library, or extracted implementation code
is packaged. The protocol is not a published TeamSpeak integration contract.

## Verification

`./gradlew :app:testDebugUnitTest` from `android/` checks password/KDF vectors,
authenticated account-key decoding, multi-block item encryption including
counter carry, protobuf bounds, and simulated selective-transfer/error flows.
The cryptographic test vectors are synthetic, not account credentials.

These tests do **not** establish live service interoperability. A real-account
round trip has not been performed during implementation. Verify first with a
disposable identity: sign in, download one chosen identity, upload one newly
created identity, and check it in the official client. Retain private backups
before testing. Legacy account-key version 1 is implemented from the recovered
format but also needs live verification, particularly UUID text normalization.
