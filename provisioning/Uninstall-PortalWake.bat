@echo off
REM Windows double-click entry point. Double-click to remove portal-wake.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -Uninstall
echo.
pause
