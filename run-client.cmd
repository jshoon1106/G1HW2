@echo off
pushd "%~dp0"
if errorlevel 1 exit /b 1
pwsh -NoProfile -File .\project.ps1 -Action Clients -ServerHost 13.211.23.109 -Port 5000
set "g1ClientExit=%errorlevel%"
popd
pause
exit /b %g1ClientExit%
