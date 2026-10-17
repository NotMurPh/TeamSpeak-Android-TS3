# Notice Sources and Audit Limits

Checked on 2026-10-17. Paths below are repository-relative or relative to the
named published crate/artifact, never a developer's home/cache path. Full texts
are reproduced without substantive changes; line endings/whitespace may differ.
The RealFFT disclosure is explicitly different: it reproduces standard MIT
terms based on upstream's MIT declaration, not a missing upstream notice.
License identifiers summarize upstream declarations, not verified legal advice.

## Direct Components

| Committed Notice | Source and Scope |
| --- | --- |
| `AUDIOPUS-ISC.txt` | Identical `LICENSE.md` files in cached `audiopus 0.3.0-rc.0` and `audiopus_sys 0.2.2`. Both Cargo manifests declare ISC. |
| `OPUS-COPYING.txt` | Cached `audiopus_sys 0.2.2/opus/COPYING`, including its patent references. That crate's `build.rs` can use bundled Opus or an externally installed library. The collector also copies `opus/AUTHORS` and `opus/LICENSE_PLEASE_READ.txt`; external library substitutions require a separate check. |
| `SONORA-BSD-3-Clause.txt` | Cached `sonora 0.2.0/LICENSE`; also present in sonora-agc2/common-audio/fft/ns/simd 0.2.0. The sonora-aec3 archive omits the file; the matching [upstream LICENSE](https://github.com/dignifiedquire/sonora/blob/a024d6ef8351add55be5e8b1d6cc35f555787660/LICENSE) is identical. Revision taken from sonora-aec3's `.cargo_vcs_info.json`. |
| `NNNOISELESS-COPYING.txt` | Cached `nnnoiseless 0.5.2/COPYING`. This package distributes the default `src/weights.rnn` under its BSD-3-Clause declaration, without a separate model-license file. `src/rnn.rs` embeds that file in `RnnModel::default`; the app uses this default via `DenoiseState::new`. The collector retains `AUTHORS` as well. No claim is made about independently trained/replacement models or training-dataset rights. |
| `NOTO-SYMBOLS2-OFL.txt` | Complete [Google Fonts Noto Sans Symbols 2 OFL.txt](https://raw.githubusercontent.com/google/fonts/main/ofl/notosanssymbols2/OFL.txt). The bundled TTF's name table was inspected: family Noto Sans Symbols 2, version 2.008, Copyright 2022 The Noto Project Authors, OFL 1.1. The upstream copyright and license match those embedded declarations; a binary hash match to an upstream font build was not established. |
| `MPL-2.0.txt`, `UNIFFI-NOTICE.md` | Complete [UniFFI v0.32.2 LICENSE](https://raw.githubusercontent.com/mozilla/uniffi-rs/v0.32.2/LICENSE); cached UniFFI manifests declare MPL-2.0 and source files carry MPL notices, but the published crates omit the complete LICENSE. Source-availability requirements remain separate from collecting notices. |
| `Apache-2.0.txt` | Complete text including appendix, as retained in `vendor/tsclientlib/LICENSE-APACHE`. Also checked against JNA 5.19.1's `AL2.0` terms (which omit the instructional appendix). Shared by the Apache-licensed components, not applied to every third party. |
| `JNA-NOTICE.md`, `JNA-LIBFFI-LICENSE.txt` | JNA 5.19.1 Maven POM and upstream tagged sources listed in `JNA-NOTICE.md`. JNA explicitly offers Apache-2.0 OR LGPL-2.1-or-later; Apache is selected. Native libffi's MIT-style notice is independent. The AAR's nested `classes.jar` contains `META-INF/LICENSE` and `META-INF/AL2.0`; retain those when packaging. |
| `REALFFT-3.5.0-NOTICE.md` | Published manifest/README MIT declarations, matching upstream revision, archive/source inspection, and standard SPDX MIT terms without an invented copyright line. This is a disclosed incomplete upstream attribution, not a complete recovered upstream license file or an entry in `CARGO-SUPPLEMENTS.json`. |

Vendored tsclientlib and declaration licenses are **not replaced**: the
collector copies their original license files from `vendor/tsclientlib`,
including `utils/tsproto-structs/declarations`. Local patches stay in that tree.

## Cargo Supplements

`CARGO-SUPPLEMENTS.json` maps exact package versions to reviewed complete texts
when conventional license-file discovery is insufficient. These mappings do
not apply automatically to later releases. MIT is selected for the dual-licensed
JNI, ndk-context, and quicklz packages below; for r-efi the included `AUTHORS`
contains the full MIT permission/disclaimer and copyright list. The remaining
UniFFI and Sonora mappings use the direct notices described above.

| Package(s) | Text Source, Pinned Using Cached `.cargo_vcs_info.json` |
| --- | --- |
| git-testament 0.2.6, git-testament-derive 0.2.1 | `GIT-TESTAMENT-BSD-3-Clause.txt`: [upstream COPYING](https://raw.githubusercontent.com/kinnison/git-testament/76b99270964e0e9660f14db5929e850aebc4b3c9/COPYING). |
| jni 0.22.4 | `JNI-RS-MIT.txt`: [upstream LICENSE-MIT](https://raw.githubusercontent.com/jni-rs/jni-rs/5ae9458a4ec44c5318f37ddc7569c1d4ae8a69e7/LICENSE-MIT). |
| jni-macros 0.22.4 | Same text, checked against its [distinct revision](https://raw.githubusercontent.com/jni-rs/jni-rs/33045a124105c939d1e2cbdcb5a39e5d868ffa03/LICENSE-MIT). |
| jni-sys-macros 0.4.1 | `JNI-SYS-MIT.txt`: [upstream LICENSE-MIT](https://raw.githubusercontent.com/jni-rs/jni-sys/64d77b7a5f119d7b55b4e2c169a4668067ff59e6/LICENSE-MIT). |
| ndk-context 0.1.1 | `NDK-CONTEXT-MIT.txt`: [upstream LICENSE-MIT](https://raw.githubusercontent.com/rust-windowing/android-ndk-rs/10f2ba388fca20f7349996ebae26ccda7a6fda5c/LICENSE-MIT). The upstream text has no separate copyright line; none was invented here. |
| quicklz 0.3.1 | `QUICKLZ-MIT.txt`: [upstream LICENSE-MIT](https://raw.githubusercontent.com/ReSpeak/quicklz/d905415a88aed6dae685bae500025ccc81de3822/LICENSE-MIT). |
| r-efi 6.0.0 | Cached `AUTHORS`: full MIT text and copyright list; collected directly rather than duplicating it here. Manifest declaration: MIT OR Apache-2.0 OR LGPL-2.1-or-later. |
| valuable 0.1.1 | `VALUABLE-MIT.txt`: [upstream LICENSE](https://raw.githubusercontent.com/tokio-rs/valuable/9efc29b6e58cef28f6566a47aa7e142a55fead77/LICENSE). |

The collector includes all conventional license/notice files recursively, not
just the first root LICENSE. This matters for `audiopus_sys/opus` and
`ring 0.17.14`: ring declares `Apache-2.0 AND ISC` and includes
`LICENSE-BoringSSL`, `LICENSE-other-bits`, fiat's nested LICENSE, and once_cell's
nested MIT/Apache texts. Do not collapse an AND expression into an OR choice.
Copies of both alternatives for other dual-licensed crates are retained where
shipped upstream; inclusion does not imply that both must be selected.

## Android Metadata

`android/app/build.gradle.kts` declares AndroidX Core, Activity, Lifecycle,
Compose UI, Material 3, Material icons, and JNA. Cached POMs for Core KTX 1.19.1
and Material 3 Android 1.4.0 identify the Android Open Source Project and
Apache-2.0. Cached Kotlin standard-library and kotlinx.coroutines metadata
declare Apache-2.0; the coroutines Android 1.9.0 POM was checked directly.
Those are sampled metadata checks, **not** a resolved release-classpath audit.
Cached versions can differ from those selected by the release BOM/Gradle graph.

The shared Apache text and component attributions are committed; preserve
additional artifact-specific notices in JARs/AARs. The Cargo collector does not
walk the entire Gradle cache (which would include unrelated build tools and
possibly unrelated artifacts). The release process must check the actual
`releaseRuntimeClasspath`, including non-AndroidX transitives and native code.

A small follow-up collector should resolve **only** that configuration through
a temporary Gradle init script, then collect embedded LICENSE/COPYING/NOTICE
entries from the resolved JARs/AARs (including nested `classes.jar`/`libs/*.jar`)
and the matching published POM license declarations. Export public artifact
coordinates and relative notice paths, not cache locations. Missing embedded
texts should be reported, not inferred from a POM URL. This would not edit build
files or scan unrelated cache contents; no Gradle collector is added here.

## Remaining Gaps

- **realfft 3.5.0**: cached Cargo metadata and README declare MIT, but the archive and [matching upstream tree](https://github.com/HEnquist/realfft/tree/d0d4eee0525fd27c96c8a046d6d107acd5ed84a6) contain no full license/copyright file or source notice. `REALFFT-3.5.0-NOTICE.md` records the evidence and includes standard MIT grant/condition/disclaimer text with no fabricated owner/year. The collector succeeds with a warning and exports `noticeIssues` for this exact known absence, not a claim that the attribution is resolved. Recommendation: retain the disclosure and request the applicable notice from upstream. New/unhandled gaps still fail collection.
- **TeamSpeak assets**: retained unchanged for publication by the owner's explicit choice and acceptance of responsibility for legal handling. Ownership is separate from any project MIT license; redistribution permission has not been verified. See `THIRD_PARTY.md`.
- **MPL source availability**: `UNIFFI-NOTICE.md` identifies upstream source. A release must also provide any covered local changes and a usable corresponding source location. Notice collection alone does not do this.
- **Release integration**: `tools/release.ps1` embeds the generated bundle under `assets/licenses/`, adds matching source locations, and creates `third-party-licenses.zip`. The workflow publishes that sidecar with the APK. Keep the full bundle with every other binary distribution channel too; this does not resolve the remaining audit limits.
- **Completeness limits**: filename-based collection can miss notices embedded only in source comments or unusually named files. Cargo metadata is an over-inclusive dependency inventory, not proof that each crate is linked. Rust sysroot/toolchain code, NDK runtimes, external Opus substitutions, and the exact Gradle runtime/native graph need release-specific review if distributed.

## Validation

1. Run `powershell -NoProfile -File tools/collect-licenses.ps1 -OutputDirectory <new-directory>` after caching locked dependencies. The parent directory must exist. With the audited graph, expect successful collection plus a warning for the known RealFFT deficiency, which remains explicit in `REVIEW.md`. Other collection errors still produce a nonzero exit status.
2. Check `CARGO-NOTICES.json`: package names/versions, upstream license expressions, relative notice paths, unresolved `noticeIssues`, and the lockfile SHA-256 are exported, not raw Cargo IDs, local manifest paths, credentials, or signing configuration. Every listed notice path should exist; realfft's entry must retain its unresolved issue and link to `REALFFT-3.5.0-NOTICE.md`.
3. Check nested Opus/ring notices and vendored declaration licenses are present. For notices copied from cache, compare hashes to the matching upstream files; compare normalized whitespace for manually reproduced texts.
4. Re-run after any lockfile, feature, artifact, or native-library change. This script uses default workspace features; a release with extra features needs a matching expanded collection/audit. Verify the actual Android runtime classpath and MPL source access separately before publishing.
