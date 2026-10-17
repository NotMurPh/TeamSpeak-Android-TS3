# Collect release notices without copying Cargo metadata, local paths, or build artifacts.
# Requires PowerShell 5.1+, Cargo, and the locked dependency sources already cached.
[CmdletBinding()]
param([string]$OutputDirectory)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $root 'target/third-party-licenses' }
if (-not [IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory = Join-Path (Get-Location).ProviderPath $OutputDirectory }
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (-not (Test-Path -LiteralPath (Split-Path $output -Parent) -PathType Container)) {
    throw 'The output parent directory must already exist.'
}
if ((Test-Path -LiteralPath $output) -and
    (-not (Test-Path -LiteralPath $output -PathType Container) -or
    @(Get-ChildItem -LiteralPath $output -Force).Count -ne 0)) {
    throw 'Use a new or empty output directory; existing bundles are not overwritten.'
}

$autoInstall = $env:RUSTUP_AUTO_INSTALL
try {
    $env:RUSTUP_AUTO_INSTALL = '0'
    $json = & cargo metadata --manifest-path (Join-Path $root 'Cargo.toml') --format-version 1 --locked --offline
    if ($LASTEXITCODE -ne 0) { throw 'Cargo metadata failed. Install the required toolchain and cache sources with cargo fetch --locked, then retry.' }
} finally {
    $env:RUSTUP_AUTO_INSTALL = $autoInstall
}
$metadata = ($json -join "`n") | ConvertFrom-Json
$supplements = Get-Content -LiteralPath (Join-Path $root 'docs/licenses/CARGO-SUPPLEMENTS.json') -Raw | ConvertFrom-Json
$packages = @($metadata.packages | Sort-Object name, version)
$workspace = @($metadata.workspace_members)
$vendor = (Join-Path $root 'vendor/tsclientlib') + [IO.Path]::DirectorySeparatorChar
$unsupported = @($packages | Where-Object {
    ($_.source -and $_.source -notlike 'registry+*') -or
    (-not $_.source -and $_.id -notin $workspace -and
        -not $_.manifest_path.StartsWith($vendor, [StringComparison]::OrdinalIgnoreCase))
})
if ($unsupported.Count) {
    throw ('Unreviewed git/path dependencies: ' + (($unsupported | ForEach-Object { "$($_.name)@$($_.version)" }) -join ', '))
}

$null = New-Item -ItemType Directory -Path $output -Force
Copy-Item -LiteralPath (Join-Path $root 'THIRD_PARTY.md') -Destination $output
if (Test-Path -LiteralPath (Join-Path $root 'LICENSE') -PathType Leaf) {
    Copy-Item -LiteralPath (Join-Path $root 'LICENSE') -Destination $output
}
$null = New-Item -ItemType Directory -Path (Join-Path $output 'docs')
Copy-Item -LiteralPath (Join-Path $root 'docs/licenses') -Destination (Join-Path $output 'docs') -Recurse

# Copy only upstream notice files, including nested native-library notices.
$noticePattern = '^(LICENSE|LICENCE|COPYING|COPYRIGHT|NOTICE|AUTHORS|PATENTS|UNLICENSE)([._-].*)?$'
$sourceExtensions = '^\.(rs|c|h|cc|cpp|py|js|ts|sh|ps1)$'
$vendorFiles = @(Get-ChildItem -LiteralPath $vendor -File -Recurse | Where-Object {
    $_.Name -match $noticePattern -and $_.Extension -notmatch $sourceExtensions
})
foreach ($file in $vendorFiles) {
    $relative = $file.FullName.Substring($vendor.Length)
    $destination = Join-Path $output "vendor/tsclientlib/$relative"
    $null = New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force
    Copy-Item -LiteralPath $file.FullName -Destination $destination
}

$inventory = @()
$gaps = @()
$knownGaps = @()
foreach ($package in $packages | Where-Object { $_.source -like 'registry+*' }) {
    $label = "$($package.name)-$($package.version)"
    $source = (Split-Path $package.manifest_path -Parent) + [IO.Path]::DirectorySeparatorChar
    $files = @(Get-ChildItem -LiteralPath $source -File -Recurse | Where-Object {
        $_.Name -match $noticePattern -and $_.Extension -notmatch $sourceExtensions
    })
    if ($package.license_file) {
        $declared = [IO.Path]::GetFullPath((Join-Path $source $package.license_file))
        if (-not $declared.StartsWith($source, [StringComparison]::OrdinalIgnoreCase) -or
            -not (Test-Path -LiteralPath $declared -PathType Leaf)) {
            $gaps += "$label : declared license file is missing or outside the package."
        } else {
            $files += Get-Item -LiteralPath $declared
        }
    }
    $paths = @()
    $issues = @()
    $hasLicense = $false
    foreach ($file in $files | Sort-Object FullName -Unique) {
        $relative = $file.FullName.Substring($source.Length).Replace('\', '/')
        $bundlePath = "cargo/$label/$relative"
        $destination = Join-Path $output $bundlePath
        $null = New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force
        Copy-Item -LiteralPath $file.FullName -Destination $destination
        $paths += $bundlePath
        if ($file.Name -match '^(LICENSE|LICENCE|COPYING|UNLICENSE)([._-].*)?$') { $hasLicense = $true }
    }

    # Reviewed, exact-version exceptions; never infer a copyright from Cargo authors.
    $supplement = $supplements.PSObject.Properties[$label]
    if ($supplement) {
        $missing = @($supplement.Value | Where-Object {
            -not (Test-Path -LiteralPath (Join-Path $output $_) -PathType Leaf)
        })
        if ($missing.Count) {
            $gaps += "$label : a reviewed supplement is missing from the bundle."
        } else {
            $paths += $supplement.Value
            $hasLicense = $true
        }
    }
    if (-not $hasLicense) {
        if ($label -eq 'realfft-3.5.0' -and $package.license -eq 'MIT') {
            # Disclose the exact known omission; do not count it as a full upstream notice.
            $notice = 'docs/licenses/REALFFT-3.5.0-NOTICE.md'
            if (Test-Path -LiteralPath (Join-Path $output $notice) -PathType Leaf) {
                $paths += $notice
                $issues += 'Upstream declares MIT but supplies no full license/copyright notice. Standard MIT terms and evidence are included; copyright attribution remains unresolved.'
                $knownGaps += "$label : $($issues[0])"
            } else {
                $gaps += "$label : required known-deficiency notice is missing."
            }
        } else {
            $gaps += "$label : no full license file found; declared license: $($package.license)"
        }
    }
    if (-not $package.license -and -not $package.license_file) { $gaps += "$label : missing Cargo license metadata." }
    $inventory += [ordered]@{
        name = $package.name
        version = $package.version
        declaredLicense = $package.license
        noticeFiles = @($paths | Sort-Object -Unique)
        noticeIssues = @($issues)
    }
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
$index = [ordered]@{
    scope = 'Locked workspace Cargo metadata, default features, all platforms; includes build/dev/tool dependencies, not a binary SBOM.'
    cargoLockSha256 = (Get-FileHash -LiteralPath (Join-Path $root 'Cargo.lock') -Algorithm SHA256).Hash.ToLowerInvariant()
    packages = $inventory
}
[IO.File]::WriteAllText((Join-Path $output 'CARGO-NOTICES.json'), ($index | ConvertTo-Json -Depth 8) + "`n", $utf8)
$review = @(
    '# Collection Review',
    '',
    'Distribute this entire directory with the matching binaries. See THIRD_PARTY.md and docs/licenses/SOURCES.md.',
    'This is a notice-file collection, not a legal clearance or a complete source-code offer.',
    'Cargo build/dev/tool and other-platform dependencies are intentionally over-included.',
    'Gradle dependencies are not collected automatically; preserve their packaged notices and review the release runtime classpath.',
    'Check embedded source notices, external native libraries, MPL source availability, and any changed dependencies before release.',
    '',
    '## Collection Errors',
    ''
)
if ($gaps.Count) { $review += $gaps | ForEach-Object { "- $_" } } else { $review += 'None detected by the filename/metadata checks (not a completeness guarantee).' }
$review += '', '## Known Upstream Notice Deficiencies', ''
if ($knownGaps.Count) { $review += $knownGaps | ForEach-Object { "- $_" } } else { $review += 'None recorded.' }
[IO.File]::WriteAllText((Join-Path $output 'REVIEW.md'), ($review -join "`n") + "`n", $utf8)
if ($gaps.Count) { throw "Collected $($inventory.Count) registry packages, but found $($gaps.Count) gaps. See REVIEW.md in the output directory; do not publish an unchecked bundle." }
if ($knownGaps.Count) { Write-Warning "Bundle collected with $($knownGaps.Count) known upstream notice deficiencies. See REVIEW.md; these are not resolved attributions or legal clearance." }
"Collected notices for $($inventory.Count) registry packages. Review REVIEW.md before distribution."
