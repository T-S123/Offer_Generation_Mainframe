<#
.SYNOPSIS
Opens the console TN3270 client in PowerShell, with an optional graphical client when WSLg is available.
#>
param([switch]$Gui)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'wsl-path.ps1')
$linuxProject = ConvertTo-EngineLinuxPath -Path (Join-Path $PSScriptRoot '..')
$terminalMode = if ($Gui) { 'gui' } else { 'console' }

wsl.exe -d Ubuntu-22.04 --exec bash "$linuxProject/scripts/terminal.sh" $terminalMode
if ($LASTEXITCODE -notin @(0,130,143,-1073741510)) { throw "Terminal could not open (exit $LASTEXITCODE). Follow the diagnostic above; console mode does not require WSLg." }
