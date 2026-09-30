<#
.SYNOPSIS
Initializes, starts, or checks the six Java AI agents through WSL, forwarding each mode as one argument. Credentials stay in the local runtime.
#>
param([switch]$Initialize, [switch]$Check)
$ErrorActionPreference = 'Stop'
if ($Initialize -and $Check) { throw 'Choose initialization or readiness checking.' }
. (Join-Path $PSScriptRoot 'wsl-path.ps1')
$linuxProject = ConvertTo-EngineLinuxPath -Path (Join-Path $PSScriptRoot '..')
if ($Initialize) {
    wsl.exe -d Ubuntu-22.04 --exec bash "$linuxProject/scripts/run-ai.sh" --init
} elseif ($Check) {
    wsl.exe -d Ubuntu-22.04 --exec bash "$linuxProject/scripts/run-ai.sh" --check
} else {
    wsl.exe -d Ubuntu-22.04 --exec bash "$linuxProject/scripts/run-ai.sh"
}
if ($LASTEXITCODE -notin @(0,130,143,-1073741510)) { throw 'AI command failed. Inspect runtime/ai logs.' }
