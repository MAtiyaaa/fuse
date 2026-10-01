#!/usr/bin/env bash
# Builds Fuse for macOS on a Mac, for that Mac's architecture:
#
#   build/macos/Fuse-<fuse.version>-macos-arm64.dmg   on Apple silicon
#   build/macos/Fuse-<fuse.version>-macos-x64.dmg     on Intel (or under Rosetta)
#
#   scripts/build-macos.sh
#
# The app is signed ad hoc (Apple silicon runs nothing unsigned), not notarized: the first time,
# open it with right-click, Open (or allow it in System Settings, Privacy & Security). It is checked
# with Fuse's own --self-test, then put on a disk image next to a link to Applications.
#
# Environment:
#   GRADLE_ARGS="..."   extra Gradle arguments (for example "--no-daemon")
#   SKIP_SELF_TEST=1    don't run --self-test
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="$(sed -n 's/^fuse\.version=//p' gradle.properties | head -n 1 | tr -d '[:space:]')"
if [[ -z "$VERSION" ]]; then
  echo "error: fuse.version is missing from gradle.properties" >&2
  exit 1
fi
case "$(uname -m)" in
  arm64) ARCH="arm64" ;;
  x86_64) ARCH="x64" ;;
  *) echo "error: unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac
BINARIES="app/desktop/build/compose/binaries/main-release"
APP="$BINARIES/app/Fuse.app"
OUT_DIR="build/macos"
NAME="Fuse-$VERSION-macos-$ARCH"

echo "==> Building the app (Fuse $VERSION, $ARCH)"
# shellcheck disable=SC2086
./gradlew :app:desktop:createReleaseDistributable --console=plain ${GRADLE_ARGS:-}
if [[ ! -x "$APP/Contents/MacOS/Fuse" ]]; then
  echo "error: $APP/Contents/MacOS/Fuse not found" >&2
  exit 1
fi
echo "==> Signing ad hoc"
codesign --force --deep --sign - "$APP"
codesign --verify --deep --strict "$APP"

if [[ "${SKIP_SELF_TEST:-0}" != "1" ]]; then
  echo "==> Self-test"
  "$APP/Contents/MacOS/Fuse" --self-test
fi

echo "==> Making the disk image"
rm -rf "$OUT_DIR"
STAGE="$OUT_DIR/stage"
mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/Fuse.app"
ln -s /Applications "$STAGE/Applications"
hdiutil create -volname "Fuse $VERSION" -srcfolder "$STAGE" -fs HFS+ -format UDZO -ov "$OUT_DIR/$NAME.dmg"
rm -rf "$STAGE"
ls -l "$OUT_DIR"
