<#
.SYNOPSIS
Checks WSL path conversion for special characters and verifies useful errors for failed or empty conversion
results.
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'wsl-path.ps1')

$linuxProject = ConvertTo-EngineLinuxPath -Path (Join-Path $PSScriptRoot '..')
& wsl.exe -d Ubuntu-22.04 --exec test -f "$linuxProject/scripts/run.sh"
if ($LASTEXITCODE -ne 0) { throw 'Converted project path does not point to run.sh.' }

$fixtureDirectory = Join-Path ([IO.Path]::GetTempPath()) ('lending launcher [' + [guid]::NewGuid().ToString('N') + ']')
$fixturePath = Join-Path $fixtureDirectory 'decision file $literal & ''quoted''.json'
try {
    [IO.Directory]::CreateDirectory($fixtureDirectory) | Out-Null
    [IO.File]::WriteAllText($fixturePath, '{"decisions":[]}')
    $linuxFixture = ConvertTo-EngineLinuxPath -Path $fixturePath
    & wsl.exe -d Ubuntu-22.04 --exec test -f $linuxFixture
    if ($LASTEXITCODE -ne 0) { throw 'Spaces or metacharacters changed during WSL path conversion.' }
    $payload = & wsl.exe -d Ubuntu-22.04 --exec cat $linuxFixture
    if ($LASTEXITCODE -ne 0 -or $payload -ne '{"decisions":[]}') { throw 'Converted import path did not retain the original file.' }
} finally {

    if ([IO.File]::Exists($fixturePath)) { [IO.File]::Delete($fixturePath) }
    if ([IO.Directory]::Exists($fixtureDirectory)) { [IO.Directory]::Delete($fixtureDirectory) }
}

try {
    <#
    .SYNOPSIS
    Simulates a failed WSL command so path-conversion error reporting can be verified.
    #>
    function wsl.exe { $global:LASTEXITCODE = 23 }
    $failure = $null
    try { ConvertTo-EngineLinuxPath -Path $PSScriptRoot | Out-Null } catch { $failure = $_.Exception.Message }
    if ($failure -notlike '*Could not convert*exit code 23*') { throw "Missing useful conversion error: $failure" }
    <#
    .SYNOPSIS
    Simulates blank WSL output so empty path-conversion results can be rejected.
    #>
    function wsl.exe { $global:LASTEXITCODE = 0; return '   ' }
    $failure = $null
    try { ConvertTo-EngineLinuxPath -Path $PSScriptRoot | Out-Null } catch { $failure = $_.Exception.Message }
    if ($failure -notlike '*Could not convert*exit code 0*') { throw "Blank output was not rejected: $failure" }
} finally {
    Remove-Item -LiteralPath Function:\wsl.exe -ErrorAction SilentlyContinue
    $global:LASTEXITCODE = 0
}
Write-Output "Launcher checks passed in PowerShell $($PSVersionTable.PSVersion): project path, special-character import path, failed conversion and empty output."
