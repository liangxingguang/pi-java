@echo off
setlocal
rem Uninstall pi-java from the install dir (default D:\soft\pi-java).
rem Usage:
rem   uninstall.cmd           -> default install dir
rem   uninstall.cmd D:\my\dir -> custom install dir
if "%~1"=="" (set "INSTALL_DIR=D:\soft\pi-java") else (set "INSTALL_DIR=%~1")

if exist "%INSTALL_DIR%\pi-java.jar" del "%INSTALL_DIR%\pi-java.jar"
if exist "%INSTALL_DIR%\bin\pi-java.cmd" del "%INSTALL_DIR%\bin\pi-java.cmd"
if exist "%INSTALL_DIR%\bin\pi-java" del "%INSTALL_DIR%\bin\pi-java"
if exist "%INSTALL_DIR%\bin" rmdir "%INSTALL_DIR%\bin"
if exist "%INSTALL_DIR%" rmdir "%INSTALL_DIR%" 2>nul

echo [uninstall] removed %INSTALL_DIR%.
echo [uninstall] note: remove the bin dir from your user PATH manually if added.
endlocal
