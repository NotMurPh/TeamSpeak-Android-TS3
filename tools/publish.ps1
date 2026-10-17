# Builds, signs and publishes the next release from this machine: much faster than CI (which stays
# available as a manual workflow). Needs the release key in private/release-signing, readable only by
# the Windows user that created it, and a GitHub CLI login with access to the repository.
param(
    # Reuse the native core already built by tools/build-core.ps1.
    [switch]$SkipCore
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$repo = "NotMurPh/TeamSpeak-Android-TS3"
$assetName = "teamspeak-android-arm64-v8a.apk"
$signing = Join-Path $root "private\release-signing"

Push-Location $root
try {
    if (git status --porcelain --untracked-files=no) { throw "Commit your changes first; a release is built from a published commit." }
    git fetch -q origin main
    $commit = (git rev-parse HEAD).Trim()
    if ($commit -ne (git rev-parse origin/main).Trim()) { throw "Push main first; the release must point at a commit on GitHub." }

    # The next number after every release so far, whether CI or this script made it.
    $tags = @(gh api --paginate "repos/$repo/releases?per_page=100" --jq '.[].tag_name')
    if ($LASTEXITCODE -ne 0) { throw "Could not list releases (is gh logged in?)." }
    $last = ($tags | Where-Object { $_ -match '^build-(\d+)$' } | ForEach-Object { [int]($_ -replace '^build-', '') } | Measure-Object -Maximum).Maximum
    $n = [int]$last + 1
    $tag = "build-$n"
    $versionName = "0.1.$n"
    Write-Output "Publishing v$versionName ($tag) from $commit"

    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    if (-not $SkipCore) {
        & (Join-Path $PSScriptRoot "build-core.ps1")
        if ($LASTEXITCODE) { throw "Native core build failed." }
        if (git status --porcelain -- android/app/src/main/java) { throw "Generated bindings changed; commit them and run again." }
    }

    $data = Get-Content (Join-Path $signing "credentials.dpapi.json") -Raw | ConvertFrom-Json
    try {
        # Only this process sees the passwords; they are cleared below and never printed.
        $env:ANDROID_KEYSTORE_PATH = (Resolve-Path (Join-Path $signing "release.jks")).Path
        $env:ANDROID_KEY_ALIAS = $data.alias
        $env:ANDROID_KEYSTORE_PASSWORD = [Net.NetworkCredential]::new('', (ConvertTo-SecureString $data.passwordDpapi)).Password
        $env:ANDROID_KEY_PASSWORD = $env:ANDROID_KEYSTORE_PASSWORD
        & (Join-Path $PSScriptRoot "release.ps1") -VersionCode (1000 + $n) -VersionName $versionName -Commit $commit -Repository $repo -Tag $tag
    } finally {
        Remove-Item Env:ANDROID_KEYSTORE_PASSWORD, Env:ANDROID_KEY_PASSWORD, Env:ANDROID_KEY_ALIAS, Env:ANDROID_KEYSTORE_PATH -ErrorAction SilentlyContinue
    }

    # Release notes: this version's entry in the in-app changelog, if there is one.
    # Assigned first: Windows PowerShell passes a parsed JSON array down the pipeline as one item.
    $releases = Get-Content "android\app\src\main\assets\changelog.json" -Raw | ConvertFrom-Json
    $entry = $releases | Where-Object { $_.build -eq $n } | Select-Object -First 1
    $labels = @{ new = "New"; fixed = "Fixed"; improved = "Improved" }
    $notes = if ($entry) {
        "## $($entry.title)`n`n" + (($entry.changes | ForEach-Object { "- **$($labels[$_[0]])** $($_[1])" }) -join "`n")
    } else { "Android 8+ (API 26), arm64-v8a." }
    $notes += "`n`nInstall ``$assetName``, or update from inside the app.`n"
    $notesFile = Join-Path ([IO.Path]::GetTempPath()) "release-notes-$n.md"
    [IO.File]::WriteAllText($notesFile, $notes, (New-Object System.Text.UTF8Encoding($false)))

    $dist = Join-Path $root "dist"
    gh release create $tag -R $repo --target $commit --title "v$versionName" --notes-file $notesFile --latest `
        (Join-Path $dist $assetName) (Join-Path $dist "third-party-licenses.zip") (Join-Path $dist "SHA256SUMS") (Join-Path $dist "update.json")
    if ($LASTEXITCODE) { throw "Publishing $tag failed." }
    Remove-Item $notesFile -ErrorAction SilentlyContinue
    Write-Output "Published https://github.com/$repo/releases/tag/$tag"
} finally { Pop-Location }
