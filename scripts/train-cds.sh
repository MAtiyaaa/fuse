#!/usr/bin/env bash
# Builds a class data sharing archive for a Fuse distributable, and points its launcher at it, so
# Fuse starts faster and its first screens don't wait on loading their classes. On Linux (a virtual
# screen, Xvfb), Windows (Git Bash, the signed-in desktop) and macOS (the signed-in desktop):
#
#   scripts/train-cds.sh <distributable>   Linux: the folder holding bin/fuse
#                                           Windows: the folder holding Fuse.exe
#                                           macOS: Fuse.app
#
# Fuse runs twice: once to make a fresh profile, which is then marked as set up, and once moving
# through Home (and, with xdotool on Linux, every tab and Settings) while Java writes down each
# class it loads. On Linux two more starts are written down: one whose scan finds a game, and that
# game started as a Steam entry starts it (Fuse Fast Start, `--run`), whose time is then reported
# with the archive in use. Fuse's own launcher (the runtime inside has no java command) turns the lists
# into fuse.jsa beside Fuse's jars, and Fuse's launcher settings name it. Java checks the archive
# when Fuse starts and goes on without it if anything doesn't match, so this can only help; the
# folder can move (an AppImage mounts somewhere new every time, an installer puts Fuse anywhere).
# The training profile never stays in the distributable.
#
# Anything missing or failing leaves the distributable as it was: the archive only makes it faster.
# Needs python3 (or python); on Linux Xvfb, and xdotool for the walk through the screens.
set -uo pipefail

DIST="${1:?usage: train-cds.sh <distributable>}"
case "$(uname -s)" in
    Linux) OS=linux; LAUNCHER="$DIST/bin/fuse"; APPDIR="$DIST/lib/app"; CFG="$APPDIR/fuse.cfg" ;;
    MINGW* | MSYS* | CYGWIN*) OS=windows; LAUNCHER="$DIST/Fuse.exe"; APPDIR="$DIST/app"; CFG="$APPDIR/Fuse.cfg" ;;
    Darwin) OS=macos; LAUNCHER="$DIST/Contents/MacOS/Fuse"; APPDIR="$DIST/Contents/app"; CFG="$APPDIR/Fuse.cfg" ;;
    *) echo "warning: no class data sharing archive: $(uname -s) isn't known" >&2; exit 0 ;;
esac
ARCHIVE="$APPDIR/fuse.jsa"
# Java on Windows takes Windows paths (with forward slashes).
native() { if [[ "$OS" == windows ]]; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

skip() {
    echo "warning: no class data sharing archive: $1" >&2
    rm -f "$ARCHIVE"
    [[ -f "$CFG" ]] && { grep -v 'fuse\.jsa\|-Xshare:auto' "$CFG" >"$CFG.tmp"; mv "$CFG.tmp" "$CFG"; }
    exit 0
}

[[ -x "$LAUNCHER" && -f "$CFG" ]] || skip "$DIST is not a distributable"
PYTHON=""
for candidate in python3 python; do
    if "$candidate" -c "import sqlite3" >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
done
[[ -n "$PYTHON" ]] || skip "python3 isn't installed"

WORK="$(mktemp -d)"
# The training profile: a FuseData folder beside a Windows Fuse.exe (Fuse's portable mode), a home
# of its own elsewhere. Never left in the distributable.
PORTABLE="$DIST/FuseData"
[[ "$OS" == windows && -e "$PORTABLE" ]] && skip "$PORTABLE already exists"
cleanup() {
    [[ -n "${FUSE_PID:-}" ]] && stop
    [[ -n "${XVFB_PID:-}" ]] && kill "$XVFB_PID" 2>/dev/null
    [[ -n "${MADE_PORTABLE:-}" ]] && rm -rf "$PORTABLE"
    rm -rf "$WORK"
}
trap cleanup EXIT

PROFILE_OPTS=""
case "$OS" in
    linux)
        command -v Xvfb >/dev/null 2>&1 || skip "Xvfb isn't installed"
        Xvfb ":97" -screen 0 1920x1080x24 >/dev/null 2>&1 &
        XVFB_PID=$!
        sleep 2
        export HOME="$WORK/home" DISPLAY=":97"
        mkdir -p "$HOME"
        DATA="$HOME/.local/share/fuse"
        ;;
    windows)
        mkdir -p "$PORTABLE" && MADE_PORTABLE=1
        DATA="$PORTABLE/data"
        ;;
    macos)
        mkdir -p "$WORK/home"
        PROFILE_OPTS="-Duser.home=$WORK/home"
        DATA="$WORK/home/Library/Application Support/Fuse"
        ;;
