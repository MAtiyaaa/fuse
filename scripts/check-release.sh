#!/usr/bin/env bash
# Checks that a release is consistent before it is merged: gradle.properties names the version, its
# notes exist with the title and the New, Changed and Fixed sections, RELEASE_NOTES.md is a copy of
# them, CHANGELOG.md lists the version, and the README's "Where Fuse stands" names it (the website
# reads the version from the release itself, but the README is written by hand and goes stale). The
# release workflow publishes exactly these files.
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { echo "::error::$1"; status=1; }
status=0

version="$(sed -n 's/^fuse\.version=//p' gradle.properties | tr -d '[:space:]')"
code="$(sed -n 's/^fuse\.versionCode=//p' gradle.properties | tr -d '[:space:]')"
name="$(sed -n 's/^fuse\.releaseName=//p' gradle.properties | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"

printf '%s' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || fail "fuse.version '$version' is not a version"
printf '%s' "$code" | grep -Eq '^[0-9]+$' || fail "fuse.versionCode '$code' is not a number"
[ -n "$name" ] || fail "fuse.releaseName is missing"

notes="docs/releases/$version.md"
if [ ! -f "$notes" ]; then
  fail "$notes is missing"
else
  first="$(head -n 1 "$notes")"
  [ "$first" = "# Fuse $version - $name" ] || fail "$notes starts with '$first', expected '# Fuse $version - $name'"
  for section in "## New" "## Changed" "## Fixed"; do
    grep -qx "$section" "$notes" || fail "$notes has no '$section' section"
  done
  cmp -s "$notes" RELEASE_NOTES.md || fail "RELEASE_NOTES.md differs from $notes"
fi
grep -q "^| $version | $name | \[docs/releases/$version.md\]" CHANGELOG.md || fail "CHANGELOG.md has no row for $version ($name)"

grep -q "^> \*\*Fuse $version \"$name\"" README.md || fail "README.md's \"Where Fuse stands\" doesn't name Fuse $version \"$name\""
grep -q "<sub>$version is the " README.md || fail "README.md's download line doesn't name $version"

exit "$status"
