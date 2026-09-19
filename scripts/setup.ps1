<#
.SYNOPSIS
Installs the Java, Maven, GnuCOBOL and TN3270 tools needed by the local WSL runtime.
#>
$ErrorActionPreference = 'Stop'
wsl.exe -d Ubuntu-22.04 -u root --exec bash -lc 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-17-jdk-headless maven gnucobol s3270 x3270'
if ($LASTEXITCODE -ne 0) { throw 'WSL dependency installation failed.' }
