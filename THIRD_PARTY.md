# Third-Party Components

Third-party code, fonts, artwork, and sounds retain their respective owners
and licenses. An MIT license for original project code does **not** relicense
these components or grant rights to TeamSpeak assets or trademarks.

This inventory was checked against `Cargo.lock`, cached Cargo sources, Android
dependency declarations, and selected cached Maven metadata on 2026-10-17.
It is an attribution record, not a legal clearance or an exhaustive binary SBOM.
Full direct-component notices are in [docs/licenses](docs/licenses/);
[SOURCES.md](docs/licenses/SOURCES.md) records provenance and remaining gaps.

## Code and Font Notices

| Component | License and Full Notices |
| --- | --- |
| ReSpeak **tsclientlib**, tsproto, and supporting crates, vendored in `vendor/tsclientlib` | MIT OR Apache-2.0. Original [MIT](vendor/tsclientlib/LICENSE-MIT), [Apache](vendor/tsclientlib/LICENSE-APACHE), and nested declaration licenses remain in place. Local patches include raw command stream, unspecified file-transfer address, notifications on both command channels (TeaSpeak), and packet-loss counters accessor. |
| **audiopus 0.3.0-rc.0**, **audiopus_sys 0.2.2** | ISC for the Rust wrappers, not BSD. Both publish the same [ISC notice](docs/licenses/AUDIOPUS-ISC.txt). |
| Native **Opus**, bundled with audiopus_sys | BSD-3-Clause; [COPYING, including patent-license references](docs/licenses/OPUS-COPYING.txt). The collector also retains bundled `AUTHORS` and `LICENSE_PLEASE_READ.txt`. If the build links a different external Opus, collect that version's notices too. |
| **Sonora 0.2.0** and sonora-aec3/agc2/common-audio/fft/ns/simd 0.2.0 | BSD-3-Clause; [full WebRTC, Arun Raghavan, and dignifiedquire notice](docs/licenses/SONORA-BSD-3-Clause.txt). |
| **nnnoiseless 0.5.2**, including its packaged default RNNoise-derived model | BSD-3-Clause; [full COPYING](docs/licenses/NNNOISELESS-COPYING.txt). The default model is the crate's `src/weights.rnn`, embedded by `RnnModel::default`; no separate model license is shipped in that crate. This does not cover independently supplied replacement models or training data. |
| **UniFFI 0.32.2** and uniffi_* runtime/generator crates | MPL-2.0; [full license](docs/licenses/MPL-2.0.txt) and [source-availability notice](docs/licenses/UNIFFI-NOTICE.md). Preserve MPL-covered source notices and make corresponding covered source/modifications available for binary releases. |
| **Noto Sans Symbols 2**, `android/app/src/main/res/font/noto_symbols2.ttf` | OFL-1.1; [complete copyright and SIL Open Font License](docs/licenses/NOTO-SYMBOLS2-OFL.txt). Embedded name metadata identifies version 2.008 and Copyright 2022 The Noto Project Authors. |
| **JNA 5.19.1** / libjnidispatch | Apache-2.0 **selected** from Apache-2.0 OR LGPL-2.1-or-later; [full Apache license](docs/licenses/Apache-2.0.txt) and [JNA notice](docs/licenses/JNA-NOTICE.md). Bundled native **libffi** has a separate [MIT-style license](docs/licenses/JNA-LIBFFI-LICENSE.txt). |
| **AndroidX**, Jetpack Compose, Material components/icons | Apache-2.0; [full license](docs/licenses/Apache-2.0.txt). Attribution: The Android Open Source Project and respective contributors. Retain artifact-specific notices. |
| **Kotlin standard library** and **kotlinx.coroutines** on the Android runtime classpath | Apache-2.0; [full license](docs/licenses/Apache-2.0.txt). Attribution: JetBrains and respective contributors. The exact resolved release classpath and any additional transitive notices still need a release-time check. |
| **RealFFT 3.5.0** | Declared MIT; [standard MIT terms, upstream evidence, and missing-notice disclosure](docs/licenses/REALFFT-3.5.0-NOTICE.md). Upstream supplies no copyright/full license notice for this version. No holder/year is invented; the attribution deficiency remains recorded. |

Other Cargo dependencies, including Tokio, futures, tracing, RustCrypto crates,
and native/transitive components such as ring's BoringSSL/fiat-derived code,
have their own notices. Do not treat the table above or an SPDX expression as
a substitute for their full license/copyright texts.

## TeamSpeak Assets and Trademarks

The project owner has explicitly chosen to publish the following existing
official assets as-is and accepts responsibility for handling their legal
status. This decision is **not** a license grant from TeamSpeak, and this
project has **not verified redistribution permission**. These assets are
expressly outside any MIT license for project code.

- **TeamSpeak logo/artwork**: `android/app/src/main/res/mipmap-*/ic_launcher_foreground.png`, `ic_launcher_monochrome.png`, and `res/drawable-*/ic_notification.png`, extracted from the official TeamSpeak 3 Android app 3.4.9. Artwork ownership and TeamSpeak trademarks remain with TeamSpeak Systems GmbH and their respective rights holders.
- **TeamSpeak 3 default sounds**: `android/app/src/main/res/raw/ts_message.wav` and `ts_poke.wav`, extracted from that official app. Copyright belongs to TeamSpeak Systems GmbH and the respective rights holders; no open-source license is asserted for these sounds.

No affiliation, sponsorship, endorsement, trademark license, or permission to
redistribute the official app or its other contents is implied.

## Release Notice Bundle

Run from the repository after the locked dependency sources are cached:

```powershell
powershell -NoProfile -File tools/collect-licenses.ps1
```

The default output is `target/third-party-licenses` (its parent must exist).
Alternatively pass `-OutputDirectory` pointing to a new or empty directory.
The script is offline and uses `cargo metadata --locked --offline`, with Rustup
toolchain auto-install disabled. Install the required toolchain beforehand;
if sources are missing, run `cargo fetch --locked` in a network-enabled environment.
It collects the full resolved workspace graph, including host/build/dev/tool
and other-platform dependencies, rather than claiming they all ship in the APK.

The bundle contains this notice, the root `LICENSE` if present, `docs/licenses`,
vendored license files, recursively discovered Cargo license/copyright/notice/
author/patent files (including native subdirectories), a path-free package
inventory with the `Cargo.lock` SHA-256, and `REVIEW.md`. It does not copy raw
Cargo metadata, signing material, local manifest paths, or build configuration.

The collector fails on new/unhandled missing notices or required collection
files. The exact known **`realfft 3.5.0`** absence is instead included as a
warning in `REVIEW.md` and `noticeIssues` in `CARGO-NOTICES.json`, together with
the declared MIT terms and an explicit missing-attribution disclosure. This
allows bundle generation without pretending that upstream supplied a complete
notice or that legal sufficiency was verified. Other missing notices are not
silently waived. Filename checks cannot prove compliance.

For a release, distribute the **whole bundle alongside the matching APK/native
binaries**, for example in a downloadable `third-party-licenses.zip`, and make
the notices available with every distribution channel. Include a corresponding
source location for MPL-covered components and any covered modifications.
The collector itself does not enumerate Gradle artifacts. The release script
embeds its generated bundle under `assets/licenses/` in the APK and publishes
the matching `third-party-licenses.zip`, including exact source locations.
Recheck the resolved Android runtime classpath and preserve its embedded
notices before publishing, especially if dependency versions change.
