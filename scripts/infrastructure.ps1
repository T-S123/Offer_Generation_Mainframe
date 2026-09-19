<#
.SYNOPSIS
Starts or stops the local Docker Compose infrastructure and reports readiness failures.
#>
param([switch]$Stop)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).ProviderPath
$docker = Get-Command docker.exe -ErrorAction SilentlyContinue
if (-not $docker) {
    $candidate = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
    if (-not (Test-Path -LiteralPath $candidate)) { throw 'Install Docker Desktop with its WSL 2 backend, then run this script again.' }
    $dockerPath = $candidate
} else { $dockerPath = $docker.Source }
& $dockerPath info --format '{{.ServerVersion}}' 2>$null
if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop, wait until its engine is running, then retry.' }
if ($Stop) { & $dockerPath compose --project-directory $project stop }
else { & $dockerPath compose --project-directory $project up -d --build --wait }
if ($LASTEXITCODE -ne 0) { throw 'Local infrastructure did not become ready. Inspect docker compose logs.' }