esac
LOG="$DATA/logs/fuse.log"
DB="$DATA/fuse.db"

# Starts Fuse with Java's options [1] and waits until its first frames are drawn.
start() {
    rm -f "$LOG"
    JAVA_TOOL_OPTIONS="$PROFILE_OPTS $1" "$LAUNCHER" >"$WORK/run.log" 2>&1 &
    FUSE_PID=$!
    for _ in $(seq 1 900); do
        grep -q "Display:" "$LOG" 2>/dev/null && return 0
        kill -0 "$FUSE_PID" 2>/dev/null || return 1
        sleep 0.1
    done
    return 1
}

# Closes Fuse the way a person does, so Java finishes writing what it was asked to.
stop() {
    if [[ "$OS" == windows ]]; then
        local winpid
        winpid="$(cat "/proc/$FUSE_PID/winpid" 2>/dev/null)"
        [[ -n "$winpid" ]] && taskkill //PID "$winpid" >/dev/null 2>&1
    else
        kill "$FUSE_PID" 2>/dev/null
    fi
    for _ in $(seq 1 150); do
        kill -0 "$FUSE_PID" 2>/dev/null || break
        sleep 0.1
    done
    if kill -0 "$FUSE_PID" 2>/dev/null; then
        [[ "$OS" == windows && -n "${winpid:-}" ]] && taskkill //F //PID "$winpid" >/dev/null 2>&1
        kill -9 "$FUSE_PID" 2>/dev/null
    fi
    wait "$FUSE_PID" 2>/dev/null
    FUSE_PID=""
}

