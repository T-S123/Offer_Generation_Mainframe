<#
.SYNOPSIS
Runs the Linux build and tests from PowerShell after safely translating the project path for WSL.
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'wsl-path.ps1')
$linuxProject = ConvertTo-EngineLinuxPath -Path (Join-Path $PSScriptRoot '..')
wsl.exe -d Ubuntu-22.04 --exec bash "$linuxProject/scripts/build.sh"
if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed.' }
