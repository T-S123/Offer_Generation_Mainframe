<#
.SYNOPSIS
Provides a shared Windows-to-WSL path conversion helper for the PowerShell launchers.
#>
<#
.SYNOPSIS
Resolves an existing filesystem path and converts it through direct WSL execution. Invalid conversion output
raises a useful error before trimming.
#>
function ConvertTo-EngineLinuxPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    $resolvedPath = Resolve-Path -LiteralPath $Path -ErrorAction Stop
    if ($resolvedPath.Provider.Name -ne 'FileSystem') {
        throw "Expected a filesystem path: $Path"
    }
    $windowsPath = $resolvedPath.ProviderPath.Replace('\', '/')
    $converted = @(& wsl.exe -d Ubuntu-22.04 --exec wslpath -a -u $windowsPath)
    $conversionExitCode = $LASTEXITCODE
    if ($conversionExitCode -ne 0 -or $converted.Count -ne 1 -or
        [string]::IsNullOrWhiteSpace([string]$converted[0])) {
        throw "Could not convert '$Path' to an Ubuntu-22.04 path (WSL exit code $conversionExitCode). Check the WSL error above."
    }
    return ([string]$converted[0]).Trim()
}
