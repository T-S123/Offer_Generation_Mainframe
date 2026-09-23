<#
.SYNOPSIS
Installs the Java, Maven, GnuCOBOL and console, graphical and automated TN3270 clients used by the local WSL runtime.
#>
$ErrorActionPreference = 'Stop'
wsl.exe -d Ubuntu-22.04 -u root --exec bash -lc 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-17-jdk-headless maven gnucobol c3270 s3270 x3270'
if ($LASTEXITCODE -ne 0) { throw 'WSL dependency installation failed.' }
