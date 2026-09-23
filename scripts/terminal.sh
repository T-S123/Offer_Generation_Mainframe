#!/usr/bin/env bash
# Opens the local TN3270 console or optional graphical client after checking its prerequisites and server connection.
set -euo pipefail
mode="${1:-console}"
case "$mode" in
    console) client=c3270 ;;
    gui) client=x3270 ;;
    *) echo 'Use console or gui terminal mode.' >&2; exit 2 ;;
esac
if ! command -v "$client" >/dev/null 2>&1; then
    echo "$client is not installed in Ubuntu-22.04. Run .\scripts\setup.ps1 from PowerShell, then retry." >&2
    exit 1
fi
if ! timeout 3 bash -c 'exec 3<>/dev/tcp/127.0.0.1/2323' 2>/dev/null; then
    echo 'The terminal server is not listening at 127.0.0.1:2323 inside Ubuntu-22.04.' >&2
    echo 'Run .\scripts\run.ps1 in another PowerShell window and keep it open, then retry .\scripts\terminal.ps1.' >&2
    exit 1
fi
if [[ "$mode" == gui ]]; then
    if x3270 -model 3279-2 -efont 3270-20 -title 'Lending Intelligence Engine' 127.0.0.1:2323; then exit 0; fi
    echo 'The graphical terminal failed. Run .\scripts\terminal.ps1 without -Gui to use the console without WSLg.' >&2
    exit 1
fi
if [[ ! -t 0 || ! -t 1 ]]; then
    echo 'Open an interactive PowerShell or Windows Terminal window and run .\scripts\terminal.ps1 without piping or redirecting it.' >&2
    exit 1
fi
if [[ -z "${TERM:-}" || "$TERM" == dumb ]]; then export TERM=xterm-256color; fi
echo 'Opening the console terminal. Use Tab, Enter and F3; Ctrl+] then Quit returns to PowerShell.'
exec c3270 -model 3279-2 -charset us 127.0.0.1:2323