press() {
    [[ "$OS" == linux ]] || return 0
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
"$PYTHON" - "$(native "$DB")" <<'EOF' || skip "the profile couldn't be marked as set up"
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
start "-XX:DumpLoadedClassList=$(native "$LIST")" || skip "the training start didn't come up"
sleep 3
# Along Home's shelves, then each tab in turn, Settings, a game's options and back.
press Right Right Down Right Up Left
for _ in 1 2 3 4 5; do press e Down Right Up; done
press Return Down Down Escape Tab Down Escape Escape
sleep 2
stop
[[ -s "$LIST" ]] || skip "the training start listed no classes"

# Linux: a game in the library, found by a start's scan, then started the way a Steam entry starts
# it (Fuse Fast Start, `--run <id>`, no window), with a stand-in for mGBA that ends at once. Both
# starts write down their classes too, so a start with games and Steam's Play are quick as well.
# Anything failing here leaves the archive as the screens alone make it.
LISTS=("$LIST")
FAST_ID=""
fast_start() {
    local bin="$WORK/bin" games="$HOME/Games/gba" id
    mkdir -p "$bin" "$games"
    printf '#!/bin/sh\nexit 0\n' >"$bin/mgba" && chmod +x "$bin/mgba"
    head -c 4096 /dev/urandom >"$games/Fuse Training (World).gba"
    "$PYTHON" - "$DB" "$HOME/Games" <<'EOF' || return 1
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
db.execute("insert or ignore into library_source (path, label, kind) values (?, 'Games', 'ROMS_ROOT')", (sys.argv[2],))
db.commit()
EOF
    export PATH="$bin:$PATH"
    start "-XX:DumpLoadedClassList=$WORK/scan.lst" || return 1
    id=""
    for _ in $(seq 1 300); do
        id="$("$PYTHON" -c 'import sqlite3, sys; r = sqlite3.connect(sys.argv[1]).execute("select id from game limit 1").fetchone(); print(r[0] if r else "")' "$DB" 2>/dev/null)"
        [[ -n "$id" ]] && break
        sleep 0.2
    done
    sleep 2
    stop
    [[ -s "$WORK/scan.lst" ]] && LISTS+=("$WORK/scan.lst")
    [[ -n "$id" ]] || return 1
    FAST_ID="$id"
    rm -f "$LOG"
    JAVA_TOOL_OPTIONS="-XX:DumpLoadedClassList=$WORK/run.lst" timeout 60 "$LAUNCHER" --run "$id" >"$WORK/run.log" 2>&1
    grep -q "Fast Start: the game started" "$LOG" 2>/dev/null || return 1
    [[ -s "$WORK/run.lst" ]] && LISTS+=("$WORK/run.lst")
}
if [[ "$OS" == linux ]]; then
    echo "==> Class data sharing: a library with a game, and a game started as Steam starts it"
    fast_start || echo "warning: the archive leaves out Fast Start ($(tail -n 2 "$WORK/run.log" 2>/dev/null | tr '\n' ' '))" >&2
fi

echo "==> Class data sharing: writing the archive"
# The lists as one, each class once. A list names classes with ids of its own run (`id: 12`),
# which mean nothing beside another run's, so they are left out, as in the JDK's own list. Java
# reads lines up to 4096 characters; a few long lambda descriptions are left out.
sed -E 's/ id: [0-9]+$//' "${LISTS[@]}" | awk 'length($0) < 4000 && !seen[$0]++' >"$WORK/classes.trimmed"
rm -f "$ARCHIVE"
JAVA_TOOL_OPTIONS="-Xshare:dump -XX:SharedClassListFile=$(native "$WORK/classes.trimmed") -XX:SharedArchiveFile=$(native "$ARCHIVE")" "$LAUNCHER" >"$WORK/dump.log" 2>&1 \
    || skip "the archive couldn't be written ($(grep -v 'Preload Warning\|ClassNotFound\|Skipping' "$WORK/dump.log" | tail -n 3 | tr '\n' ' '))"
[[ -s "$ARCHIVE" ]] || skip "the archive is empty"

if ! grep -q "SharedArchiveFile" "$CFG"; then
    # In the [JavaOptions] section, after its last java-options line, with the file's own line ends.
    eol=""; grep -q $'\r$' "$CFG" && eol=$'\r'
    awk -v eol="$eol" '
        { lines[NR] = $0 } /^java-options=/ { last = NR }
        END {
            for (i = 1; i <= NR; i++) {
                print lines[i]
                if (i == last) {
                    print "java-options=-XX:SharedArchiveFile=$APPDIR/fuse.jsa" eol
                    print "java-options=-Xshare:auto" eol
                }
            }
        }' "$CFG" >"$CFG.tmp" && mv "$CFG.tmp" "$CFG"
    grep -q "SharedArchiveFile" "$CFG" || skip "Fuse's launcher settings have no Java options"
fi

# The archive is used: the classes come from it, not from the jars.
start "-Xlog:class+load=info:file=$(native "$WORK/load.txt")" || skip "Fuse didn't start with the archive"
stop
shared="$(grep -c 'source: shared objects file' "$WORK/load.txt" || true)"
[[ "${shared:-0}" -gt 1000 ]] || skip "Fuse didn't use the archive (${shared:-0} classes from it)"
echo "==> Class data sharing: $(du -h "$ARCHIVE" | cut -f1), $shared classes shared"

# How long Steam's Play takes to reach the game with the archive, as Fuse measures it each time.
if [[ -n "$FAST_ID" ]]; then
    rm -f "$LOG"
    timeout 60 "$LAUNCHER" --run "$FAST_ID" >"$WORK/run.log" 2>&1
    took="$(grep -o 'the game started [0-9]* ms after Fuse did' "$LOG" 2>/dev/null | grep -o '[0-9]*' | tail -n 1)"
    [[ -n "$took" ]] && echo "==> Fast Start: the game started $took ms after Fuse started"
fi
