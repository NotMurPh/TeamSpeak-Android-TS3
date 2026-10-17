# Collects notices, builds and verifies a signed APK, then writes public release assets.
param(
    [Parameter(Mandatory)][ValidateRange(1, 2100000000)][int]$VersionCode,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$VersionName,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$Commit,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')][string]$Repository,
    [Parameter(Mandatory)][ValidatePattern('^build-[1-9][0-9]*$')][string]$Tag,
    [string]$OutputDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) "dist")
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$applicationId = "com.tsrewrite.client"
$assetName = "teamspeak-android-arm64-v8a.apk"
foreach ($name in @("ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) { throw "Release signing requires $name." }
}
if (-not (Test-Path -LiteralPath $env:ANDROID_KEYSTORE_PATH -PathType Leaf)) { throw "ANDROID_KEYSTORE_PATH is not a keystore file." }
if (-not $env:ANDROID_HOME) { throw "ANDROID_HOME must point to the Android SDK." }
$buildTools = Join-Path $env:ANDROID_HOME "build-tools\36.0.0"
foreach ($tool in @("apksigner.bat", "aapt2.exe", "zipalign.exe")) {
    if (-not (Test-Path -LiteralPath (Join-Path $buildTools $tool))) { throw "Missing pinned Android build tool: $tool (36.0.0)." }
}
$readelf = Join-Path $env:ANDROID_HOME "ndk\29.0.14206865\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
if (-not (Test-Path -LiteralPath $readelf)) { throw "Missing llvm-readelf from NDK 29.0.14206865." }
if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) { throw "Java 21 keytool must be on PATH." }

$releaseAssets = Join-Path $root "target\release-assets"
$licensesDirectory = Join-Path $releaseAssets "licenses"
$utf8 = New-Object System.Text.UTF8Encoding($false)
Push-Location $root
try {
    cargo fetch --locked
    if ($LASTEXITCODE -ne 0) { throw "Could not fetch locked dependency sources for notice collection." }
    New-Item -ItemType Directory -Path $releaseAssets -Force | Out-Null
    # Only this script's fixed generated bundle is replaced, never the collector's default output.
    if (Test-Path -LiteralPath $licensesDirectory) { Remove-Item -LiteralPath $licensesDirectory -Recurse -Force }
    & (Join-Path $PSScriptRoot "collect-licenses.ps1") -OutputDirectory $licensesDirectory
} finally { Pop-Location }
$sourceLocations = @(
    "# Matching Source Locations",
    "",
    "Repository: $Repository",
    "Release commit: $Commit",
    "Project source, vendored code, and covered local changes: https://github.com/$Repository/tree/$Commit",
    "Source archive: https://github.com/$Repository/archive/$Commit.zip",
    "",
    "UniFFI upstream source details and MPL-2.0 terms: docs/licenses/UNIFFI-NOTICE.md and docs/licenses/MPL-2.0.txt.",
    "Exact published UniFFI crate source archives (versions from CARGO-NOTICES.json):"
)
$inventory = Get-Content -LiteralPath (Join-Path $licensesDirectory "CARGO-NOTICES.json") -Raw | ConvertFrom-Json
foreach ($package in $inventory.packages | Where-Object { $_.name -eq "uniffi" -or $_.name -like "uniffi_*" }) {
    $sourceLocations += "- $($package.name) $($package.version): https://crates.io/api/v1/crates/$($package.name)/$($package.version)/download"
}
[IO.File]::WriteAllText((Join-Path $licensesDirectory "SOURCE-LOCATION.md"), (($sourceLocations -join "`n") + "`n"), $utf8)

Push-Location (Join-Path $root "android")
try {
    & .\gradlew.bat --no-daemon --console=plain :app:assembleRelease "-PciVersionCode=$VersionCode" "-PciVersionName=$VersionName"
    if ($LASTEXITCODE -ne 0) {
        # Gradle prints only the first lint error; show them all so one CI run is enough to fix them.
        Get-ChildItem -Path "app/build/intermediates/lint_intermediate_text_report" -Filter "lint-results-*.txt" -Recurse -ErrorAction SilentlyContinue |
            ForEach-Object { Get-Content -LiteralPath $_.FullName }
        throw "Gradle release lint/build failed."
    }
} finally { Pop-Location }
$apk = Join-Path $root "android\app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path -LiteralPath $apk)) { throw "Gradle did not produce app-release.apk." }

$signature = & (Join-Path $buildTools "apksigner.bat") verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed." }
$certificateDigests = @($signature | ForEach-Object {
    if ($_ -match '^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$') { $matches[1].ToLowerInvariant() }
})
if ($certificateDigests.Count -ne 1) { throw "Expected exactly one APK signing certificate." }
$certificateSha256 = $certificateDigests[0]
$keyCertificate = & keytool "-J-Duser.language=en" "-J-Duser.country=US" -list -v -keystore $env:ANDROID_KEYSTORE_PATH -alias $env:ANDROID_KEY_ALIAS -storepass:env ANDROID_KEYSTORE_PASSWORD
if ($LASTEXITCODE -ne 0) { throw "Could not inspect the configured release certificate." }
$keyDigests = @($keyCertificate | ForEach-Object {
    if ($_ -match '^\s*SHA256:\s*([0-9a-fA-F:]+)\s*$') { $matches[1].Replace(":", "").ToLowerInvariant() }
})
if ($keyDigests.Count -ne 1 -or $keyDigests[0] -ne $certificateSha256) { throw "APK signer does not match the dedicated release key." }

