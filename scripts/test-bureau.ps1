<#
.SYNOPSIS
Runs the opt-in database integration suite through WSL using the existing local configuration.
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'wsl-path.ps1')
$project = ConvertTo-EngineLinuxPath -Path (Join-Path $PSScriptRoot '..')
wsl.exe -d Ubuntu-22.04 --exec bash "$project/scripts/test-bureau.sh"
if ($LASTEXITCODE -ne 0) { throw 'Bureau integration or regression checks failed.' }
