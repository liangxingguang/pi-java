#!/usr/bin/env bash
# Build distributable pi-java packages: fat jar + jlink JRE + launcher,
# packed as tar.gz per target platform.
#
# jlink output is platform-specific, so cross targets use a downloaded JDK
# for that platform (Adoptium Temurin) to run its jlink.
#
# Usage:
#   ./dist.sh                    -> native target only (this machine's JDK)
#   ./dist.sh macos-aarch64      -> single cross target
#   ./dist.sh all                -> linux-x64 linux-aarch64 macos-x64 macos-aarch64
#   ./dist.sh native macos-x64   -> any combination
set -euo pipefail

REPO="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME="${JAVA_HOME:-}"
MVN="${MVN:-mvn}"
MODULES="java.base,java.compiler,java.desktop,java.management,java.naming,java.net.http,java.security.jgss,java.sql,jdk.httpserver,jdk.jfr,jdk.unsupported,jdk.crypto.ec,jdk.localedata"
DIST_OUT="$REPO/pi-java-dist/target/dist"
JDK_CACHE="$REPO/pi-java-dist/target/jdk-cache"
# JDK download source. Default: Tsinghua Adoptium mirror (fast in CN;
# exposes /Adoptium/<v>/jdk/<arch>/<os>/OpenJDK<v>U-jdk_<arch>_<os>_hotspot_<n>.tar.gz).
# Set JDK_SOURCE=adoptium-api to use api.adoptium.net instead (redirects to
# GitHub, unreachable from some networks).
MIRROR_BASE="${MIRROR_BASE:-https://mirrors.tuna.tsinghua.edu.cn/Adoptium}"
JDK_SOURCE="${JDK_SOURCE:-mirror}"
ADOPTIUM_API_BASE="${ADOPTIUM_API_BASE:-https://api.adoptium.net/v3/binary/latest/25/ga}"

NATIVE_OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$NATIVE_OS" in
    mingw*|msys*|cygwin*) NATIVE_OS="windows" ;;
    darwin*) NATIVE_OS="macos" ;;
esac
NATIVE_ARCH="$(uname -m)"
case "$NATIVE_ARCH" in
    x86_64)  NATIVE_ARCH="x64" ;;
    aarch64|arm64) NATIVE_ARCH="aarch64" ;;
esac

ALL_TARGETS="linux-x64 linux-aarch64 macos-x64 macos-aarch64"
TARGETS=()
if [[ $# -eq 0 ]]; then
    TARGETS+=("native")
elif [[ "$1" == "all" ]]; then
    read -ra TARGETS <<< "$ALL_TARGETS"
else
    read -ra TARGETS <<< "$@"
fi

for t in "${TARGETS[@]}"; do
    if [[ "$t" != "native" ]] && ! [[ " $ALL_TARGETS " == *" $t "* ]]; then
        echo "[dist] unknown target: $t (known: native, all, $ALL_TARGETS)"
        exit 1
    fi
done

if [[ -z "$JAVA_HOME" ]] || ! "$JAVA_HOME/bin/jlink" --version >/dev/null 2>&1; then
    echo "[dist] error: JAVA_HOME must point at a full JDK 25+ (needed for mvn + native jlink)"
    exit 1
fi

VERSION="$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' "$REPO/pom.xml" | head -1)"
echo "[dist] version: $VERSION"

echo "[dist] building fat jar..."
(cd "$REPO" && "$MVN" -q -pl pi-java-dist -am package -DskipTests)
JAR="$REPO/pi-java-dist/target/pi-java.jar"
[[ -f "$JAR" ]] || { echo "[dist] pi-java.jar not found"; exit 1; }

# resolve_target <target> -> sets JDKPLATFORM/JDKARCH (Adoptium terms) + TARDIR
resolve_target() {
    case "$1" in
        native)         JDKPLATFORM="$NATIVE_OS"; JDKARCH="$NATIVE_ARCH"; TARDIR="pi-java-$VERSION-$NATIVE_OS-$NATIVE_ARCH" ;;
        linux-x64)      JDKPLATFORM="linux";  JDKARCH="x64";      TARDIR="pi-java-$VERSION-linux-x64" ;;
        linux-aarch64)  JDKPLATFORM="linux";  JDKARCH="aarch64";  TARDIR="pi-java-$VERSION-linux-aarch64" ;;
        macos-x64)      JDKPLATFORM="macos";  JDKARCH="x64";      TARDIR="pi-java-$VERSION-macos-x64" ;;
        macos-aarch64)  JDKPLATFORM="macos";  JDKARCH="aarch64";  TARDIR="pi-java-$VERSION-macos-aarch64" ;;
    esac
}

