$ErrorActionPreference = 'Stop'
$exe = Join-Path $PSScriptRoot 'dist\GhostLink\GhostLink.exe'
if (!(Test-Path -LiteralPath $exe)) { throw 'Run build-pc.ps1 first' }
$shell = New-Object -ComObject WScript.Shell
$desktop = [Environment]::GetFolderPath('Desktop')
$startup = [Environment]::GetFolderPath('Startup')
foreach ($entry in @(
 @{Path=(Join-Path $desktop 'Ghost Link.lnk');Args=''},
 @{Path=(Join-Path $desktop 'Ghost Mirror.lnk');Args='--connect'},
 @{Path=(Join-Path $desktop 'Android Mirror.lnk');Args='--connect'},
 @{Path=(Join-Path $startup 'Ghost Link Receiver.lnk');Args='--background'}
)) {
 $link=$shell.CreateShortcut($entry.Path)
 $link.TargetPath=$exe
 $link.Arguments=$entry.Args
 $link.WorkingDirectory=Split-Path $exe
 $link.IconLocation=(Join-Path (Split-Path $exe) 'ghostlink.ico')
 $link.Description='Private Android control through Tailscale'
 $link.Save()
}
Write-Output 'Installed desktop shortcuts and Windows sign-in receiver.'

