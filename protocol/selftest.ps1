# protocol layer self-tests (ASCII-only, see build.ps1 note about BOM)
#
# Two suites:
#   1. SelfTest    - token parsing / capability mapping (pure JVM, no network)
#   2. WsSelfTest  - the hand-written RFC 6455 client against a local echo server
#                    (skipped with -NoWs, or when node/ws is unavailable)
#
# Both compile the protocol layer with a plain JDK and NO android.jar, which is
# the hard proof that protocol/ is decoupled from app/.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File protocol/selftest.ps1
#   powershell -ExecutionPolicy Bypass -File protocol/selftest.ps1 -NoWs

param(
    [switch]$NoWs
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$jdk = Join-Path $root 'toolchain\jdk'
$javac = Join-Path $jdk 'bin\javac.exe'
$java = Join-Path $jdk 'bin\java.exe'

# The bundled toolchain is optional for this script: any JDK 17+ on PATH works.
# That is what makes the self-test runnable on CI and on contributor machines
# that never ran toolchain/fetch-toolchain.ps1.
if (-not (Test-Path $javac)) {
    $onPath = Get-Command javac.exe -ErrorAction SilentlyContinue
    $javaPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath -and $javaPath) {
        $javac = $onPath.Source
        $java = $javaPath.Source
        Write-Host ("using JDK from PATH: " + $javac)
    } else {
        Write-Host "JDK not found at $jdk and no javac/java on PATH" -ForegroundColor Red
        Write-Host "run toolchain/fetch-toolchain.ps1 first, or install a JDK 17+"
        exit 2
    }
}

$out = Join-Path $root 'build\protocol-selftest'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null

$srcs = Get-ChildItem (Join-Path $root 'protocol\java') -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }

Write-Host "[1/3] compile protocol layer with plain JDK (no android.jar)"
& $javac -encoding UTF-8 -nowarn -d $out @srcs
if ($LASTEXITCODE -ne 0) { throw "protocol layer failed to compile without Android SDK" }

Write-Host "[2/3] SelfTest"
# -Ddsh.repo lets SelfTest cross-check docs/capabilities.md against the code matrix
& $java "-Dfile.encoding=UTF-8" "-Ddsh.repo=$root" -cp $out com.dshmobile.protocol.SelfTest
if ($LASTEXITCODE -ne 0) { exit 1 }

if ($NoWs) {
    Write-Host "[3/3] WsSelfTest - skipped (-NoWs)"
    Write-Host "protocol self-tests passed"
    exit 0
}

Write-Host "[3/3] WsSelfTest against a local echo server"

$node = (Get-Command node -ErrorAction SilentlyContinue)
if (-not $node) {
    Write-Host "  skipped: node not found (needed only for the echo server)"
    Write-Host "protocol self-tests passed (WS suite skipped)"
    exit 0
}

$port = 19399
$logOut = Join-Path $out 'ws-echo.log'
$logErr = Join-Path $out 'ws-echo.err'
$server = Start-Process -FilePath $node.Source `
    -ArgumentList (Join-Path $root 'protocol\test\ws-echo-server.mjs'), $port `
    -RedirectStandardOutput $logOut -RedirectStandardError $logErr `
    -WindowStyle Hidden -PassThru
Start-Sleep -Seconds 2

try {
    & $java "-Dfile.encoding=UTF-8" -cp $out com.dshmobile.protocol.WsSelfTest "ws://127.0.0.1:$port/"
    $wsCode = $LASTEXITCODE
} finally {
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
}

if ($wsCode -ne 0) {
    Write-Host "WsSelfTest failed; server stderr:"
    Get-Content $logErr -ErrorAction SilentlyContinue
    exit 1
}

Write-Host ""
Write-Host "protocol self-tests passed"
