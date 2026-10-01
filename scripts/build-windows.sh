#!/usr/bin/env bash
# Builds Fuse for Windows (x64), on Windows (Git Bash, as in CI):
#
#   build/windows/Fuse-<fuse.version>-windows-x64.msi   installer for the signed-in user
#   build/windows/Fuse-<fuse.version>-windows-x64.zip   portable: unzip anywhere, run Fuse.exe
#
#   scripts/build-windows.sh
#
# The installer goes to %LOCALAPPDATA%\Programs\Fuse without an administrator prompt. The zip holds
# an empty FuseData folder next to Fuse.exe, which makes Fuse keep everything there (a USB drive
# works). Both are checked with Fuse's own --self-test before they are kept.
#
# Environment:
#   GRADLE_ARGS="..."   extra Gradle arguments (for example "--no-daemon")
#   SKIP_SELF_TEST=1    don't run --self-test
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="$(sed -n 's/^fuse\.version=//p' gradle.properties | head -n 1 | tr -d '[:space:]\r')"
if [[ -z "$VERSION" ]]; then
  echo "error: fuse.version is missing from gradle.properties" >&2
  exit 1
fi
BINARIES="app/desktop/build/compose/binaries/main-release"
DIST="$BINARIES/app/Fuse"
OUT_DIR="build/windows"
NAME="Fuse-$VERSION-windows-x64"

echo "==> Building the Windows installer and app (Fuse $VERSION)"
# shellcheck disable=SC2086
./gradlew :app:desktop:createReleaseDistributable :app:desktop:packageReleaseMsi --console=plain ${GRADLE_ARGS:-}
if [[ ! -f "$DIST/Fuse.exe" ]]; then
  echo "error: $DIST/Fuse.exe not found" >&2
  exit 1
fi

if [[ "${SKIP_SELF_TEST:-0}" != "1" ]]; then
  # Fuse.exe is a windowed program with no console, so a console Java prints the report first. The
  # bundled runtime has no java.exe (jpackage strips its commands), so that is the JDK that built it.
  JAVA_BIN="$DIST/runtime/bin/java.exe"
  if [[ ! -f "$JAVA_BIN" ]]; then
    JAVA_BIN="java"
    if [[ -n "${JAVA_HOME:-}" ]]; then
      JAVA_BIN="$(cygpath -u "$JAVA_HOME" 2>/dev/null || printf '%s' "$JAVA_HOME")/bin/java"
    fi
  fi
  echo "==> Self-test (the packaged classpath)"
  "$JAVA_BIN" -cp "$DIST/app/*" io.github.matiyaaa.fuse.desktop.MainKt --self-test
  echo "==> Self-test (Fuse.exe and its bundled runtime)"
  # Its exit code is the result.
  "$DIST/Fuse.exe" --self-test
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
MSI="$(ls "$BINARIES"/msi/*.msi | head -n 1)"
cp "$MSI" "$OUT_DIR/$NAME.msi"

echo "==> Packing the portable zip"
STAGE="$OUT_DIR/stage/Fuse"
mkdir -p "$STAGE"
cp -a "$DIST/." "$STAGE/"
mkdir -p "$STAGE/FuseData"
cat > "$STAGE/FuseData/README.txt" <<'TXT'
Portable Fuse keeps its library, settings and art in this folder.
Delete the folder to make Fuse use AppData instead.
TXT
( cd "$OUT_DIR/stage" && powershell.exe -NoProfile -NonInteractive -Command "Compress-Archive -Path 'Fuse' -DestinationPath '..\\$NAME.zip' -Force" )
rm -rf "$OUT_DIR/stage"

ls -l "$OUT_DIR"
