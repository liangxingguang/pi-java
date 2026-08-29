@echo off
setlocal enabledelayedexpansion
rem Install pi-java globally with a bundled minimal JRE (jlink) — the target
rem machine needs no JDK. Build fat jar, craft runtime, copy everything to
rem the install dir, print PATH setup instruction.
rem Usage:
rem   install.cmd            -> default install dir D:\soft\pi-java
rem   install.cmd D:\my\dir  -> custom install dir
set "JAVA_HOME=D:\soft\jdk\graalvm-jdk-25"
set "MVN=D:\soft\apache-maven-3.9.9\bin\mvn.cmd"
if "%~1"=="" (set "INSTALL_DIR=D:\soft\pi-java") else (set "INSTALL_DIR=%~1")
set "REPO=%~dp0"
set "MODULES=java.base,java.compiler,java.desktop,java.management,java.naming,java.net.http,java.security.jgss,java.sql,jdk.httpserver,jdk.jfr,jdk.unsupported,jdk.crypto.ec,jdk.localedata"

echo [install] building fat jar...
call "%MVN%" -q -pl pi-java-dist -am package -DskipTests
if errorlevel 1 (
    echo [install] build failed
    exit /b 1
)
if not exist "%REPO%pi-java-dist\target\pi-java.jar" (
    echo [install] pi-java.jar not found after build
    exit /b 1
)

echo [install] crafting bundled JRE (jlink)...
if exist "%REPO%pi-java-dist\target\jre" rmdir /s /q "%REPO%pi-java-dist\target\jre"
call "%JAVA_HOME%\bin\jlink" --add-modules %MODULES% --include-locales=en,zh --output "%REPO%pi-java-dist\target\jre" --strip-debug --no-header-files --no-man-pages --compress zip-6
if errorlevel 1 (
    echo [install] jlink failed
    exit /b 1
)

echo [install] installing to %INSTALL_DIR%...
if exist "%INSTALL_DIR%" rmdir /s /q "%INSTALL_DIR%"
mkdir "%INSTALL_DIR%\bin"
copy /y "%REPO%pi-java-dist\target\pi-java.jar" "%INSTALL_DIR%\pi-java.jar" >nul
xcopy /e /i /q "%REPO%pi-java-dist\target\jre" "%INSTALL_DIR%\jre" >nul

(
    echo @echo off
    echo "%%~dp0..\jre\bin\java" -Dfile.encoding=UTF-8 -jar "%%~dp0..\pi-java.jar" %%*
) > "%INSTALL_DIR%\bin\pi-java.cmd"

(
    echo #!/usr/bin/env bash
    echo exec "$(dirname "$0")/../jre/bin/java" -Dfile.encoding=UTF-8 -jar "$(dirname "$0")/../pi-java.jar" "$@"
) > "%INSTALL_DIR%\bin\pi-java"
echo [install] verifying...
call "%INSTALL_DIR%\bin\pi-java.cmd" --version
if errorlevel 1 (
    echo [install] verification failed
    exit /b 1
)

echo.
echo [install] done. Add to PATH (one-time, permanent):
echo     [Environment]::SetEnvironmentVariable("Path", $env:Path + ";%INSTALL_DIR%\bin", "User"^)
echo Then run from any directory:  pi-java --version
endlocal
