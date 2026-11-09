$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$bt = Join-Path $PSScriptRoot '.tools\build-tools\android-15'
$platform = Join-Path $PSScriptRoot '.tools\platform\android-35\android.jar'
$javac = Join-Path (Split-Path (Get-Command java).Source) 'javac.exe'
$env:JAVA_HOME = Split-Path (Split-Path (Get-Command java).Source)
$keytool = Join-Path (Split-Path (Get-Command java).Source) 'keytool.exe'
New-Item -ItemType Directory -Force build\generated,build\classes,build\dex,build\assets,dist | Out-Null
$cfg = Get-Content config.json -Raw | ConvertFrom-Json
@{pc_ip=$cfg.pc_ip;token=$cfg.token;adb_port=$cfg.adb_port} | ConvertTo-Json | Set-Content build\assets\controller.json -Encoding utf8
& "$bt\aapt2.exe" compile --dir android\res -o build\resources.zip
if ($LASTEXITCODE) { throw 'Resource compilation failed' }
& "$bt\aapt2.exe" link -o build\unsigned.apk -I $platform --manifest android\AndroidManifest.xml --java build\generated -A build\assets build\resources.zip
if ($LASTEXITCODE) { throw 'Resource linking failed' }
$sources = @(Get-ChildItem android\src,build\generated -Filter *.java -Recurse | ForEach-Object FullName)
& $javac -encoding UTF-8 -source 8 -target 8 -classpath $platform -d build\classes $sources
if ($LASTEXITCODE) { throw 'Java compilation failed' }
& (Join-Path (Split-Path $javac) 'jar.exe') cf build\classes.jar -C build\classes .
& "$bt\d8.bat" --lib $platform --min-api 30 --output build\dex build\classes.jar
if ($LASTEXITCODE) { throw 'Dex compilation failed' }
& (Join-Path (Split-Path $javac) 'jar.exe') uf build\unsigned.apk -C build\dex classes.dex
& "$bt\zipalign.exe" -f 4 build\unsigned.apk build\aligned.apk
if (!(Test-Path .tools\ghostlink.jks)) {
 & $keytool -genkeypair -keystore .tools\ghostlink.jks -storepass ghostlink-local-build -keypass ghostlink-local-build -alias ghostlink -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Ghost Link Local Build'
}
& "$bt\apksigner.bat" sign --ks .tools\ghostlink.jks --ks-key-alias ghostlink --ks-pass pass:ghostlink-local-build --key-pass pass:ghostlink-local-build --out dist\GhostLink.apk build\aligned.apk
if ($LASTEXITCODE) { throw 'APK signing failed' }
& "$bt\apksigner.bat" verify dist\GhostLink.apk
if ($LASTEXITCODE) { throw 'APK verification failed' }
Write-Output 'Built and verified dist\GhostLink.apk'
