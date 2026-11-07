<#
.SYNOPSIS
Initializes, starts, checks the agents, or runs the Performance API without model workers through WSL, forwarding each mode as one argument. Credentials stay in the local runtime.
#>
param([switch]$Initialize, [switch]$Check, [switch]$PerformanceOnly)
$ErrorActionPreference = 'Stop'
if (([int][bool]$Initialize + [int][bool]$Check + [int][bool]$PerformanceOnly) -gt 1) { throw 'Choose initialization or readiness checking.' }
. (Join-Path $PSScriptRoot 'wsl-path.ps1')
$linuxProject = ConvertTo-EngineLinuxPath -Path (Join-Path $PSScriptRoot '..')
if ($Initialize) {
    wsl.exe -d Ubuntu-22.04 --exec bash "$linuxProject/scripts/run-ai.sh" --init
} elseif ($Check) {
    wsl.exe -d Ubuntu-22.04 --exec bash "$linuxProject/scripts/run-ai.sh" --check
} elseif ($PerformanceOnly) {
    wsl.exe -d Ubuntu-22.04 --exec bash "$linuxProject/scripts/run-ai.sh" --api-only
} else {
    wsl.exe -d Ubuntu-22.04 --exec bash "$linuxProject/scripts/run-ai.sh"
}
if ($LASTEXITCODE -notin @(0,130,143,-1073741510)) { throw 'AI command failed. Inspect runtime/ai logs.' }
