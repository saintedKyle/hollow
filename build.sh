#!/usr/bin/env bash
# hollow build. Needs a JDK 17 or newer.
#
#   ./build.sh              jars + a jlink runtime in dist/
#   ./build.sh --no-runtime jars only
#   JAVA_HOME=/path ./build.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD="$ROOT/build"
DIST="$ROOT/dist"
RUNTIME=1

for arg in "$@"; do
    case "$arg" in
        --no-runtime) RUNTIME=0 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

find_jdk() {
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
        echo "$JAVA_HOME"; return
    fi
    for candidate in "$ROOT"/.tools/*; do
        if [[ -x "$candidate/bin/javac" ]]; then echo "$candidate"; return; fi
    done
    if command -v javac >/dev/null 2>&1; then
        dirname "$(dirname "$(readlink -f "$(command -v javac)")")"; return
    fi
    echo "no JDK found. set JAVA_HOME or drop one in .tools/" >&2
    exit 1
}

JDK="$(find_jdk)"
JAVAC="$JDK/bin/javac"
JAR="$JDK/bin/jar"
JLINK="$JDK/bin/jlink"

echo "jdk    $JDK"
echo "out    $DIST"

rm -rf "$BUILD"
mkdir -p "$BUILD" "$DIST"
find "$DIST" -maxdepth 1 -type f -delete
if [[ "$RUNTIME" -eq 1 ]]; then rm -rf "$DIST/runtime"; fi

compile() {
    local src="$1" out="$2" cp="${3:-}"
    mkdir -p "$out"
    local args=(-encoding UTF-8 -g -Xlint:all,-serial,-this-escape -Werror -d "$out")
    [[ -n "$cp" ]] && args+=(-cp "$cp")
    mapfile -t sources < <(find "$src" -name '*.java')
    "$JAVAC" "${args[@]}" "${sources[@]}"
    echo "   ok  $(basename "$src")"
}

make_jar() {
    local out="$1" manifest="$2" classes="$3"
    "$JAR" --create --file "$out" --manifest "$manifest" -C "$classes" .
}

manifest() {
    local path="$1"; shift
    printf 'Manifest-Version: 1.0\r\n' > "$path"
    for line in "$@"; do printf '%s\r\n' "$line" >> "$path"; done
    printf '\r\n' >> "$path"
}

echo 'compile hollow-core'
compile "$ROOT/src/core" "$BUILD/core"
manifest "$BUILD/core.mf" 'Agent-Class: hollow.core.Bootstrap' 'Can-Redefine-Classes: true' 'Can-Retransform-Classes: true'
make_jar "$DIST/hollow-core.jar" "$BUILD/core.mf" "$BUILD/core"

echo 'compile hollow'
compile "$ROOT/src/injector" "$BUILD/injector"
manifest "$BUILD/injector.mf" 'Main-Class: hollow.injector.Main'
make_jar "$DIST/hollow.jar" "$BUILD/injector.mf" "$BUILD/injector"

echo 'compile example-agent'
compile "$ROOT/src/example" "$BUILD/example"
manifest "$BUILD/example.mf" 'Agent-Class: hollow.example.DemoAgent'
make_jar "$DIST/example-agent.jar" "$BUILD/example.mf" "$BUILD/example"

echo 'compile fixtures'
compile "$ROOT/src/fixtures" "$BUILD/fixtures"
manifest "$BUILD/probe.mf" 'Agent-Class: hollow.probe.Probe'
make_jar "$DIST/probe-agent.jar" "$BUILD/probe.mf" "$BUILD/fixtures"

if [[ "$RUNTIME" -eq 1 ]]; then
    echo 'jlink runtime'
    "$JLINK" \
        --add-modules java.base,java.desktop,jdk.attach,java.management,java.instrument \
        --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
        --output "$DIST/runtime"

    cat > "$DIST/hollow" <<'LAUNCHER'
#!/usr/bin/env sh
HERE="$(cd "$(dirname "$0")" && pwd)"
exec "$HERE/runtime/bin/java" -cp "$HERE/hollow.jar" hollow.injector.Main "$@"
LAUNCHER
    chmod +x "$DIST/hollow"
fi

echo
echo 'built:'
ls -la "$DIST"
