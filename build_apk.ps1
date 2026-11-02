$ErrorActionPreference = "Stop"
$jdkBin = "C:\Program Files\Java\jdk-22\bin"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$buildTools = "$sdk\build-tools\34.0.0"
$platform = "$sdk\platforms\android-34\android.jar"
$psJar = "$PSScriptRoot\app\libs\pocketsphinx.jar"
$outDir = "$PSScriptRoot\build_apk"
$keystore = "$outDir\debug.keystore"
$finalApk = "$PSScriptRoot\BatmanWledDashboard.apk"

if (Test-Path "$outDir\obj") { Remove-Item "$outDir\obj" -Recurse -Force }
if (Test-Path "$outDir\dex") { Remove-Item "$outDir\dex" -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$outDir\gen", "$outDir\obj", "$outDir\dex" | Out-Null

Write-Host "1. Packaging resources and assets with aapt..."
& "$buildTools\aapt.exe" package -f -m `
    -0 mdef -0 sendump -0 means -0 variances -0 dict -0 gram -0 keys `
    -J "$outDir\gen" `
    -M "$PSScriptRoot\app\src\main\AndroidManifest.xml" `
    -S "$PSScriptRoot\app\src\main\res" `
    -A "$PSScriptRoot\app\src\main\assets" `
    -I $platform `
    -F "$outDir\unaligned.apk"

Write-Host "2. Compiling Java sources (target 1.8) with CMU PocketSphinx Offline ASR..."
$javaFiles = Get-ChildItem -Path "$PSScriptRoot\app\src\main\java", "$outDir\gen" -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName
& "$jdkBin\javac.exe" -source 1.8 -target 1.8 -bootclasspath $platform -classpath "$platform;$psJar" -d "$outDir\obj" $javaFiles

Write-Host "3. Packaging classes.jar and converting to DEX (min-api 17)..."
& "$jdkBin\jar.exe" cf "$outDir\classes.jar" -C "$outDir\obj" .
& "$buildTools\d8.bat" --min-api 17 --lib $platform --output "$outDir\dex" "$outDir\classes.jar" $psJar

Write-Host "4. Adding classes.dex and native PocketSphinx JNI (armeabi-v7a) to APK..."
Copy-Item "$outDir\dex\classes.dex" "$PSScriptRoot\classes.dex" -Force
Push-Location $PSScriptRoot
& "$buildTools\aapt.exe" add "$outDir\unaligned.apk" "classes.dex" | Out-Null
Remove-Item "$PSScriptRoot\classes.dex" -Force
Pop-Location

Push-Location "$PSScriptRoot\app\src\main"
& "$buildTools\aapt.exe" add "$outDir\unaligned.apk" "lib/armeabi-v7a/libpocketsphinx_jni.so" | Out-Null
Pop-Location

Write-Host "5. Aligning and signing APK (V1 + V2)..."
& "$buildTools\zipalign.exe" -f 4 "$outDir\unaligned.apk" "$outDir\aligned.apk"
if (-not (Test-Path $keystore)) {
    & "$jdkBin\keytool.exe" -genkeypair -keystore $keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
}
& "$buildTools\apksigner.bat" sign --ks $keystore --ks-pass pass:android --key-pass pass:android --v1-signing-enabled true --v2-signing-enabled true --out $finalApk "$outDir\aligned.apk"
Write-Host "Built APK successfully: $finalApk"
