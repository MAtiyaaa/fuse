#!/usr/bin/env bash
# Builds a class data sharing archive for a Fuse distributable on Linux, and points its launcher
# at it, so Fuse starts faster and its first screens don't wait on loading their classes.
#
#   scripts/train-cds.sh <distributable folder, the one holding bin/fuse>
#
# Fuse runs twice on a virtual screen (Xvfb): once to make a fresh profile, which is then marked
# as set up, and once moving through Home, every tab and Settings while Java writes down each class
# it loads. Fuse's own launcher (the runtime inside has no java command) then turns that list into
# lib/app/fuse.jsa, and lib/app/fuse.cfg names it. Java checks the archive when Fuse starts and goes
# on without it if anything doesn't match, so this can only help; the folder can move (an AppImage
# mounts somewhere new every time).
#
# Anything missing or failing leaves the distributable as it was: the archive only makes it faster.
# Needs Xvfb and python3; xdotool for the walk through the screens (without it, Home only).
set -uo pipefail

DIST="${1:?usage: train-cds.sh <distributable folder>}"
LAUNCHER="$DIST/bin/fuse"
CFG="$DIST/lib/app/fuse.cfg"
ARCHIVE="$DIST/lib/app/fuse.jsa"

skip() {
    echo "warning: no class data sharing archive: $1" >&2
    rm -f "$ARCHIVE"
    [[ -f "$CFG" ]] && sed -i '/fuse\.jsa/d; /-Xshare:auto/d' "$CFG"
    exit 0
}

[[ -x "$LAUNCHER" && -f "$CFG" ]] || skip "$DIST is not a distributable"
command -v Xvfb >/dev/null 2>&1 || skip "Xvfb isn't installed"
command -v python3 >/dev/null 2>&1 || skip "python3 isn't installed"

WORK="$(mktemp -d)"
DISPLAY_NUM=":97"
cleanup() {
    [[ -n "${FUSE_PID:-}" ]] && kill "$FUSE_PID" 2>/dev/null
    [[ -n "${XVFB_PID:-}" ]] && kill "$XVFB_PID" 2>/dev/null
    rm -rf "$WORK"
}
trap cleanup EXIT

Xvfb "$DISPLAY_NUM" -screen 0 1920x1080x24 >/dev/null 2>&1 &
XVFB_PID=$!
sleep 2

export HOME="$WORK/home" DISPLAY="$DISPLAY_NUM"
mkdir -p "$HOME"
LOG="$HOME/.local/share/fuse/logs/fuse.log"
DB="$HOME/.local/share/fuse/fuse.db"

# Starts Fuse with Java's options [1] and waits until its first frames are drawn.
start() {
    rm -f "$LOG"
    JAVA_TOOL_OPTIONS="$1" "$LAUNCHER" >"$WORK/run.log" 2>&1 &
    FUSE_PID=$!
    for _ in $(seq 1 600); do
        grep -q "Display:" "$LOG" 2>/dev/null && return 0
        kill -0 "$FUSE_PID" 2>/dev/null || return 1
        sleep 0.1
    done
    return 1
}

stop() {
    kill "$FUSE_PID" 2>/dev/null
    wait "$FUSE_PID" 2>/dev/null
    FUSE_PID=""
}

press() {
    command -v xdotool >/dev/null 2>&1 || return 0
    for key in "$@"; do
        xdotool key "$key"
        sleep 0.6
    done
}

echo "==> Class data sharing: a first start, to make a profile"
start "" || skip "Fuse didn't start on the virtual screen ($(tail -n 3 "$WORK/run.log" | tr '\n' ' '))"
sleep 3
stop

# The training start skips setup, so it reaches Home and the rest.
python3 - "$DB" <<'EOF' || skip "the profile couldn't be marked as set up"
import json, sqlite3, sys
db = sqlite3.connect(sys.argv[1])
row = db.execute("select value_json from setting where key = 'app'").fetchone()
if row is None:
    sys.exit("no app settings")
prefs = json.loads(row[0])
prefs.setdefault("onboarding", {})["completed"] = True
db.execute("update setting set value_json = ? where key = 'app'", (json.dumps(prefs),))
db.commit()
EOF

echo "==> Class data sharing: a start that goes through the screens"
LIST="$WORK/classes.lst"
start "-XX:DumpLoadedClassList=$LIST" || skip "the training start didn't come up"
sleep 3
# Along Home's shelves, then each tab in turn, Settings, a game's options and back.
press Right Right Down Right Up Left
for _ in 1 2 3 4 5; do press e Down Right Up; done
press Return Down Down Escape Tab Down Escape Escape
sleep 2
stop
[[ -s "$LIST" ]] || skip "the training start listed no classes"

echo "==> Class data sharing: writing the archive"
# Java reads lines up to 4096 characters; a few long lambda descriptions are left out.
awk 'length($0) < 4000' "$LIST" >"$WORK/classes.trimmed"
rm -f "$ARCHIVE"
JAVA_TOOL_OPTIONS="-Xshare:dump -XX:SharedClassListFile=$WORK/classes.trimmed -XX:SharedArchiveFile=$ARCHIVE" "$LAUNCHER" >"$WORK/dump.log" 2>&1 \
    || skip "the archive couldn't be written ($(grep -v 'Preload Warning\|ClassNotFound\|Skipping' "$WORK/dump.log" | tail -n 3 | tr '\n' ' '))"
[[ -s "$ARCHIVE" ]] || skip "the archive is empty"

if ! grep -q "SharedArchiveFile" "$CFG"; then
    # In the [JavaOptions] section, which holds the java-options lines.
    printf 'java-options=-XX:SharedArchiveFile=$APPDIR/fuse.jsa\njava-options=-Xshare:auto\n' >>"$CFG"
fi

# The archive is used: the classes come from it, not from the jars.
start "-Xlog:class+load=info:file=$WORK/load.txt" || skip "Fuse didn't start with the archive"
stop
shared="$(grep -c 'source: shared objects file' "$WORK/load.txt" || true)"
[[ "${shared:-0}" -gt 1000 ]] || skip "Fuse didn't use the archive (${shared:-0} classes from it)"
echo "==> Class data sharing: $(du -h "$ARCHIVE" | cut -f1), $shared classes shared"
