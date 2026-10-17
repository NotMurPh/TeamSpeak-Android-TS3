# Publication Boundaries

The public repository starts from a reviewed source snapshot, not the earlier
local research history. Never push private/local-history branches or use
`git push --all` or `git push --mirror` from the original development checkout.

## Included

- Android application source, resources and Gradle wrapper.
- Project core, command-line test harness and binding generator sources.
- Required open-source vendored protocol dependencies and their licenses.
- Original presentation graphics, documentation and release automation.
- Existing TeamSpeak artwork and notification sounds, retained at the owner's
  explicit direction, with ownership/permission limitations in `THIRD_PARTY.md`.

## Not Included

- Original TeamSpeak APKs, extracted official native libraries and decompiled
  reference material (`original/` and `reference/`).
- Local TeamSpeak server binaries, database, logs, credentials, privilege tokens
  and saved test identities (`testserver/`).
- Local research notes and network-specific SDK/proxy utilities.
- Build outputs, caches, machine-specific SDK paths and IDE state.
- Signing keys, passwords, environment secrets and app backup/export files.
- Old Git history containing excluded material or identifying defaults.

Releases compile the project's own native library; they must not package
`libteamspeak_client.so` from the official client. Upstream public test fixtures
in vendored source are not application credentials and must never be used as
privileged identities on real servers.

## Before Sharing

Use fictional names and channels in presentation images. Review any actual
screenshots/logs for private server addresses, usernames and messages. App JSON
backups contain identities and saved passwords and must remain private.

Ignore rules are a guardrail, not a secret scanner: they do not remove files
already committed. Review staged changes and history as well as the working
directory before pushing. Release signing material belongs only in private
local storage and the four repository-specific Actions secrets documented in
`RELEASING.md`.
