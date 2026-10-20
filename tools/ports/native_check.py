#!/usr/bin/env python3
"""The nightly check of the PlayStation games' native ports Fuse finds (ports/native.json).

Fuse doesn't install these: the person does, from each project's releases, and Fuse finds them by
the files their release puts in their folder. Each night this reads every project's newest release
from GitHub's API and checks that each build is still there by the name it had, and that a zip
still holds the files Fuse finds the port by. Only a zip's own listing is read (its last bytes, by
HTTP ranges), never the whole download. The results go to a JSON file, one entry per port.

Only the Python standard library is used.
"""

import argparse
import io
import json
import os
import re
import sys
import urllib.request
import zipfile

API = "https://api.github.com/repos/{repo}/releases/latest"
TIMEOUT = 60


def request(url, headers=None):
    h = {"User-Agent": "Fuse ports check"}
    token = os.environ.get("GITHUB_TOKEN")
    if token and url.startswith("https://api.github.com/"):
        h["Authorization"] = f"Bearer {token}"
    h.update(headers or {})
    return urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=TIMEOUT)


class RangeFile(io.RawIOBase):
    """A remote file read by HTTP ranges, enough for zipfile to read the listing at its end."""

    def __init__(self, url, size, fetch=None):
        self.url, self.size, self.pos = url, size, 0
        self.fetch = fetch or (lambda start, end: request(self.url, {"Range": f"bytes={start}-{end}"}).read())

    def seekable(self):
        return True

    def readable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, offset, whence=io.SEEK_SET):
        base = {io.SEEK_SET: 0, io.SEEK_CUR: self.pos, io.SEEK_END: self.size}[whence]
        self.pos = max(0, base + offset)
        return self.pos

    def readinto(self, b):
        if self.pos >= self.size or len(b) == 0:
            return 0
        end = min(self.size, self.pos + len(b)) - 1
        data = self.fetch(self.pos, end)
        b[: len(data)] = data
        self.pos += len(data)
        return len(data)


def has_files(names, wanted):
    """Whether one folder of the zip (its top, or a folder in it) holds every one of [wanted]."""
    folders = {}
    for n in names:
        # Some zips are made on Windows with its separator in their names.
        n = n.replace("\\", "/")
        if n.endswith("/"):
            continue
        folder, _, base = n.rpartition("/")
        folders.setdefault(folder, set()).add(base)
    return any(all(w in files for w in wanted) for files in folders.values())


def pick(assets, pattern):
    """The release's file whose name matches [pattern], the first if several do."""
    rx = re.compile(pattern)
    return next((a for a in assets if rx.search(a.get("name", ""))), None)


def check_port(port, latest=None, listing=None):
    """One port's newest release against what Fuse expects: (passed, detail)."""
    latest = latest or (lambda repo: json.load(request(API.format(repo=repo))))
    listing = listing or (lambda asset: zipfile.ZipFile(io.BufferedReader(RangeFile(asset["browser_download_url"], asset["size"]), buffer_size=1 << 16)).namelist())
    try:
        release = latest(port["repo"])
    except Exception as e:  # noqa: BLE001 (any failure to ask is a failure of the night)
        return False, f"GitHub didn't answer for {port['repo']}: {e}"
    tag = release.get("tag_name", "?")
    assets = release.get("assets", [])
    seen = []
    for build in port["builds"]:
        asset = pick(assets, build["asset"])
        if asset is None:
            return False, f"{tag}: no {build['os']} file matching {build['asset']}"
        wanted = build.get("has", [])
        if wanted:
            try:
                names = listing(asset)
            except Exception as e:  # noqa: BLE001
                return False, f"{tag}: {asset['name']} couldn't be read: {e}"
            if not has_files(names, wanted):
                return False, f"{tag}: {asset['name']} no longer holds {', '.join(wanted)}"
        seen.append(asset["name"])
    return True, f"{tag}: {', '.join(seen)}"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--list", default="ports/native.json")
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    with open(args.list, encoding="utf-8") as f:
        data = json.load(f)
    results = {}
    for port in data["ports"]:
        passed, detail = check_port(port)
        results[port["id"]] = {"passed": passed, "detail": detail}
        print(f"{'ok  ' if passed else 'FAIL'} {port['id']}: {detail}")
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, sort_keys=True)
    return 0 if all(r["passed"] for r in results.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
