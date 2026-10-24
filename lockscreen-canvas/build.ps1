$ErrorActionPreference = "Stop"

$sdk = "C:\Users\anush\AppData\Local\Android\Sdk"
$bt = "$sdk\build-tools\36.0.0"
$adb = "$sdk\platform-tools\adb.exe"
$androidJar = "$sdk\platforms\android-36\android.jar"
$projDir = $PSScriptRoot
$repoRoot = (Get-Item $projDir).Parent.FullName
$keystore = "$repoRoot\odialer-video-ringtone\build\release.keystore"
if (-not (Test-Path $keystore)) {
    $keystore = "$projDir\release.keystore"
}

$buildDir = "$projDir\build"
$objDir = "$buildDir\obj"
$dexDir = "$buildDir\dex"
$genDir = "$buildDir\gen"
$distDir = "$projDir\dist"
New-Item -ItemType Directory -Force -Path $distDir | Out-Null

Write-Host "=== Building Lock Video Cover APK ===" -ForegroundColor Cyan

# Clean & Prepare
if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Force -Path $objDir, $dexDir, $genDir | Out-Null

# 1. Compile Resources with aapt2
Write-Host "1. Compiling resources..." -ForegroundColor Yellow
$resZip = "$buildDir\compiled_res.zip"
& "$bt\aapt2.exe" compile --dir "$projDir\res" -o $resZip

# 2. Link Resources
Write-Host "2. Linking resources and generating R.java..." -ForegroundColor Yellow
$baseApk = "$buildDir\base.apk"
$assetArgs = @()
if (Test-Path "$projDir\assets") {
    $assetArgs = @("-A", "$projDir\assets")
}
& "$bt\aapt2.exe" link -I $androidJar `
    --manifest "$projDir\AndroidManifest.xml" `
    --java $genDir `
    -o $baseApk `
    $resZip `
    $assetArgs `
    --auto-add-overlay

# 3. Compile Java Source Files
Write-Host "3. Compiling Java sources with Java 11..." -ForegroundColor Yellow
$javaFiles = @(Get-ChildItem -Recurse -Path "$projDir\src\*.java", "$genDir\*.java" | ForEach-Object { $_.FullName })
& javac --release 11 -cp $androidJar -d $objDir $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# 4. Dexing with D8
Write-Host "4. Converting classes to DEX with D8..." -ForegroundColor Yellow
$classFiles = @(Get-ChildItem -Recurse -Path "$objDir\*.class" | ForEach-Object { $_.FullName })
& cmd /c "$bt\d8.bat" --min-api 26 --lib $androidJar --output $dexDir $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# 5. Inject classes.dex into APK
Write-Host "5. Repackaging APK with classes.dex..." -ForegroundColor Yellow
$unalignedApk = "$buildDir\unaligned.apk"
python -c "
import zipfile, shutil
shutil.copy('$($baseApk.Replace('\', '/'))', '$($unalignedApk.Replace('\', '/'))')
with zipfile.ZipFile('$($unalignedApk.Replace('\', '/'))', 'a', compression=zipfile.ZIP_DEFLATED) as zf:
    zf.write('$($dexDir.Replace('\', '/'))/classes.dex', 'classes.dex')
"

# 6. Zipalign
Write-Host "6. Aligning APK..." -ForegroundColor Yellow
$alignedApk = "$buildDir\aligned.apk"
& "$bt\zipalign.exe" -p -f 4 $unalignedApk $alignedApk

# 7. Sign APK
Write-Host "7. Signing APK..." -ForegroundColor Yellow
$finalApk = "$distDir\LockVideoCover.apk"
& cmd /c "$bt\apksigner.bat" sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
    --ks $keystore --ks-key-alias "odialer" --ks-pass "pass:android123" --key-pass "pass:android123" `
    --out $finalApk $alignedApk

Copy-Item -Force $finalApk "$projDir\LockVideoCover.apk"

Write-Host "8. Verifying APK..." -ForegroundColor Yellow
& cmd /c "$bt\apksigner.bat" verify -v $finalApk

Write-Host "=== Build Complete! Created: $finalApk ===" -ForegroundColor Green
Get-Item $finalApk | Select-Object Name, Length, LastWriteTime
