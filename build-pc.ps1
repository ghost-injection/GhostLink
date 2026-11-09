$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
& .tools\packaging\Scripts\python.exe desktop\make_icon.py
& .tools\packaging\Scripts\python.exe -m PyInstaller --noconfirm --windowed --onedir --name GhostLink --icon (Join-Path $PSScriptRoot 'build\ghostlink.ico') --specpath build --workpath build\pyinstaller desktop\ghostlink.py
if ($LASTEXITCODE) { throw 'PC executable build failed' }
Copy-Item config.json dist\GhostLink\config.json -Force
Copy-Item build\ghostlink.ico dist\GhostLink\ghostlink.ico -Force
$scrcpyDirectory = Split-Path (Get-Command scrcpy).Source
New-Item -ItemType Directory -Force dist\GhostLink\tools\scrcpy | Out-Null
Copy-Item -Path (Join-Path $scrcpyDirectory '*') -Destination dist\GhostLink\tools\scrcpy -Recurse -Force
Copy-Item README.md dist\GhostLink\README.md -Force
Write-Output 'Built portable Windows folder: dist\GhostLink'
