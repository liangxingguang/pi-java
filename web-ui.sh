#!/usr/bin/env bash
# One-click launcher for the pi-java web UI (Git Bash / WSL).
# Usage:
#   bash web-ui.sh            -> start on default port 8787
#   bash web-ui.sh 9000       -> start on port 9000
# First start generates a gateway token at ~/.pi-java/web/gateway-token
# (or use env PI_JAVA_WEB_TOKEN); the browser prompt asks for it.
set -uo pipefail
cd "$(dirname "$0")"

# Force the project JDK (JDK 25); edit here if you use a different install.
export JAVA_HOME="D:/soft/jdk/graalvm-jdk-25"
MVN="${MVN:-D:/soft/apache-maven-3.9.9/bin/mvn}"
PORT="${1:-8787}"
JAR="pi-java-dist/target/pi-java.jar"

echo "[pi-java web] building (incremental)..."
"$MVN" -q -pl pi-java-dist -am install -DskipTests || {
    echo "[pi-java web] build failed"
    exit 1
}

echo "[pi-java web] starting on http://localhost:$PORT ..."
"$JAVA_HOME/bin/java" -jar "$JAR" --mode web --port "$PORT" &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT

echo "[pi-java web] waiting for server..."
until curl -s -o /dev/null --max-time 2 "http://localhost:$PORT/api/config"; do
    sleep 1
done

TOKEN_FILE="${USERPROFILE:-$HOME}/.pi-java/web/gateway-token"
if [[ -f "$TOKEN_FILE" ]]; then
    echo "[pi-java web] gateway token: $(head -1 "$TOKEN_FILE")"
fi
echo "[pi-java web] open http://localhost:$PORT/ and paste the token above when asked."
echo "[pi-java web] server running in this shell - press Ctrl+C to stop."
wait "$SERVER_PID"
