<#
.SYNOPSIS
Packages the six-agent Java runtime and executes isolated AI tests through WSL Java 17.
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'wsl-path.ps1')
$linuxProject = ConvertTo-EngineLinuxPath -Path (Join-Path $PSScriptRoot '..')
wsl.exe -d Ubuntu-22.04 --exec bash "$linuxProject/scripts/build-ai.sh"
if ($LASTEXITCODE -ne 0) { throw 'AI build or tests failed.' }
