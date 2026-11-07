<#
.SYNOPSIS
Installs locked GUI dependencies, checks client, presenter maintenance and launcher boundaries, and packages using Windows Node and WSL Java.
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'wsl-path.ps1')
$linuxProject = ConvertTo-EngineLinuxPath -Path (Join-Path $PSScriptRoot '..')
Push-Location (Join-Path $PSScriptRoot '../frontend')
try {
    npm.cmd ci
    if ($LASTEXITCODE -ne 0) { throw 'GUI dependency installation failed.' }
    npm.cmd run contract:check
    if ($LASTEXITCODE -ne 0) { throw 'GUI API contract is stale.' }
    npm.cmd test
    if ($LASTEXITCODE -ne 0) { throw 'GUI client tests failed.' }
    npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'GUI compilation failed.' }
} finally { Pop-Location }
wsl.exe -d Ubuntu-22.04 --exec python3 "$linuxProject/scripts/test-presenter.py"
if ($LASTEXITCODE -ne 0) { throw 'Presenter maintenance checks failed.' }
wsl.exe -d Ubuntu-22.04 --exec mvn -q -f "$linuxProject/services/pom.xml" '-Dtest=WebServerTest,StartupLauncherTest' package
if ($LASTEXITCODE -ne 0) { throw 'GUI gateway tests or packaging failed.' }
Write-Host 'GUI built. Start the base engine, then run ./scripts/run-gui.ps1.'

