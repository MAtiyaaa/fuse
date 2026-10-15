#!/usr/bin/env bash
# Re-encodes the menu music for Fuse: Opus at 64 kbps stereo in WebM, which Android's MediaPlayer
# plays from Android 5 and Fuse's desktop menu music decodes itself. Two songs ship inside Fuse; the
# rest are published on the repository's `music` branch and fetched the first time they are wanted.
#
#   tools/music/encode.sh <folder of source songs> <output folder>
#
# Prints each song's size and sha256, for BundledMusic.kt.
set -euo pipefail
src="$1"; out="$2"
mkdir -p "$out"
for f in "$src"/*.mp3 "$src"/*.flac "$src"/*.wav; do
  [ -e "$f" ] || continue
  id="$(basename "${f%.*}")"
  ffmpeg -v error -y -i "$f" -map_metadata -1 -vn -ac 2 -c:a libopus -b:a 64k -vbr on \
    -compression_level 10 -application audio -frame_duration 20 -f webm "$out/$id.webm"
  printf '%s %s %s\n' "$id" "$(stat -c %s "$out/$id.webm")" "$(sha256sum "$out/$id.webm" | cut -d' ' -f1)"
done
