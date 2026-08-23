@echo off
setlocal EnableDelayedExpansion
rem One-click launcher for the pi-java web UI (build deps, start server, open browser).
rem Usage:
rem   web-ui.cmd          -> start on default port 8787
rem   web-ui.cmd 9000     -> start on port 9000
rem First start generates a gateway token at %USERPROFILE%\.pi-java\web\gateway-token
rem (or use env PI_JAVA_WEB_TOKEN); the browser prompt asks for it.
set "JAVA_HOME=D:\soft\jdk\graalvm-jdk-25"
set "MVN=D:\soft\apache-maven-3.9.9\bin\mvn.cmd"
set "PORT=%1"
if "%PORT%"=="" set "PORT=8787"
set "JAR=pi-java-dist\target\pi-java.jar"

echo [pi-java web] building (incremental)...
call "%MVN%" -q -pl pi-java-dist -am package -DskipTests
if errorlevel 1 (
    echo [pi-java web] build failed
    exit /b 1
)

echo [pi-java web] starting on http://localhost:%PORT% ...
start "pi-java web" "%JAVA_HOME%\bin\java.exe" -jar "%JAR%" --mode web --port %PORT%

echo [pi-java web] waiting for server...
:wait
timeout /t 1 /nobreak >nul
curl -s -o nul --max-time 2 "http://localhost:%PORT%/api/config" >nul 2>&1 || goto :wait

echo [pi-java web] server is up.
set "TOKEN_FILE=%USERPROFILE%\.pi-java\web\gateway-token"
if exist "%TOKEN_FILE%" (
    set /p TOKEN=<"%TOKEN_FILE%"
    echo [pi-java web] gateway token: !TOKEN!
    echo [pi-java web] paste the token into the browser prompt when asked.
)
start "" "http://localhost:%PORT%/"
echo [pi-java web] done - browser opened. Stop the server in the "pi-java web" window.
endlocal
