#!/usr/bin/env python3
"""The snapshot of Open Ports Games' catalogue that Fuse carries, for its Android ports.

snapshot: reads the catalogue (openportsgames.com/api/ports.json, schema "openportsgames/catalog",
          its data under CC BY 4.0) from a URL or a file, and writes the ports it lists for Android
          in the same form, with only the fields Fuse reads, to ports/android/openports.json. Left
          out, as Fuse leaves them out of the live catalogue too: tools (launchers), entries whose
          source is gone, and any with a warning (one that ships the game's own files).

Only the Python standard library is used.
"""

import argparse
import json
import re
import sys
import urllib.request

FEED_URL = "https://openportsgames.com/api/ports.json"
SCHEMA = "openportsgames/catalog"
VERSION = 2
FIELDS = ("id", "kind", "title", "game", "portType", "status", "verified", "notes", "originalSystems")


def read_feed(source):
    """The catalogue at source: an https:// address, or a file's path."""
    if source.startswith("https://"):
        req = urllib.request.Request(source, headers={"User-Agent": "Fuse ports snapshot"})
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read().decode("utf-8"))
    if "://" in source:
        raise ValueError("the catalogue is read over HTTPS or from a file")
    with open(source, encoding="utf-8") as f:
        return json.load(f)


def keep(port):
    """True for a port Fuse lists for Android."""
    if "android" not in port.get("platforms", []) or port.get("kind") != "game":
        return False
    if port.get("sourceDead") is True or (port.get("warning") or "").strip():
        return False
    return any(s.startswith("https://") for s in port.get("sources", []))


def plain(text):
    """A listing's text as Fuse shows text: a dash between words a comma, one between numbers a hyphen."""
    text = re.sub(r"\s+[\u2014\u2013]\s+", ", ", text)
    text = re.sub(r"(?<=\d)[\u2014\u2013](?=\d)", "-", text)
    text = re.sub(r"[\u2014\u2013]", ", ", text)
    return re.sub(r"[\u200b-\u200d\u2060\ufeff]", "", text).strip()


def trimmed(port):
    out = {k: port[k] for k in FIELDS if k in port}
    for k in ("title", "game", "notes"):
        if isinstance(out.get(k), str):
            out[k] = plain(out[k])
    out["platforms"] = ["android"]
    out["sources"] = [next(s for s in port["sources"] if s.startswith("https://"))]
    spdx = (port.get("license") or {}).get("spdx")
    if spdx:
        out["license"] = {"spdx": spdx}
    return out


def snapshot(feed):
    """The snapshot of feed: its Android ports, trimmed, in the catalogue's own form."""
    if feed.get("schema") != SCHEMA:
        raise ValueError("this isn't Open Ports Games' catalogue")
    if int(feed.get("version", 0)) > VERSION:
        raise ValueError("the catalogue is in a newer form than this script reads")
    ports = sorted((trimmed(p) for p in feed.get("ports", []) if keep(p)), key=lambda p: p["id"])
    return {
        "schema": SCHEMA,
        "version": feed.get("version"),
        "generatedAt": feed.get("generatedAt"),
        "site": feed.get("site"),
        "licence": "CC BY 4.0",
        "ports": ports,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    snap = sub.add_parser("snapshot", help="write the Android snapshot")
    snap.add_argument("--feed", default=FEED_URL, help="the catalogue's address or file")
    snap.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    out = snapshot(read_feed(args.feed))
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(json.dumps(out, ensure_ascii=False, indent=1))
        f.write("\n")
    print(f"{len(out['ports'])} Android ports from the catalogue of {out['generatedAt']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
