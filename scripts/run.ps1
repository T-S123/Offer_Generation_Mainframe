<#
.SYNOPSIS
Launches the selected bank or an import against that bank through WSL.
#>
param([string]$ImportDecisions, [string]$BureauManifest, [string]$BureauProfiles,
    [ValidateSet('Emma','Anna')][string]$Bank = 'Emma')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'wsl-path.ps1')
$linuxProject = ConvertTo-EngineLinuxPath -Path (Join-Path $PSScriptRoot '..')
$bankEnvironment = 'LENDING_BANK=' + $Bank.ToLowerInvariant()
if (@($ImportDecisions,$BureauManifest,$BureauProfiles | Where-Object { $_ }).Count -gt 1) { throw 'Choose one import or batch mode.' }
if ($BureauManifest -or $BureauProfiles) {
    $file = if ($BureauManifest) { $BureauManifest } else { $BureauProfiles }
    $mode = if ($BureauManifest) { '--bureau-batch' } else { '--import-bureau-profiles' }
    $linuxSource = ConvertTo-EngineLinuxPath -Path $file
    wsl.exe -d Ubuntu-22.04 --exec env $bankEnvironment bash "$linuxProject/scripts/run.sh" $mode $linuxSource
} elseif ($ImportDecisions) {
    $linuxSource = ConvertTo-EngineLinuxPath -Path $ImportDecisions
    wsl.exe -d Ubuntu-22.04 --exec env $bankEnvironment bash "$linuxProject/scripts/run.sh" --import-decisions $linuxSource
} else {
    wsl.exe -d Ubuntu-22.04 --exec env $bankEnvironment bash "$linuxProject/scripts/run.sh"
}
if ($LASTEXITCODE -notin @(0,130,143,-1073741510)) { throw "Engine command failed (exit $LASTEXITCODE). Read the error above." }
