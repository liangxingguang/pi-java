#!/usr/bin/env bash
# Install pi-java globally with a bundled minimal JRE (jlink) — the target
# machine needs no JDK. Build fat jar, craft runtime, copy everything to
# the install dir, print PATH setup instruction.
# Usage:
#   ./install.sh            -> default install dir ~/.local/pi-java
#   ./install.sh /opt/pi    -> custom install dir
set -euo pipefail

JAVA_HOME="${JAVA_HOME:-}"
MVN="${MVN:-mvn}"
if [[ -z "$JAVA_HOME" ]]; then
    echo "[install] error: JAVA_HOME not set (point it at a JDK 25+ install)"
    exit 1
fi
if ! "$JAVA_HOME/bin/jlink" --version >/dev/null 2>&1; then
    echo "[install] error: \$JAVA_HOME/bin/jlink not available (need full JDK, not JRE)"
    exit 1
fi

INSTALL_DIR="${1:-$HOME/.local/pi-java}"
REPO="$(cd "$(dirname "$0")" && pwd)"
MODULES="java.base,java.compiler,java.desktop,java.management,java.naming,java.net.http,java.security.jgss,java.sql,jdk.httpserver,jdk.jfr,jdk.unsupported,jdk.crypto.ec,jdk.localedata"

echo "[install] building fat jar..."
(cd "$REPO" && "$MVN" -q -pl pi-java-dist -am package -DskipTests)
JAR="$REPO/pi-java-dist/target/pi-java.jar"
if [[ ! -f "$JAR" ]]; then
    echo "[install] pi-java.jar not found after build"
    exit 1
fi

echo "[install] crafting bundled JRE (jlink)..."
rm -rf "$REPO/pi-java-dist/target/jre"
"$JAVA_HOME/bin/jlink" --add-modules "$MODULES" --include-locales=en,zh \
    --output "$REPO/pi-java-dist/target/jre" \
    --strip-debug --no-header-files --no-man-pages --compress zip-6

echo "[install] installing to $INSTALL_DIR..."
rm -rf "$INSTALL_DIR"
mkdir -p "$INSTALL_DIR/bin"
cp "$JAR" "$INSTALL_DIR/pi-java.jar"
cp -r "$REPO/pi-java-dist/target/jre" "$INSTALL_DIR/jre"

cat > "$INSTALL_DIR/bin/pi-java" <<SHIM
#!/usr/bin/env bash
exec "\$(dirname "\$0")/../jre/bin/java" -Dfile.encoding=UTF-8 -jar "\$(dirname "\$0")/../pi-java.jar" "\$@"
SHIM
chmod +x "$INSTALL_DIR/bin/pi-java"

echo "[install] verifying..."
"$INSTALL_DIR/bin/pi-java" --version

echo
echo "[install] done. Add to PATH (one-time, ~/.bashrc or ~/.zshrc):"
echo "    export PATH=\"\$PATH:$INSTALL_DIR/bin\""
echo "Then run from any directory:  pi-java --version"
