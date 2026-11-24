<#
.SYNOPSIS
Initializes, starts or checks a bank's networks through WSL. PerformanceOnly starts its dashboard APIs.
#>
param([switch]$Initialize, [switch]$Check, [switch]$PerformanceOnly,
    [ValidateSet('Simulation','Feedback','Personalization')][string]$Network,
    [ValidateSet('Emma','Anna')][string]$Bank = 'Emma')
$ErrorActionPreference = 'Stop'
if (([int][bool]$Initialize + [int][bool]$Check + [int][bool]$PerformanceOnly) -gt 1) { throw 'Choose one AI mode.' }
if ($Network -and ($Initialize -or $PerformanceOnly)) { throw 'Network applies only to startup and readiness checking.' }
. (Join-Path $PSScriptRoot 'wsl-path.ps1')
$linuxProject = ConvertTo-EngineLinuxPath -Path (Join-Path $PSScriptRoot '..')
$launcherArgs = @()
if ($Initialize) { $launcherArgs += '--init' }
if ($Check) { $launcherArgs += '--check' }
if ($PerformanceOnly) { $launcherArgs += '--api-only' }
if ($Network) { $launcherArgs += @('--network', $Network.ToUpperInvariant()) }
$bankEnvironment = 'LENDING_BANK=' + $Bank.ToLowerInvariant()
wsl.exe -d Ubuntu-22.04 --exec env $bankEnvironment bash "$linuxProject/scripts/run-ai.sh" @launcherArgs
if ($LASTEXITCODE -notin @(0,130,143,-1073741510)) { throw 'AI command failed. Inspect runtime/ai logs.' }
