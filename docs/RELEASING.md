# Android Release Maintenance

Every push to `main` runs `.github/workflows/release.yml`. A successful run publishes a new release, not merely a workflow artifact. Failed tests, lint, signing, or verification block publication. There is no path filter, workflow-wide cancellation, or in-app updater.

Manual `workflow_dispatch` is also available for recovery on `main`; other refs are skipped. A manual dispatch creates a new run number/version. Rerun an existing workflow when the same version code and tag must be retained.

## First Installation With the Release Key

**Export a backup from the old app before uninstalling it.** Earlier builds were debug-signed. Android cannot install the new dedicated-key APK over an app with the same application ID and a different certificate. Uninstall the old app once, install the release APK, and import the backup. Uninstalling deletes app data; Android backup is disabled for this app. Later APKs signed with the same release key can update in place when their version code increases.

## Signing Setup

Provision one dedicated release keystore outside the repository and retain an offline recovery copy of it, its passwords, alias, and certificate fingerprint. Do not regenerate this key for each build. Losing it prevents ordinary updates to existing installations.

The repository must have these Actions secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the complete binary keystore, not a path |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Dedicated release key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

The workflow decodes the keystore under `RUNNER_TEMP`, exposes its path as `ANDROID_KEYSTORE_PATH` only during the signing step, and removes it in both `finally` and an always-run cleanup step. Only the four public assets are uploaded. Passwords are passed through environment variables, never Gradle properties or command-line password arguments. The certificate fingerprint is public, not a secret.

Any resolved Gradle task whose name contains `release`, including `lintRelease`, `assembleRelease`, `bundleRelease`, or an aggregate build that includes release tasks, requires all four signing environment variables and an existing keystore file. There is no fallback to debug signing. Ordinary debug tasks need none of these variables and retain standard debug signing.

Protect `main` and review changes to the workflow, build scripts, and dependencies before merging: main-branch code executes in a job that can access signing secrets. The build job has read-only repository permissions. Only the publication and promotion jobs have `contents: write`; `GH_TOKEN` is scoped to their `gh` steps and uses `github.token`, not a personal token. Checkout does not persist credentials. Neither publishing job receives keystore secrets or executes repository build scripts.

## Pinned Build Inputs

| Input | Version |
| --- | --- |
| Runner | `windows-2022` for native/Android builds |
| Java | Temurin 21 |
| Rust | 1.99.0 (`rust-toolchain.toml`), Android target `aarch64-linux-android` |
| cargo-ndk | 4.1.2, installed with `--locked` |
| Android NDK | 29.0.14206865 |
| SDK platform / build tools | `platforms;android-37.0` / 36.0.0 (`compileSdk = 37` unchanged) |
| AGP / Gradle | Existing 9.4.1 / 9.8.0, unchanged |
| Application ID | `com.tsrewrite.client` |
| Minimum Android / ABI | API 26 (Android 8.0) / `arm64-v8a` only |

All project Cargo build, test, and binding-generation calls use `--locked`. The native script initializes the latest Visual Studio installation containing the x64 C++ tools, loads its developer environment, and uses Ninja/CMake for Opus. It selects the exact NDK under `ANDROID_HOME`, never the newest installed NDK, and checks that cargo-ndk produces `libtscore.so`. The unstripped host DLL supplies UniFFI metadata. CI rejects regenerated Kotlin bindings that differ from checked-in source, including untracked generated files.

Actions are pinned to the following official release commits, verified during peer review:

| Action | Release | Commit |
| --- | --- | --- |
| `actions/checkout` | v4.4.0 | `11d5960a326750d5838078e36cf38b85af677262` |
| `actions/setup-java` | v4.9.1 | `cf277c60eb25467037889841efdb72551f06f6c3` |
| `actions/upload-artifact` | v4.6.2 | `ea165f8d65b6e75b540449e92b4886f43607fa02` |
| `actions/download-artifact` | v4.3.0 | `d3f86a106a0bac45b974a628896c90dbdf5c8093` |

When updating an action, independently verify its official release commit and review the change. Hosted runner images and other tool distributions remain mutable build inputs. Do not describe this build as bit-for-bit reproducible; the Gradle dependency setup is otherwise unchanged.

## Versions And Assets

The base application version is `0.1.0`. For workflow run number `N`:

| Field | Value |
| --- | --- |
| Tag | `build-N` |
| Gradle `ciVersionCode` | `1000 + N` |
| Gradle `ciVersionName` | `0.1.0+build.N` |
| APK filename | `teamspeak-android-arm64-v8a.apk` |

The run attempt is deliberately excluded from the version and tag. Rerunning a workflow retains the version code. Keep this workflow's run-number lineage: renaming/replacing it or migrating repositories can reset numbering and require an explicit version offset migration above all previously shipped codes. Android version codes must not exceed 2100000000. Local Gradle defaults remain code `1`, name `0.1.0`; use CI properties for installable release upgrades.

Each release contains exactly these public assets:

- `teamspeak-android-arm64-v8a.apk`: the verified, dedicated-key-signed APK.
- `third-party-licenses.zip`: the entire collected notice bundle under `licenses/`, matching the bundle embedded in the APK.
- `SHA256SUMS`: lowercase SHA-256 checksums for the APK, `third-party-licenses.zip`, and `update.json`, using standard two-space filename separators.
- `update.json`: schema version 1 release metadata, not an updater implementation.

