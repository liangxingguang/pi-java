@echo off
setlocal
rem One-click launcher for the pi-java CLI (build deps, then run).
rem Usage:
rem   run.cmd                 -> interactive mode (TUI)
rem   run.cmd --list-models   -> list built-in models
rem   run.cmd --version       -> print version
set "JAVA_HOME=D:\soft\jdk\graalvm-jdk-25"
set "MVN=D:\soft\apache-maven-3.9.9\bin\mvn.cmd"
rem Silence JDK 25 native-access warnings (JLine/TamboUI Panama backend)
set "MAVEN_OPTS=--enable-native-access=ALL-UNNAMED"

echo [pi-java] building (incremental)...
call "%MVN%" -q -pl pi-java-tui -am install -DskipTests
if errorlevel 1 (
    echo [pi-java] build failed
    exit /b 1
)

echo [pi-java] launching...
call "%MVN%" -pl pi-java-tui exec:java -Dexec.args="%*"
exit /b %errorlevel%
