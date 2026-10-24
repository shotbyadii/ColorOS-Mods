$ErrorActionPreference = "Stop"

$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt = "$sdk\build-tools\36.0.0"
$adb = "$sdk\platform-tools\adb.exe"
$androidJar = "$sdk\platforms\android-36\android.jar"

$repoRoot = (Get-Item $PSScriptRoot).Parent.FullName
$dialerDir = "$repoRoot\odialer-video-ringtone"

Write-Host ">>> Compiling Java sources with Java 11..."
$javaFiles = Get-ChildItem -Recurse -Filter "*.java" "$dialerDir\companion-app\src", "$dialerDir\build\companion\gen" | Select-Object -ExpandProperty FullName
& javac --release 11 -cp $androidJar -d "$dialerDir\build\companion\classes" $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host ">>> Converting classes to DEX with d8..."
$classFiles = Get-ChildItem -Recurse -Filter "*.class" "$dialerDir\build\companion\classes" | Select-Object -ExpandProperty FullName
& cmd /c "$bt\d8.bat" --lib $androidJar --output "$dialerDir\build\companion\dex" $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host ">>> Repackaging base.apk with new classes.dex..."
$baseApk = "$dialerDir\build\companion\base.apk"
$unalignedApk = "$dialerDir\build\companion\unaligned.apk"
$dexFile = "$dialerDir\build\companion\dex\classes.dex"

python -c "
import zipfile, shutil
shutil.copy('$($baseApk.Replace('\', '/'))', '$($unalignedApk.Replace('\', '/'))')
with zipfile.ZipFile('$($unalignedApk.Replace('\', '/'))', 'a', compression=zipfile.ZIP_DEFLATED) as zf:
    zf.write('$($dexFile.Replace('\', '/'))', 'classes.dex')
"

Write-Host ">>> Aligning APK..."
$alignedApk = "$dialerDir\build\companion\aligned.apk"
& "$bt\zipalign.exe" -p -f 4 $unalignedApk $alignedApk

Write-Host ">>> Signing APK with release keystore..."
$keystore = "$dialerDir\build\release.keystore"
$distDir = "$dialerDir\dist"
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$finalApk = "$distDir\ODialerVideoRingtone.apk"

& cmd /c "$bt\apksigner.bat" sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
    --ks $keystore --ks-key-alias "odialer" --ks-pass "pass:android123" --key-pass "pass:android123" `
    --out $finalApk $alignedApk

Write-Host ">>> Verifying APK signature..."
& cmd /c "$bt\apksigner.bat" verify -v $finalApk

Write-Host ">>> Successfully built $finalApk"
