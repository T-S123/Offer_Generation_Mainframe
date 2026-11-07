<#
.SYNOPSIS
Starts only the optional local browser gateway through WSL; the engine and A2A services retain their separate launchers.
#>
param([ValidateRange(1024,65535)][int]$Port = 8080)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'wsl-path.ps1')
$linuxProject = ConvertTo-EngineLinuxPath -Path (Join-Path $PSScriptRoot '..')
wsl.exe -d Ubuntu-22.04 --exec bash "$linuxProject/scripts/run-gui.sh" "$Port"
if ($LASTEXITCODE -notin @(0,130,143,-1073741510)) { throw "GUI stopped (exit $LASTEXITCODE). Read the error above." }

