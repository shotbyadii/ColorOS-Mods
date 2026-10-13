$ErrorActionPreference = "Stop"

$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt = "$sdk\build-tools\36.0.0"
$adb = "$sdk\platform-tools\adb.exe"
$androidJar = "$sdk\platforms\android-36\android.jar"

Write-Host ">>> Compiling Java sources with Java 11..."
$javaFiles = Get-ChildItem -Recurse -Filter "*.java" "companion-app\src", "build\companion\gen" | Select-Object -ExpandProperty FullName
& javac --release 11 -cp $androidJar -d "build\companion\classes" $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host ">>> Converting classes to DEX with d8..."
$classFiles = Get-ChildItem -Recurse -Filter "*.class" "build\companion\classes" | Select-Object -ExpandProperty FullName
& "$bt\d8.bat" --lib $androidJar --output "build\companion\dex" $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host ">>> Repackaging base.apk with new classes.dex..."
python -c "
import zipfile, shutil
shutil.copy('build/companion/base.apk', 'build/companion/unaligned.apk')
with zipfile.ZipFile('build/companion/unaligned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as zf:
    zf.write('build/companion/dex/classes.dex', 'classes.dex')
"

Write-Host ">>> Aligning APK..."
& "$bt\zipalign.exe" -p -f 4 "build\companion\unaligned.apk" "build\companion\aligned.apk"

Write-Host ">>> Signing APK with release keystore..."
& "$bt\apksigner.bat" sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
    --ks "build\release.keystore" --ks-key-alias "odialer" --ks-pass "pass:android123" --key-pass "pass:android123" `
    --out "dist\ODialerVideoRingtone.apk" "build\companion\aligned.apk"

Write-Host ">>> Verifying APK signature..."
& "$bt\apksigner.bat" verify -v "dist\ODialerVideoRingtone.apk"

Write-Host ">>> Successfully built dist\ODialerVideoRingtone.apk"
