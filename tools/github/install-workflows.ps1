# install-workflows.ps1 - copy tools/github/*.yml into .github/workflows and push
#
# Why this exists: pushing a workflow file needs the `workflow` OAuth scope.
# If your gh token lacks it, run `gh auth refresh -s workflow` first; this script
# checks that and tells you what to do instead of failing with a cryptic git error.
#
# NOTE: this file is ASCII-only on purpose (Windows PowerShell 5.1 reads a .ps1
# without a BOM as ANSI, so non-ASCII text breaks the parser).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools/github/install-workflows.ps1
#   powershell -ExecutionPolicy Bypass -File tools/github/install-workflows.ps1 -NoPush

param(
    [switch]$NoPush
)

$ErrorActionPreference = 'Continue'
$here = $PSScriptRoot
$root = Split-Path -Parent (Split-Path -Parent $here)
$target = Join-Path $root '.github\workflows'

trap {
    Write-Host ""
    Write-Host ("ERROR: " + $_.Exception.Message) -ForegroundColor Red
    exit 1
}

Push-Location $root
try {
    Write-Host "[1/4] check gh auth scopes"
    $auth = gh auth status 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "gh is not authenticated (run: gh auth login)" }
    $hasWorkflow = $auth -match 'workflow'
    if (-not $hasWorkflow) {
        Write-Host "      token lacks the 'workflow' scope." -ForegroundColor Yellow
        Write-Host "      run this once, then re-run this script:" -ForegroundColor Yellow
        Write-Host "        gh auth refresh -s workflow" -ForegroundColor Yellow
        exit 2
    }
    Write-Host "      ok"

    Write-Host "[2/4] copy workflow files"
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    $files = @('ci.yml', 'release.yml')
    foreach ($f in $files) {
        $src = Join-Path $here $f
        if (-not (Test-Path $src)) { throw "missing $src" }
        Copy-Item $src (Join-Path $target $f) -Force
        Write-Host ("      " + $f)
    }

    Write-Host "[3/4] commit"
    git add .github/workflows
    git commit -q -m "ci: enable GitHub Actions (checks + APK build + tag release)"
    if ($LASTEXITCODE -ne 0) { Write-Host "      (nothing to commit)" }
    Write-Host ("      " + (git log --oneline -1))

    if ($NoPush) {
        Write-Host "[4/4] skipped push (-NoPush)"
    } else {
        Write-Host "[4/4] push"
        git push
        if ($LASTEXITCODE -ne 0) { throw "push failed" }
    }
    Write-Host ""
    Write-Host "done. check the Actions tab on your repo." -ForegroundColor Green
} finally {
    Pop-Location
}