`update.json` fields are `schemaVersion` (integer 1), `versionCode` (integer), `versionName`, `applicationId`, `minSdk` (integer 26), `abi` (`arm64-v8a`), `commit` (full source SHA), `sha256` (APK digest), `certificateSha256` (APK signing certificate digest), and `downloadUrl`. Both digests are lowercase hexadecimal without colons. The URL is tag-specific, `https://github.com/OWNER/REPO/releases/download/build-N/teamspeak-android-arm64-v8a.apk`, never a mutable `latest/download` URL. SHA256SUMS is not a detached signature; the Android signing certificate is the installation trust boundary. Any future updater needs its own explicit trust and verification design.

## License Bundle

Before Gradle runs, `tools/release.ps1` runs `cargo fetch --locked`, creates `target/release-assets`, and invokes `tools/collect-licenses.ps1 -OutputDirectory target/release-assets/licenses`. The collector requires a new or empty output; a retry removes only this fixed generated `licenses` directory, not other `target` contents or the collector's default output. It copies the root project `LICENSE`, `THIRD_PARTY.md`, all reviewed `docs/licenses` notices (including known direct Android full notices), vendored notices, and the locked Cargo notice inventory and texts. Unknown collection gaps stop the release.

The exact known `realfft 3.5.0` missing upstream MIT copyright attribution remains a warning, not a resolved notice or legal clearance. The owner accepts legal responsibility for that disclosed deficiency. Preserve `REVIEW.md`, the `noticeIssues` inventory entry, and `docs/licenses/REALFFT-3.5.0-NOTICE.md`; do not suppress other collector errors.

The script adds `SOURCE-LOCATION.md` with the supplied repository, exact release commit, matching project/vendored source and archive links, and exact-version UniFFI crate source archives from the collected inventory. Maintainers must keep the corresponding source accessible, including covered local changes where applicable; source links are not proof of compliance by themselves.

The release source set adds `rootProject.file("../target/release-assets")` as an assets directory, producing `assets/licenses/...` in the APK. This path is relative to the Android root project, not `android/app`. Debug source sets and ordinary debug builds are unaffected. The sidecar ZIP contains the same full bundle under `licenses/`; distribute it alongside the APK on other binary distribution channels as well.

This integration is **not a complete resolved Gradle release-runtime-classpath license audit**. The collector includes locked Cargo build/dev/tool and other-platform dependencies and reviewed direct Android notices, but does not automatically discover every resolved Gradle transitive notice or native component. No resource-exclusion or metadata-stripping rule is added: retain existing packaged JAR/AAR notices and review the actual `releaseRuntimeClasspath`, embedded notices, native libraries, and source-availability obligations when dependencies change. See `docs/licenses/SOURCES.md` for the collection scope and remaining limits.

## Verification And Publication

Before publication the build runs `cargo test --locked -p tscore --lib`, compares generated bindings, collects the license bundle, and runs `:app:lintRelease :app:assembleRelease`. The signing script then verifies:

- A valid APK signature with exactly one signer, whose SHA-256 fingerprint matches the configured keystore alias.
- The expected application ID, version code/name, minSdk 26, and only the arm64-v8a native ABI, with a non-debuggable manifest.
- Packaged `lib/arm64-v8a/libtscore.so` and JNA's `libjnidispatch.so`, with no `libteamspeak_client` entry anywhere in the APK.
- Embedded `assets/licenses/THIRD_PARTY.md` and `assets/licenses/SOURCE-LOCATION.md` in the signed APK.
- ZIP alignment including 16 KB native-library page alignment, plus AArch64 ELF headers and at least 16 KB LOAD segment alignment for every packaged `.so`.

The Windows build transfers only public files to the Linux publication job using the exact artifact ID from that attempt. The publication job rechecks checksums and metadata, creates a draft targeted at the triggering commit, uploads all assets, and only then publishes it with automatic latest selection disabled. Existing tags must resolve to the triggering commit. A failed upload leaves a recoverable draft; a rerun may replace assets only while that release is still a draft. A published `build-N` is never overwritten, deleted, retagged, or repackaged by the workflow. This is workflow-enforced immutability; it does not enable or replace GitHub's repository-level immutable-release setting.

Publication is independent for every push. Only latest promotion is serialized across runs with `cancel-in-progress: false`. Each promotion scans all published, non-prerelease `build-N` releases and chooses the highest numeric N, regardless of which build finished last. GitHub may coalesce pending promotion jobs, but it cannot cancel build/publication jobs through this group. Thus an older build finishing late cannot move latest backward. Rerunning an already-published build leaves its assets unchanged and still runs promotion against the highest published build. If publication or promotion fails due to transient API errors, rerun the failed jobs; do not delete a published release to retry it.

## Local Maintenance

Use Windows with Java 21, Visual Studio x64 C++ tools, CMake/Ninja, the pinned SDK/NDK, and cargo-ndk 4.1.2. Set `ANDROID_HOME` and provision the four signing environment variables from a secure local source when testing release tasks. Never commit the keystore or passwords.

```powershell
./tools/build-core.ps1
cargo test --locked -p tscore --lib
./tools/release.ps1 -VersionCode 1001 -VersionName '0.1.0+build.1' `
    -Commit (git rev-parse HEAD) -Repository 'OWNER/REPO' -Tag 'build-1'
```

`tools/release.ps1` fetches locked Cargo sources, collects notices, builds, verifies, and writes four public assets to `dist`; it does not publish or call the GitHub API. It does not build Rust itself: run the native script first to avoid packaging stale libraries. Use real run metadata for any production release, not these example values. Normal local debug development can continue with `android/gradlew.bat :app:assembleDebug` without release signing variables or a generated license bundle.

When changing pins, update the workflow, native/release scripts, Gradle configuration, and this document together as applicable. Investigate lint failures rather than disabling release checks. An unsigned, debug-signed, missing-native-library, mismatched-binding, or misaligned APK must not be published as a workaround.
