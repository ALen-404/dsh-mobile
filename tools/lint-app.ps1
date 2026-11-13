# lint-app.ps1 - cheap static checks for the Android shell
#
# Why: several classes of bug in this app compile fine and only show up on a real
# device (touching a destroyed View, leaking a WebView/socket, blocking the UI
# thread). A full linter needs Gradle + Android Lint, which this project
# deliberately does not have. These grep-level rules catch the specific mistakes
# this codebase has actually made.
#
# Rules:
#   1. every *Activity.java must tear down in onDestroy (or be allow-listed)
#   2. `new Thread` may only appear in the allow-listed files (use Task instead)
#   3. `Task.run` must be called with the Activity-aware overload (pass `this`)
#   4. files that open HttpURLConnection must do cleanup in a finally block
#   5. files that open ObserveEventStream must close it (closeStream/onDestroy)
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools/lint-app.ps1
# Exit code 0 = clean, 1 = violations.

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$srcDir = Join-Path $root 'app\src\com\dshmobile'

if (-not (Test-Path $srcDir)) {
    Write-Host "app sources not found: $srcDir"
    exit 2
}

$violations = 0
function Pass($name) {
    Write-Host ("  OK   " + $name)
}
function Fail($name, $detail) {
    Write-Host ("  FAIL " + $name)
    foreach ($d in $detail) { Write-Host ("       " + $d) }
    $script:violations++
}

$files = Get-ChildItem $srcDir -Filter *.java

# --- rule 1: activities tear down -----------------------------------------
# Allow-list = activities that genuinely hold nothing long-lived:
#   HelpActivity / MainActivity / SessionsActivity: no WebView, no stream, no
#   socket; their background work goes through Task.run(owner, ...) which skips
#   the callback once the Activity is finishing/destroyed.
$noDestroyAllowed = @('HelpActivity.java', 'MainActivity.java', 'SessionsActivity.java', 'ConnectActivity.java')
$bad = @()
foreach ($f in $files) {
    if ($f.Name -notlike '*Activity.java') { continue }
    if ($noDestroyAllowed -contains $f.Name) { continue }
    $text = Get-Content $f.FullName -Raw
    if ($text -notmatch 'protected void onDestroy\(') {
        $bad += $f.Name
    }
}
if ($bad.Count -eq 0) { Pass "rule1: activities implement onDestroy" }
else { Fail "rule1: activities implement onDestroy" $bad }

# --- rule 2: raw threads are centralised ----------------------------------
$threadAllowed = @('Task.java', 'Preflight.java')
$bad = @()
foreach ($f in $files) {
    $hits = Select-String -Path $f.FullName -Pattern 'new Thread\(' -ErrorAction SilentlyContinue
    if ($hits -and ($threadAllowed -notcontains $f.Name)) {
        $bad += ($f.Name + " (" + $hits.Count + "x) -> use Task.run instead")
    }
}
if ($bad.Count -eq 0) { Pass "rule2: no ad-hoc threads outside Task/Preflight" }
else { Fail "rule2: no ad-hoc threads outside Task/Preflight" $bad }

# --- rule 3: Task.run passes the Activity --------------------------------
$bad = @()
foreach ($f in $files) {
    $hits = Select-String -Path $f.FullName -Pattern 'Task\.run\("' -ErrorAction SilentlyContinue
    foreach ($h in $hits) {
        $bad += ($f.Name + ":" + $h.LineNumber + " -> Task.run(owner, ...) required")
    }
}
if ($bad.Count -eq 0) { Pass "rule3: Task.run always gets an Activity owner" }
else { Fail "rule3: Task.run always gets an Activity owner" $bad }

# --- rule 4: HttpURLConnection cleanup in finally ------------------------
$bad = @()
foreach ($f in $files) {
    $text = Get-Content $f.FullName -Raw
    if ($text -notmatch 'HttpURLConnection') { continue }
    if ($text -notmatch 'finally') {
        $bad += ($f.Name + " -> disconnect() must be in finally")
    }
}
if ($bad.Count -eq 0) { Pass "rule4: HttpURLConnection cleanup in finally" }
else { Fail "rule4: HttpURLConnection cleanup in finally" $bad }

# --- rule 5: event streams are closed ------------------------------------
$bad = @()
foreach ($f in $files) {
    $text = Get-Content $f.FullName -Raw
    if ($text -notmatch 'ObserveEventStream\.open') { continue }
    if (($text -notmatch '\.close\(\)') -and ($text -notmatch 'closeStream')) {
        $bad += ($f.Name + " -> opened a stream but never closes it")
    }
}
if ($bad.Count -eq 0) { Pass "rule5: ObserveEventStream is closed" }
else { Fail "rule5: ObserveEventStream is closed" $bad }

# --- rule 6: WebView owners must handle renderer death -------------------
# On API 26+ returning false from onRenderProcessGone makes the system KILL the
# whole app, which users see as "opens then crashes". Must be handled explicitly.
$bad = @()
foreach ($f in $files) {
    $text = Get-Content $f.FullName -Raw
    if ($text -notmatch 'new WebView\(') { continue }
    if ($text -notmatch 'onRenderProcessGone') {
        $bad += ($f.Name + " -> must override onRenderProcessGone and return true")
    }
}
if ($bad.Count -eq 0) { Pass "rule6: WebView renderer death handled" }
else { Fail "rule6: WebView renderer death handled" $bad }

Write-Host ""
if ($violations -eq 0) {
    Write-Host "== app lint ok ==" -ForegroundColor Green
    exit 0
}
Write-Host ("== app lint: " + $violations + " rule(s) violated ==") -ForegroundColor Red
exit 1