# jlink_for <target> <output-dir>: pick JDK (local or downloaded) and run jlink
jlink_for() {
    local target="$1" out="$2"
    resolve_target "$target"
    local jlink_bin
    if [[ "$target" == "native" ]]; then
        jlink_bin="$JAVA_HOME/bin/jlink"
    else
        local key="$JDKPLATFORM-$JDKARCH"
        local jdk_dir="$JDK_CACHE/$key"
        if [[ ! -f "$jdk_dir/bin/jlink" ]]; then
            echo "[dist] downloading JDK 25 for $key..."
            mkdir -p "$jdk_dir"
            local tmp
            tmp="$(mktemp)"
            if [[ "$JDK_SOURCE" == "adoptium-api" ]]; then
                local url="$ADOPTIUM_API_BASE/$JDKPLATFORM/$JDKARCH/jdk/hotspot/normal/eclipse"
                curl -fSL --retry 3 --connect-timeout 15 -o "$tmp" "$url"
            else
                # mirror: map platform to its directory/file naming, pick newest
                local osdir="linux"
                [[ "$JDKPLATFORM" == "macos" ]] && osdir="mac"
                local listing
                listing="$(curl -fsSL --retry 3 --connect-timeout 15 \
                    "$MIRROR_BASE/25/jdk/$JDKARCH/$osdir/")"
                local fname
                fname="$(echo "$listing" | grep -oE "OpenJDK25U-jdk_${JDKARCH}_${osdir}_hotspot_[0-9._]+\\.tar\\.gz" | sort -uV | tail -1)"
                [[ -n "$fname" ]] || { echo "[dist] no JDK found on mirror for $key"; rm -f "$tmp"; return 1; }
                echo "[dist]   $fname"
                curl -fSL --retry 3 --connect-timeout 15 \
                    -o "$tmp" "$MIRROR_BASE/25/jdk/$JDKARCH/$osdir/$fname"
            fi
            if [[ "$JDKPLATFORM" == "macos" ]]; then
                # macOS archives extract to *.jdk/Contents/Home
                tar xzf "$tmp" -C "$jdk_dir" --strip-components=3
            else
                tar xzf "$tmp" -C "$jdk_dir" --strip-components=1
            fi
            rm -f "$tmp"
            [[ -f "$jdk_dir/bin/jlink" ]] || { echo "[dist] jlink missing after extraction for $key"; return 1; }
        fi
        jlink_bin="$jdk_dir/bin/jlink"
        if [[ "$JDKPLATFORM" != "$NATIVE_OS" ]]; then
            echo "[dist] error: cannot run a $JDKPLATFORM jlink on a $NATIVE_OS host."
            echo "       Cross targets must be built on a matching OS (e.g. build"
            echo "       linux-* on Linux, macos-* on macOS), or use two hosts with"
            echo "       the same JDK_CACHE."
            return 1
        fi
    fi
    rm -rf "$out"
    "$jlink_bin" --add-modules "$MODULES" --include-locales=en,zh \
        --output "$out" --strip-debug --no-header-files --no-man-pages \
        --compress zip-6
}

mkdir -p "$DIST_OUT"
for target in "${TARGETS[@]}"; do
    resolve_target "$target"
    local_dir="$DIST_OUT/$TARDIR"
    echo "[dist] === $target -> $TARDIR.tar.gz ==="
    rm -rf "$local_dir"
    mkdir -p "$local_dir/bin"

    cp "$JAR" "$local_dir/pi-java.jar"
    jlink_for "$target" "$local_dir/jre"

    cat > "$local_dir/bin/pi-java" <<SHIM
#!/usr/bin/env bash
exec "\$(dirname "\$0")/../jre/bin/java" -Dfile.encoding=UTF-8 -jar "\$(dirname "\$0")/../pi-java.jar" "\$@"
SHIM
    chmod +x "$local_dir/bin/pi-java"

    cat > "$local_dir/INSTALL.txt" <<DOC
pi-java $VERSION ($target)

Install: add the bin directory to PATH, e.g. in ~/.bashrc or ~/.zshrc:
    export PATH="\$PATH:$(pwd)/bin"
Then run from any directory:
    pi-java --version
DOC

    tar -czf "$DIST_OUT/$TARDIR.tar.gz" -C "$DIST_OUT" "$TARDIR"
    du -sh "$DIST_OUT/$TARDIR.tar.gz"
done

echo "[dist] done. Packages in $DIST_OUT:"
ls -1 "$DIST_OUT"/*.tar.gz
