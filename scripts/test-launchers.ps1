<#
.SYNOPSIS
Checks WSL path conversion, console/GUI terminal dispatch and useful diagnostics for failed launcher commands.
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
try {
    $global:terminalLauncherTestArguments = @()
    $global:terminalLauncherTestExit = 0
    <#
    .SYNOPSIS
    Captures terminal launcher arguments and supplies controlled WSL results without opening an interactive client.
    #>
    function wsl.exe {
        if ($args -contains 'wslpath') { $global:LASTEXITCODE = 0; return '/mnt/fixture path/engine' }
        $global:terminalLauncherTestArguments = @($args)
        $global:LASTEXITCODE = $global:terminalLauncherTestExit
    }
    & (Join-Path $PSScriptRoot 'terminal.ps1')
    if ($global:terminalLauncherTestArguments.Count -ne 6 -or $global:terminalLauncherTestArguments[4] -ne '/mnt/fixture path/engine/scripts/terminal.sh' -or $global:terminalLauncherTestArguments[5] -ne 'console') {
        throw 'The default terminal must use console mode and preserve the complete script path.'
    }
    & (Join-Path $PSScriptRoot 'terminal.ps1') -Gui
    if ($global:terminalLauncherTestArguments[5] -ne 'gui') { throw 'Explicit -Gui did not select the graphical client.' }
    $global:terminalLauncherTestExit = 23
    $failure = $null
    try { & (Join-Path $PSScriptRoot 'terminal.ps1') } catch { $failure = $_.Exception.Message }
    if ($failure -notlike '*exit 23*console mode does not require WSLg*') { throw "Missing terminal diagnostic: $failure" }
} finally {
    Remove-Item -LiteralPath Function:\wsl.exe -ErrorAction SilentlyContinue
    Remove-Variable -Name terminalLauncherTestArguments,terminalLauncherTestExit -Scope Global -ErrorAction SilentlyContinue
    $global:LASTEXITCODE = 0
}
Write-Output "Launcher checks passed in PowerShell $($PSVersionTable.PSVersion): paths, conversion errors, console/GUI selection and terminal errors."
