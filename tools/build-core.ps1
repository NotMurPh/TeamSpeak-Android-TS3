# Builds the Rust core for Android and regenerates the Kotlin bindings.
# Usage: powershell -File tools/build-core.ps1 [-Abis arm64-v8a,x86_64]
param([string[]]$Abis = @("arm64-v8a"))
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$ndkVersion = "29.0.14206865"
if (-not $Abis.Count -or @($Abis | Where-Object { $_ -notin @("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }).Count) {
    throw "Specify supported Android ABIs as a PowerShell string array (default: arm64-v8a)."
}

$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
if (-not (Test-Path -LiteralPath $vswhere)) { throw "Visual Studio Installer/vswhere is required for the MSVC host build." }
$vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if ($LASTEXITCODE -ne 0 -or -not $vs) { throw "Visual Studio with the x64 C++ toolchain is required." }
$devCmd = Join-Path $vs "Common7\Tools\VsDevCmd.bat"
if (-not (Test-Path -LiteralPath $devCmd)) { throw "Visual Studio developer environment was not found: $devCmd" }
$developerEnvironment = & $env:ComSpec /d /s /c ('"{0}" -no_logo -arch=x64 -host_arch=x64 >nul && set' -f $devCmd)
if ($LASTEXITCODE -ne 0) { throw "Could not initialize the Visual Studio x64 developer environment." }
foreach ($line in $developerEnvironment) {
    if ($line -match '^([^=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1], $matches[2], "Process") }
}
$env:Path = "$env:USERPROFILE\.cargo\bin;$vs\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin;$vs\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja;$env:Path"
foreach ($command in @("cargo", "cmake", "ninja")) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Required build tool not found: $command" }
}
& cargo ndk --version
if ($LASTEXITCODE -ne 0) { throw "cargo-ndk is required; install with cargo install cargo-ndk --version 4.1.2 --locked." }
$env:CMAKE_GENERATOR = "Ninja"  # opus is built with CMake; the VS generator cannot target Android
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
}
$ndk = Join-Path $env:ANDROID_HOME "ndk\$ndkVersion"
$ndkProperties = Join-Path $ndk "source.properties"
if (-not (Test-Path -LiteralPath $ndkProperties) -or
    -not ((Get-Content -LiteralPath $ndkProperties) -match "^Pkg.Revision\s*=\s*$([regex]::Escape($ndkVersion))\s*$") -or
    -not (Test-Path -LiteralPath (Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe"))) {
    throw "Install the pinned NDK with sdkmanager 'ndk;$ndkVersion' under ANDROID_HOME ($env:ANDROID_HOME)."
}
$env:ANDROID_NDK_HOME = $ndk; $env:ANDROID_NDK_ROOT = $ndk; $env:ANDROID_NDK = $ndk

Push-Location $root
try {
    $targets = $Abis | ForEach-Object { "-t"; $_ }
    cargo ndk @targets -P 26 -o android/app/src/main/jniLibs build --release --locked -p tscore
    if ($LASTEXITCODE -ne 0) { throw "cargo ndk failed" }
    foreach ($abi in $Abis) {
        $library = Join-Path $root "android/app/src/main/jniLibs/$abi/libtscore.so"
        if (-not (Test-Path -LiteralPath $library) -or (Get-Item -LiteralPath $library).Length -eq 0) {
            throw "cargo ndk did not produce the expected native library: $library"
        }
    }
    # Bindings come from the unstripped host build; the release .so has its metadata stripped.
    cargo build --locked -q -p tscore
    if ($LASTEXITCODE -ne 0) { throw "host build failed" }
    # Cargo metadata discovers core/uniffi.toml; --config expects a global config in UniFFI 0.32.
    cargo run --locked -q -p uniffi-bindgen -- generate --library target/debug/tscore.dll --language kotlin --out-dir android/app/src/main/java --no-format
    if ($LASTEXITCODE -ne 0) { throw "uniffi-bindgen failed" }
} finally { Pop-Location }
