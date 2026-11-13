# dev-instance.ps1 - boot a THROWAWAY, fully isolated DSH instance
#
# Why: verifying the protocol layer (and any native feature) needs a real DSH
# that prints a launch token. Touching the user's own instance is not acceptable,
# so this script clones the installed profile into a temporary DSH_HOME, remaps
# the port, and boots it there.
#
# Isolation guarantees:
#   * DSH_HOME points at a temp directory -> storages/sessions/logs are separate
#   * the profile is a COPY under a different name -> the original is untouched
#   * PORT is remapped   (the desktop profile pins 19387; the real instance owns it)
#   * known-incompatible plugins are disabled in the copy
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools/dev-instance.ps1
#   powershell -ExecutionPolicy Bypass -File tools/dev-instance.ps1 -Port 21387
#   powershell -ExecutionPolicy Bypass -File tools/dev-instance.ps1 -Stop
#
# Prints the launch URL (with ?token=) on success. ASCII-only on purpose: PS 5.1
# reads .ps1 without a BOM as ANSI and would choke on non-ASCII comments.

param(
    [int]$Port = 21387,
    [string]$ProfileName = 'probe',
    [string]$HomePath = (Join-Path ([IO.Path]::GetTempPath()) 'dsh-dev-home'),
    [string]$DshPath = '',
    [switch]$Stop,
    [int]$BootTimeoutSec = 60
)

$ErrorActionPreference = 'Continue'

function Stop-DevInstance {
    param([int]$p)
    $conns = netstat -ano | Select-String 'LISTENING' | Select-String (":$p\b")
    foreach ($line in $conns) {
        $procId = ($line.ToString() -split '\s+')[-1]
        if ($procId -match '^\d+$') {
            Write-Host "  stopping pid $procId on port $p"
            Stop-Process -Id ([int]$procId) -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep -Seconds 1
}

if ($Stop) {
    Stop-DevInstance -p $Port
    if (Test-Path $HomePath) {
        Remove-Item $HomePath -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host "  removed $HomePath"
    }
    Write-Host "dev instance stopped"
    exit 0
}

$repo = Split-Path -Parent $PSScriptRoot
$srcHome = Join-Path $env:USERPROFILE '.dsh'
$srcProfile = Join-Path $srcHome 'profiles\desktop'
$dstProfile = Join-Path $HomePath "profiles\$ProfileName"

if (-not (Test-Path $srcProfile)) { throw "desktop profile not found: $srcProfile" }

Write-Host "[1/5] clone profile -> $dstProfile"
Stop-DevInstance -p $Port
Remove-Item $HomePath -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path (Join-Path $HomePath 'profiles') | Out-Null
robocopy $srcProfile $dstProfile /E /NFL /NDL /NJH /NJS /R:1 /W:1 | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy failed ($LASTEXITCODE)" }
$m = Get-ChildItem $dstProfile -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum
Write-Host ("      " + $m.Count + " files / " + [math]::Round($m.Sum / 1MB, 1) + " MB")

Write-Host "[2/5] patch the copy (port + known-bad plugins)"
$patchPath = Join-Path $dstProfile 'cordis.patch.yml'
$node = (Get-Command node -ErrorAction SilentlyContinue)
if (-not $node) { throw "node is required to patch the copied profile" }
$patchScript = @'
const fs=require("fs");
const path=process.argv[2], port=process.argv[3];
let s=fs.readFileSync(path,"utf8");
// remap the pinned webserver port so it does not collide with the real instance
s=s.replace(/port:\s*19387/, "port: "+port);
// disable plugins known to fail to import on this DSH build
if(!/disabled: true[\s\S]{0,80}?dsh-chat-import/.test(s)){
  s=s.replace(/(- id: import-claude\n  name: dsh-chat-import\n)/, "$1  disabled: true\n");
}
fs.writeFileSync(path,s,"utf8");
console.log("      patched");
'@
$tmp = Join-Path $env:TEMP 'dsh-dev-patch.js'
[IO.File]::WriteAllText($tmp, $patchScript, (New-Object Text.UTF8Encoding($false)))
& $node.Source $tmp $patchPath $Port

Write-Host "[3/5] boot (DSH_HOME=$HomePath, port $Port)"
# Resolve the dsh launcher. Pass -DshPath if it is somewhere unusual.
$dsh = $DshPath
if (-not $dsh) {
    $onPath = Get-Command dsh.cmd -ErrorAction SilentlyContinue
    if ($onPath) { $dsh = $onPath.Source }
}
if (-not $dsh) {
    # typical Windows desktop install locations
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs\@deepseek-aidsh-desktop\resources\runtime\cli\bin\dsh.cmd'),
        (Join-Path $env:PROGRAMFILES '@deepseek-aidsh-desktop\resources\runtime\cli\bin\dsh.cmd')
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { $dsh = $c; break }
    }
}
if (-not $dsh -or -not (Test-Path $dsh)) {
    Write-Host "  cannot find dsh.cmd"
    Write-Host "  pass it explicitly:  -DshPath '<...>\resources\runtime\cli\bin\dsh.cmd'"
    exit 1
}
Write-Host ("      dsh = " + $dsh)
# keep the log INSIDE the throwaway home: build/ is wiped by build.ps1, and the log
# must outlive that (it holds the launch token for this instance).
$log = Join-Path $HomePath 'dev-instance.log'
Remove-Item $log -ErrorAction SilentlyContinue
$inner = "`$env:DSH_HOME='$HomePath'; & '$dsh' --profile $ProfileName --no-open --host 127.0.0.1 --port 0 *> '$log'"
Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile','-Command',$inner) -WindowStyle Hidden

Write-Host "[4/5] wait for the launch token ..."
$deadline = (Get-Date).AddSeconds($BootTimeoutSec)
$tokenUrl = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 800
    if (Test-Path $log) {
        $m2 = Select-String -Path $log -Pattern 'dsh web: (\S+)' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($m2) { $tokenUrl = $m2.Matches[0].Groups[1].Value; break }
    }
}
if (-not $tokenUrl) {
    Write-Host "  FAILED to obtain a token. Last log lines:"
    if (Test-Path $log) { Get-Content $log -Tail 25 | ForEach-Object { Write-Host ("    " + $_) } }
    exit 1
}

Write-Host "[5/5] ready"
Write-Host ""
Write-Host "  TOKEN_URL=$tokenUrl"
Write-Host ""
Write-Host "  verify:  node protocol/probe.mjs --token-url `"$tokenUrl`""
Write-Host "  stop:    powershell -ExecutionPolicy Bypass -File tools/dev-instance.ps1 -Stop -Port $Port"
