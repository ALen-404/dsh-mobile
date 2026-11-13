# dsh-mobile build script
#
# Deliberately Gradle-free: only uses the Android SDK's aapt2 / d8 / zipalign / apksigner.
# No Maven dependencies, fully reproducible offline. Toolchain comes from
# toolchain/fetch-toolchain.ps1.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File build.ps1
#
# Output:
#   dist/dsh-mobile-debug.apk
#
# NOTE: this file is ASCII-only on purpose. Windows PowerShell 5.1 reads a .ps1
# without a BOM as ANSI, so non-ASCII comments break the parser. Keep it ASCII.

param(
    [switch]$Release,
    [string]$OutName = ""
)

# Continue, not Stop: native tools (keytool/javac) write informational text to
# stderr and Stop would treat that as fatal. Real failures are caught via the
# explicit $LASTEXITCODE checks below.
$ErrorActionPreference = 'Continue'

# Fail with ONE readable line instead of a PowerShell stack trace. Every `throw`
# in this script lands here.
trap {
    Write-Host ""
    Write-Host ("ERROR: " + $_.Exception.Message) -ForegroundColor Red
    exit 1
}

$root = $PSScriptRoot
$toolchain = Join-Path $root 'toolchain'
$jdk = Join-Path $toolchain 'jdk'
$sdk = Join-Path $toolchain 'sdk'
$bt = Join-Path $sdk 'build-tools\34.0.0'
$androidJar = Join-Path $sdk 'platforms\android-34\android.jar'

$app = Join-Path $root 'app'
$res = Join-Path $app 'res'
$src = Join-Path $app 'src'
$manifest = Join-Path $app 'AndroidManifest.xml'
$build = Join-Path $root 'build'
$dist = Join-Path $root 'dist'

function Need($path, $what) {
    if (-not (Test-Path $path)) { throw "missing $what : $path  (run toolchain/fetch-toolchain.ps1 first)" }
}

Need (Join-Path $jdk 'bin\javac.exe') 'JDK'
Need $androidJar 'Android platform android.jar'
Need (Join-Path $bt 'aapt2.exe') 'build-tools aapt2'
Need (Join-Path $bt 'lib\d8.jar') 'build-tools d8'
Need (Join-Path $bt 'zipalign.exe') 'build-tools zipalign'
Need (Join-Path $bt 'lib\apksigner.jar') 'build-tools apksigner'

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"
$java = Join-Path $jdk 'bin\java.exe'
$javac = Join-Path $jdk 'bin\javac.exe'
$keytool = Join-Path $jdk 'bin\keytool.exe'
$jarTool = Join-Path $jdk 'bin\jar.exe'
$aapt2 = Join-Path $bt 'aapt2.exe'
$zipalign = Join-Path $bt 'zipalign.exe'
$d8Jar = Join-Path $bt 'lib\d8.jar'
$apksignerJar = Join-Path $bt 'lib\apksigner.jar'

$minSdk = 21
$targetSdk = 34

Write-Host "== dsh-mobile build ==" -ForegroundColor Cyan

# --- clean ---------------------------------------------------------------
Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $build, $dist,
    (Join-Path $build 'gen'), (Join-Path $build 'classes'), (Join-Path $build 'dex') | Out-Null

# --- 1. compile resources ------------------------------------------------
Write-Host "[1/7] aapt2 compile"
$resZip = Join-Path $build 'res.zip'
& $aapt2 compile --dir $res -o $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

# --- 2. link resources + generate R.java ---------------------------------
Write-Host "[2/7] aapt2 link"
$unsigned = Join-Path $build 'app-unsigned.apk'
& $aapt2 link -o $unsigned -I $androidJar --manifest $manifest `
    --min-sdk-version $minSdk --target-sdk-version $targetSdk `
    --java (Join-Path $build 'gen') $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# --- 3. compile java -----------------------------------------------------
Write-Host "[3/7] javac"
$sources = @()
$sources += Get-ChildItem (Join-Path $build 'gen') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }

# protocol layer lives in its own source root (no Android deps, reusable)
$protocolJava = Join-Path $root 'protocol\java'
if (Test-Path $protocolJava) {
    # self-test mains are JVM-only, never shipped in the APK
    $sources += Get-ChildItem $protocolJava -Recurse -Filter *.java |
        Where-Object { $_.Name -notin @('SelfTest.java', 'WsSelfTest.java', 'LiveSelfTest.java') } |
        ForEach-Object { $_.FullName }
}

$classes = Join-Path $build 'classes'
# javac writes deprecation NOTES to stderr even with -nowarn; redirect them to a
# file so a successful build does not look like a failure to CI / the caller.
$javacErr = Join-Path $build 'javac.err'
& $javac --release 8 -encoding UTF-8 -nowarn -Xlint:none -classpath $androidJar -d $classes @sources 2> $javacErr
if ($LASTEXITCODE -ne 0) {
    Write-Host "javac output:"
    Get-Content $javacErr -ErrorAction SilentlyContinue | ForEach-Object { Write-Host ("  " + $_) }
    throw "javac failed"
}
$noteLines = @(Get-Content $javacErr -ErrorAction SilentlyContinue)
if ($noteLines.Count -gt 0) {
    Write-Host ("      (" + $noteLines.Count + " javac notes, ignored)")
}

# --- 4. dex --------------------------------------------------------------
Write-Host "[4/7] d8"
$dexOut = Join-Path $build 'dex'
$classesJar = Join-Path $build 'classes.jar'
& $jarTool cf $classesJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& $java -cp $d8Jar com.android.tools.r8.D8 --release --min-api $minSdk `
    --lib $androidJar --output $dexOut $classesJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# --- 5. pack classes.dex into the apk ------------------------------------
Write-Host "[5/7] pack dex"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
$dexFile = Join-Path $dexOut 'classes.dex'
$entryName = 'classes.dex'
$existing = $zip.Entries | Where-Object { $_.FullName -eq $entryName }
if ($existing) { $existing.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $dexFile, $entryName) | Out-Null
$zip.Dispose()

# --- 6. align ------------------------------------------------------------
Write-Host "[6/7] zipalign"
$aligned = Join-Path $build 'app-aligned.apk'
& $zipalign -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

# --- 7. sign -------------------------------------------------------------
Write-Host "[7/7] apksigner"
$keystore = Join-Path $toolchain 'debug.keystore'
if (-not (Test-Path $keystore)) {
    Write-Host "      creating debug keystore"
    & $keytool -genkeypair -keystore $keystore -alias debug -storepass android -keypass android `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=DSH Mobile (unofficial), O=DSH Mobile" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
if (-not $OutName) {
    $OutName = if ($Release) { 'dsh-mobile-release.apk' } else { 'dsh-mobile-debug.apk' }
}
$final = Join-Path $dist $OutName
& $java -jar $apksignerJar sign --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --min-sdk-version $minSdk --out $final $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

& $java -jar $apksignerJar verify --min-sdk-version $minSdk $final
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

$size = [math]::Round((Get-Item $final).Length / 1KB, 1)
Write-Host ""
Write-Host "== build ok ==" -ForegroundColor Green
Write-Host "   $final  ($size KB)"

# Explicit success exit: without it PowerShell can propagate a non-zero code from
# an earlier native command's stderr, and CI would read "build ok" as a failure.
exit 0