$badging = & (Join-Path $buildTools "aapt2.exe") dump badging $apk
if ($LASTEXITCODE -ne 0) { throw "Could not inspect the APK manifest." }
$badgingText = $badging -join "`n"
if ($badgingText -notmatch "(?m)^package: name='$([regex]::Escape($applicationId))' versionCode='$VersionCode' versionName='$([regex]::Escape($VersionName))'" -or
    $badgingText -notmatch "(?m)^(min)?[sS]dkVersion:'26'\s*$" -or
    $badgingText -notmatch "(?m)^native-code: 'arm64-v8a'\s*$" -or
    $badgingText -match '(?m)^application-debuggable') {
    throw "APK package, version, minSdk, ABI, or non-debuggable validation failed."
}
& (Join-Path $buildTools "zipalign.exe") -c -P 16 -v 4 $apk
if ($LASTEXITCODE -ne 0) { throw "APK ZIP/16 KB native library alignment verification failed." }

Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($apk)
$nativeCheckDirectory = Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
try {
    $entries = @($archive.Entries.FullName)
    foreach ($required in @("lib/arm64-v8a/libtscore.so", "lib/arm64-v8a/libjnidispatch.so", "assets/licenses/THIRD_PARTY.md", "assets/licenses/SOURCE-LOCATION.md")) {
        if ($required -notin $entries) { throw "Missing required APK entry: $required" }
    }
    if (@($entries | Where-Object { $_ -match 'libteamspeak_client' }).Count) { throw "Official libteamspeak_client must not be packaged." }
    $nativeLibraries = @($archive.Entries | Where-Object { $_.FullName -match '\.so$' })
    New-Item -ItemType Directory -Path $nativeCheckDirectory | Out-Null
    foreach ($library in $nativeLibraries) {
        if ($library.FullName -notmatch '^lib/arm64-v8a/[^/]+\.so$') { throw "Unexpected native library path: $($library.FullName)" }
        $extracted = Join-Path $nativeCheckDirectory $library.Name
        [IO.Compression.ZipFileExtensions]::ExtractToFile($library, $extracted)
        $elf = & $readelf --file-header --program-headers --wide $extracted
        if ($LASTEXITCODE -ne 0 -or ($elf -join "`n") -notmatch 'Machine:\s+AArch64') { throw "Invalid arm64 ELF: $($library.Name)" }
        $loads = @($elf | Where-Object { $_ -match '^\s*LOAD\s' })
        if (-not $loads.Count) { throw "No ELF LOAD segments in $($library.Name)." }
        foreach ($load in $loads) {
            if ($load -notmatch '\s0x([0-9a-fA-F]+)\s*$' -or [Convert]::ToInt64($matches[1], 16) -lt 16384) {
                throw "ELF LOAD segment is not 16 KB aligned: $($library.Name)"
            }
        }
    }
} finally {
    $archive.Dispose()
    if (Test-Path -LiteralPath $nativeCheckDirectory) { Remove-Item -LiteralPath $nativeCheckDirectory -Recurse -Force }
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$destination = Join-Path $OutputDirectory $assetName
Copy-Item -LiteralPath $apk -Destination $destination -Force
$sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
$licenseZip = Join-Path $OutputDirectory "third-party-licenses.zip"
if (Test-Path -LiteralPath $licenseZip) { Remove-Item -LiteralPath $licenseZip -Force }
$licenseArchive = [IO.Compression.ZipFile]::Open($licenseZip, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in Get-ChildItem -LiteralPath $licensesDirectory -File -Recurse) {
        $entryName = "licenses/" + $file.FullName.Substring($licensesDirectory.Length + 1).Replace("\", "/")
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($licenseArchive, $file.FullName, $entryName, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $licenseArchive.Dispose() }
$licenseSha256 = (Get-FileHash -LiteralPath $licenseZip -Algorithm SHA256).Hash.ToLowerInvariant()
$metadata = [ordered]@{
    schemaVersion = 1
    versionCode = $VersionCode
    versionName = $VersionName
    applicationId = $applicationId
    minSdk = 26
    abi = "arm64-v8a"
    commit = $Commit
    sha256 = $sha256
    certificateSha256 = $certificateSha256
    downloadUrl = "https://github.com/$Repository/releases/download/$Tag/$assetName"
}
$metadataPath = Join-Path $OutputDirectory "update.json"
[IO.File]::WriteAllText($metadataPath, (($metadata | ConvertTo-Json) + "`n"), $utf8)
$metadataSha256 = (Get-FileHash -LiteralPath $metadataPath -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $OutputDirectory "SHA256SUMS"), "$sha256  $assetName`n$licenseSha256  third-party-licenses.zip`n$metadataSha256  update.json`n", $utf8)
Write-Output "Verified release $VersionName ($VersionCode), signing certificate SHA-256: $certificateSha256"
