# RealFFT 3.5.0: MIT Declaration and Missing Upstream Notice

RealFFT 3.5.0 declares the MIT license. The following is an excerpt from its
published `Cargo.toml.orig`, matching the upstream source manifest:

```toml
[package]
name = "realfft"
version = "3.5.0"
license = "MIT"
repository = "https://github.com/HEnquist/realfft"
```

The upstream README ends with `License: MIT`.

## Evidence and Limitation

Checked on 2026-10-17 against the cached `realfft-3.5.0.crate` archive, its
extracted sources, and upstream revision
`d0d4eee0525fd27c96c8a046d6d107acd5ed84a6`, recorded in the crate's
`.cargo_vcs_info.json`:

- [Matching Cargo manifest](https://github.com/HEnquist/realfft/blob/d0d4eee0525fd27c96c8a046d6d107acd5ed84a6/Cargo.toml)
- [Matching README](https://github.com/HEnquist/realfft/blob/d0d4eee0525fd27c96c8a046d6d107acd5ed84a6/README.md)
- [Complete recursive upstream tree](https://api.github.com/repos/HEnquist/realfft/git/trees/d0d4eee0525fd27c96c8a046d6d107acd5ed84a6?recursive=1)

Neither the archive nor that upstream tree contains a LICENSE, LICENCE,
COPYING, COPYRIGHT, AUTHORS, or NOTICE file. Searches of the extracted package
found license declarations only in the two manifests and README; no copyright
notice or complete MIT text was found in the source, example, or benchmark
headers or elsewhere in those package files. The queried history of the exact
path `LICENSE` up to this revision also returned no commits; this is not a claim
about every historical filename or every version of the project.

No copyright holder or year is inferred from Cargo's authors field. Absence of
a notice is **not** absence of copyright. This document preserves the known MIT
declaration and reproduces the standard MIT permission/condition/disclaimer
below, without inserting a fictional copyright line. It is **not** a recovered
complete upstream notice, a new license grant by this project, or a claim that
the missing attribution has been resolved.

## Standard MIT Terms

The following grant, condition, and disclaimer are the standard MIT text from
the [SPDX license list](https://raw.githubusercontent.com/spdx/license-list-data/main/text/MIT.txt).
The SPDX template's placeholder copyright line is deliberately not reproduced
or filled in. These terms are included on the basis of upstream's explicit MIT
declaration, not as evidence of an identified copyright owner.

```text
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Release Handling

The notice collector includes this document and records the exact-version
upstream deficiency in `CARGO-NOTICES.json`, `REVIEW.md`, and a warning. This
known absence does not prevent producing a release notice bundle; it remains
an unresolved attribution deficiency, not a clean license-audit result.

Recommendation: retain this disclosure with the MIT terms and ask upstream to
provide the applicable copyright/license notice. If supplied, preserve it and
replace this known-deficiency handling with the verified notice. This approach
does not determine legal sufficiency or remove any applicable license duty.
