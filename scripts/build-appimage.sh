#!/usr/bin/env bash
# Builds the Linux AppImage: build/appimage/Fuse-<fuse.version>-x86_64.AppImage
#
#   scripts/build-appimage.sh
#
# Steps: Gradle builds the release distributable (a folder with Fuse and its own Java runtime),
# this script wraps it in an AppDir (AppRun, fuse.desktop, fuse.png) and appimagetool packs it.
#
# Environment:
#   SKIP_GRADLE=1       reuse an existing app/desktop/build/compose/binaries/main-release/app/fuse
#   GRADLE_ARGS="..."   extra Gradle arguments (for example "--no-daemon")
#   APPIMAGETOOL=path   use this appimagetool instead of downloading it into build/tools/
#
# Needs network access the first time (appimagetool, and the AppImage runtime it fetches).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="$(sed -n 's/^fuse\.version=//p' gradle.properties | head -n 1 | tr -d '[:space:]')"
if [[ -z "$VERSION" ]]; then
  echo "error: fuse.version is missing from gradle.properties" >&2
  exit 1
fi
ARCH="x86_64"
PACKAGING="app/desktop/packaging"
DIST="app/desktop/build/compose/binaries/main-release/app/fuse"
OUT_DIR="build/appimage"
APPDIR="$OUT_DIR/Fuse.AppDir"
OUTPUT="$OUT_DIR/Fuse-$VERSION-$ARCH.AppImage"
TOOL_URL="https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"

if [[ "${SKIP_GRADLE:-0}" != "1" ]]; then
  echo "==> Building the release distributable (Fuse $VERSION)"
  # shellcheck disable=SC2086
  ./gradlew :app:desktop:createReleaseDistributable --console=plain ${GRADLE_ARGS:-}
fi
if [[ ! -x "$DIST/bin/fuse" ]]; then
  echo "error: $DIST/bin/fuse not found; run without SKIP_GRADLE=1" >&2
  exit 1
fi

echo "==> Assembling $APPDIR"
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/lib" "$APPDIR/usr/share/applications" "$APPDIR/usr/share/icons/hicolor/256x256/apps"
cp -a "$DIST" "$APPDIR/usr/lib/fuse"
install -m 0755 "$PACKAGING/AppRun" "$APPDIR/AppRun"
install -m 0644 "$PACKAGING/fuse.desktop" "$APPDIR/fuse.desktop"
install -m 0644 "$PACKAGING/fuse.desktop" "$APPDIR/usr/share/applications/fuse.desktop"
if command -v rsvg-convert >/dev/null 2>&1; then
  rsvg-convert -w 256 -h 256 "$PACKAGING/fuse.svg" -o "$APPDIR/fuse.png"
else
  # fuse.png is committed, rendered from the same geometry by packaging/RenderIcon.java.
  install -m 0644 "$PACKAGING/fuse.png" "$APPDIR/fuse.png"
fi
install -m 0644 "$APPDIR/fuse.png" "$APPDIR/usr/share/icons/hicolor/256x256/apps/fuse.png"
ln -sf fuse.png "$APPDIR/.DirIcon"

TOOL="${APPIMAGETOOL:-build/tools/appimagetool-$ARCH.AppImage}"
if [[ ! -x "$TOOL" ]]; then
  echo "==> Downloading appimagetool"
  mkdir -p "$(dirname "$TOOL")"
  curl -fL --retry 3 --connect-timeout 20 -o "$TOOL.part" "$TOOL_URL"
  chmod +x "$TOOL.part"
  mv "$TOOL.part" "$TOOL"
fi

echo "==> Packing $OUTPUT"
rm -f "$OUTPUT"
# APPIMAGE_EXTRACT_AND_RUN lets appimagetool (itself an AppImage) run where FUSE is unavailable, as in CI containers.
ARCH="$ARCH" VERSION="$VERSION" APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --no-appstream "$APPDIR" "$OUTPUT"
chmod +x "$OUTPUT"

echo "$OUTPUT"
