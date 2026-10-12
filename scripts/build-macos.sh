#!/usr/bin/env bash
# Builds Fuse for macOS on a Mac, for that Mac's architecture:
#
#   build/macos/Fuse-<fuse.version>-macos-arm64.dmg   on Apple silicon
#   build/macos/Fuse-<fuse.version>-macos-x64.dmg     on Intel (or under Rosetta)
#
#   scripts/build-macos.sh
#
# With a Developer ID certificate, the app is signed with it under the hardened runtime by Compose's
# own signing (the native libraries inside its jars too, with the JVM's entitlements), the disk image
# is signed, and with notarization credentials Apple notarizes it and the ticket is stapled to it,
# so it opens like any downloaded app. Without a certificate it is signed ad hoc (Apple silicon runs
# nothing unsigned) and not notarized: the first time, open it with right-click, Open (or allow it in
# System Settings, Privacy & Security). Either way it is checked with Fuse's own --self-test, then
# put on a disk image next to a link to Applications.
#
# Environment:
#   GRADLE_ARGS="..."              extra Gradle arguments (for example "--no-daemon")
#   SKIP_SELF_TEST=1               don't run --self-test
#   MACOS_SIGNING_IDENTITY=...     "Developer ID Application: Name (TEAMID)", to sign with it
#   MACOS_SIGNING_KEYCHAIN=...     the keychain holding it, when not the default one
#   MACOS_NOTARY_APPLE_ID=...      with MACOS_NOTARY_PASSWORD (an app-specific password) and
#   MACOS_NOTARY_TEAM_ID=...       MACOS_NOTARY_TEAM_ID: notarize and staple the disk image
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

SIGNING=()
if [[ -n "${MACOS_SIGNING_IDENTITY:-}" ]]; then
  SIGNING+=("-Pcompose.desktop.mac.sign=true" "-Pcompose.desktop.mac.signing.identity=$MACOS_SIGNING_IDENTITY")
  if [[ -n "${MACOS_SIGNING_KEYCHAIN:-}" ]]; then
    SIGNING+=("-Pcompose.desktop.mac.signing.keychain=$MACOS_SIGNING_KEYCHAIN")
  fi
fi

echo "==> Building the app (Fuse $VERSION, $ARCH)"
# shellcheck disable=SC2086
./gradlew :app:desktop:createReleaseDistributable --console=plain ${SIGNING[@]+"${SIGNING[@]}"} ${GRADLE_ARGS:-}
if [[ ! -x "$APP/Contents/MacOS/Fuse" ]]; then
  echo "error: $APP/Contents/MacOS/Fuse not found" >&2
  exit 1
fi
if [[ -n "${MACOS_SIGNING_IDENTITY:-}" ]]; then
  echo "==> Signed with the Developer ID"
  codesign --verify --deep --strict --verbose=2 "$APP"
  codesign --display --verbose=2 "$APP" 2>&1 | grep -E "^(Authority|TeamIdentifier|Runtime)" || true
else
  echo "==> Signing ad hoc"
  codesign --force --deep --sign - "$APP"
  codesign --verify --deep --strict "$APP"
fi

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
# On a fresh machine hdiutil sometimes finds the new volume busy ("Resource busy") while Spotlight or
# XProtect is still looking at it; a few seconds later it works.
for attempt in 1 2 3 4 5; do
  if hdiutil create -volname "Fuse $VERSION" -srcfolder "$STAGE" -fs HFS+ -format UDZO -ov "$OUT_DIR/$NAME.dmg"; then
    break
  fi
  if [ "$attempt" -eq 5 ]; then
    echo "hdiutil could not make the disk image after 5 tries" >&2
    exit 1
  fi
  echo "hdiutil was busy; trying again in $((attempt * 5)) s ($attempt of 5)"
  sleep $((attempt * 5))
done
rm -rf "$STAGE"
DMG="$OUT_DIR/$NAME.dmg"

if [[ -n "${MACOS_SIGNING_IDENTITY:-}" ]]; then
  echo "==> Signing the disk image"
  KEYCHAIN_ARGS=()
  if [[ -n "${MACOS_SIGNING_KEYCHAIN:-}" ]]; then KEYCHAIN_ARGS=(--keychain "$MACOS_SIGNING_KEYCHAIN"); fi
  codesign --force --timestamp --sign "$MACOS_SIGNING_IDENTITY" ${KEYCHAIN_ARGS[@]+"${KEYCHAIN_ARGS[@]}"} "$DMG"
  codesign --verify --verbose=2 "$DMG"
  if [[ -n "${MACOS_NOTARY_APPLE_ID:-}" && -n "${MACOS_NOTARY_PASSWORD:-}" && -n "${MACOS_NOTARY_TEAM_ID:-}" ]]; then
    echo "==> Notarizing (Apple checks it; this takes a few minutes)"
    RESULT="$(xcrun notarytool submit "$DMG" --apple-id "$MACOS_NOTARY_APPLE_ID" --password "$MACOS_NOTARY_PASSWORD" \
      --team-id "$MACOS_NOTARY_TEAM_ID" --wait --timeout 45m --output-format json)" || true
    echo "$RESULT"
    if ! printf '%s' "$RESULT" | grep -q '"status" *: *"Accepted"'; then
      ID="$(printf '%s' "$RESULT" | sed -nE 's/.*"id" *: *"([^"]+)".*/\1/p' | head -n 1)"
      if [[ -n "$ID" ]]; then
        xcrun notarytool log "$ID" --apple-id "$MACOS_NOTARY_APPLE_ID" --password "$MACOS_NOTARY_PASSWORD" --team-id "$MACOS_NOTARY_TEAM_ID" || true
      fi
      echo "error: Apple did not accept the disk image for notarization" >&2
      exit 1
    fi
    echo "==> Stapling the ticket"
    xcrun stapler staple "$DMG"
    xcrun stapler validate "$DMG"
    spctl --assess --type open --context context:primary-signature --verbose=2 "$DMG"
  else
    echo "warning: signed but not notarized (MACOS_NOTARY_APPLE_ID, MACOS_NOTARY_PASSWORD and MACOS_NOTARY_TEAM_ID aren't all set)" >&2
  fi
fi
ls -l "$OUT_DIR"
