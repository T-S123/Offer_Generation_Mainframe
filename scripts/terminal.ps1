<#
.SYNOPSIS
Opens the native x3270 client through WSLg and connects it to the local application terminal listener.
#>
$ErrorActionPreference = 'Stop'

wsl.exe -d Ubuntu-22.04 --exec x3270 -model 3279-2 -efont 3270-20 -title 'Lending Intelligence Engine' 127.0.0.1:2323
if ($LASTEXITCODE -ne 0) { throw 'Terminal failed. Confirm WSLg is available and the engine is running.' }
