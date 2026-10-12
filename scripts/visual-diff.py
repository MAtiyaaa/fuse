#!/usr/bin/env python3
"""
Visual regression for the screen audits: compares the screenshots of two audit runs (a known-good
one and a new one, both from `:ui:shell:desktopAudit -Pfuse.audit.dir=...`) pixel by pixel, and
writes a report of every screen that changed, with a picture of what changed beside it.

    python3 scripts/visual-diff.py <known-good dir> <new dir> <report dir> [--tolerance 0.002]

A screen counts as changed when more than the tolerance (a share of its pixels) differs by more
than a small amount in any channel, so antialiasing noise is not a change. Screens only in one run
are listed as added or removed. The exit status is 1 when anything changed, so a script or CI step
can ask for the change to be looked at before a release; it changes nothing in either run.
"""
import argparse
import html
import os
import sys

from PIL import Image, ImageChops

# A channel must differ by more than this (of 255) for the pixel to count as changed.
CHANNEL_NOISE = 12


def pngs(root):
    out = {}
    for dirpath, _, files in os.walk(root):
        for name in files:
            if name.lower().endswith(".png"):
                path = os.path.join(dirpath, name)
                out[os.path.relpath(path, root)] = path
    return out


def changed_share(a, b):
    """The share of pixels that differ, and an image marking them (or None when sizes differ)."""
    if a.size != b.size:
        return 1.0, None
    diff = ImageChops.difference(a.convert("RGB"), b.convert("RGB"))
    # A pixel changed when any of its channels did.
    r, g, bl = [c.point(lambda v: 255 if v > CHANNEL_NOISE else 0) for c in diff.split()]
    mask = ImageChops.lighter(ImageChops.lighter(r, g), bl)
    count = sum(mask.histogram()[1:])
    total = a.size[0] * a.size[1]
    marked = b.convert("RGB").copy()
    overlay = Image.new("RGB", b.size, (255, 0, 120))
    marked = Image.composite(overlay, marked, mask)
    return count / total if total else 0.0, marked


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("known_good")
    ap.add_argument("new")
    ap.add_argument("report")
    ap.add_argument("--tolerance", type=float, default=0.002, help="share of pixels allowed to differ (default 0.002)")
    args = ap.parse_args()

    old, new = pngs(args.known_good), pngs(args.new)
    os.makedirs(args.report, exist_ok=True)
    rows, changed = [], 0
    for key in sorted(set(old) | set(new)):
        if key not in old:
            rows.append((key, "added", None))
            changed += 1
            continue
        if key not in new:
            rows.append((key, "removed", None))
            changed += 1
            continue
        share, marked = changed_share(Image.open(old[key]), Image.open(new[key]))
        if share <= args.tolerance:
            continue
        changed += 1
        out = None
        if marked is not None:
            out = os.path.join(args.report, key.replace(os.sep, "__"))
            marked.save(out)
        rows.append((key, "size changed" if marked is None else f"{share * 100:.2f}% of pixels", out))

    with open(os.path.join(args.report, "index.html"), "w") as f:
        f.write("<!doctype html><meta charset=utf-8><title>Visual changes</title>")
        f.write("<style>body{font:14px system-ui;background:#111;color:#eee;margin:24px}img{max-width:48%;margin:4px;border:1px solid #333}"
                "h2{font-size:15px;margin-top:28px}</style>")
        f.write(f"<h1>{changed} screen(s) changed</h1>")
        for key, what, marked in rows:
            f.write(f"<h2>{html.escape(key)}: {html.escape(what)}</h2>")
            if key in old:
                f.write(f"<img alt='before' src='file://{html.escape(os.path.abspath(old[key]))}'>")
            if marked:
                f.write(f"<img alt='after, changes marked' src='{html.escape(os.path.basename(marked))}'>")
            elif key in new:
                f.write(f"<img alt='after' src='file://{html.escape(os.path.abspath(new[key]))}'>")
    for key, what, _ in rows:
        print(f"{what:>20}  {key}")
    print(f"{changed} screen(s) changed; report in {args.report}/index.html")
    return 1 if changed else 0


if __name__ == "__main__":
    sys.exit(main())
