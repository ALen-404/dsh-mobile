# dsh-mobile toolchain fetcher
#
# Gets a minimal, Gradle-free Android build toolchain:
#   - JDK 17                          (Adoptium API, latest GA)
#   - Android build-tools 34.0.0      (aapt2 / d8 / zipalign / apksigner)
#   - Android platform android.jar    (API 34)
#
# Why not sdkmanager? On restricted networks sdkmanager often cannot fetch its
# repository index ("Failed to download any source lists"), and honoring an HTTP
# proxy from Java is unreliable. Plain curl + the published zips always work, so
# this script downloads the two zips directly and lays them out by hand.
#
# NOTE: the platform zip is named `platform-34-ext12_r01.zip` in Google's repo
# (the plain `platform-34_rNN.zip` names 404 as of 2026-11). That package still
# contains a full android.jar, which is all we need.
#
# This file is deliberately ASCII-only: Windows PowerShell 5.1 reads .ps1 files
# without a BOM as ANSI, which mangles non-ASCII comments into parse errors.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File toolchain/fetch-toolchain.ps1

$ErrorActionPreference = 'Continue'

# Fail with one readable line instead of a PowerShell stack trace.
trap {
    Write-Host ""
    Write-Host ("ERROR: " + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
$root = Split-Path -Parent $PSScriptRoot
$tc = Join-Path $root 'toolchain'
$dl = Join-Path $tc 'dl'
$sdk = Join-Path $tc 'sdk'
New-Item -ItemType Directory -Force -Path $dl | Out-Null

function Get-File($url, $dest) {
    if ((Test-Path $dest) -and (Get-Item $dest).Length -gt 1MB) {
        Write-Host ("  cached: " + (Split-Path $dest -Leaf))
        return
    }
    Write-Host ("  downloading: " + $url)
    curl.exe -sS -L --retry 3 --retry-delay 2 -m 1800 -o $dest $url
    if (-not (Test-Path $dest)) { throw ("download failed: " + $url) }
}

# --- 1. JDK ----------------------------------------------------------------
$jdkDir = Join-Path $tc 'jdk'
if (-not (Test-Path (Join-Path $jdkDir 'bin\javac.exe'))) {
    Write-Host '[1/3] JDK 17'
    $zip = Join-Path $dl 'jdk17.zip'
    Get-File 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk' $zip
    Expand-Archive $zip -DestinationPath $tc -Force
    $inner = Get-ChildItem $tc -Directory | Where-Object { $_.Name -like 'jdk-17*' } | Select-Object -First 1
    if (-not $inner) { throw 'unexpected JDK archive layout' }
    if (Test-Path $jdkDir) { Remove-Item $jdkDir -Recurse -Force }
    Rename-Item $inner.FullName $jdkDir
} else { Write-Host '[1/3] JDK 17 - already present' }

# --- 2. build-tools 34.0.0 -------------------------------------------------
$btDir = Join-Path $sdk 'build-tools\34.0.0'
if (-not (Test-Path (Join-Path $btDir 'aapt2.exe'))) {
    Write-Host '[2/3] Android build-tools 34.0.0'
    $zip = Join-Path $dl 'build-tools_r34-windows.zip'
    Get-File 'https://dl.google.com/android/repository/build-tools_r34-windows.zip' $zip
    $tmp = Join-Path $tc 'bt-tmp'
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Archive $zip -DestinationPath $tmp -Force
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $btDir) | Out-Null
    if (Test-Path $btDir) { Remove-Item $btDir -Recurse -Force }
    Move-Item $inner.FullName $btDir
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
} else { Write-Host '[2/3] build-tools 34.0.0 - already present' }

# --- 3. platform (android.jar) --------------------------------------------
$platDir = Join-Path $sdk 'platforms\android-34'
if (-not (Test-Path (Join-Path $platDir 'android.jar'))) {
    Write-Host '[3/3] Android platform 34 (android.jar)'
    $zip = Join-Path $dl 'platform-34-ext12_r01.zip'
    Get-File 'https://dl.google.com/android/repository/platform-34-ext12_r01.zip' $zip
    $tmp = Join-Path $tc 'pf-tmp'
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Archive $zip -DestinationPath $tmp -Force
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $platDir) | Out-Null
    if (Test-Path $platDir) { Remove-Item $platDir -Recurse -Force }
    Move-Item $inner.FullName $platDir
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
} else { Write-Host '[3/3] platform 34 - already present' }

Write-Host ''
Write-Host 'Toolchain ready. Now run: build.ps1'
