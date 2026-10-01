#!/usr/bin/env python3
"""Builds Fuse's website (site/) into a folder GitHub Pages can serve.

    python3 site/build.py --out _site [--release release.json]

It copies the page and its scripts, the brand art, feature icons, screenshots and fonts from the
repository (so nothing is kept twice), the theme files from docs/themes with an index of them, and
the latest release, if given, as release.json. The version and release name are written into the
page too, so it reads right before its script runs (or without it). Standard library only.
"""

import argparse
import html
import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
REPO = "MAtiyaaa/fuse"


def gradle_props():
    props = {}
    for line in (ROOT / "gradle.properties").read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def copy(src: Path, dst: Path):
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)


def theme_entry(path: Path, rel: str, built_in: bool):
    doc = json.loads(path.read_text())
    colors = doc.get("colors", {})
    bg = doc.get("background", "hero")
    style = bg if isinstance(bg, str) else bg.get("style", "hero")
    secondary = None if isinstance(bg, str) else bg.get("secondary")
    return {
        "name": doc["name"],
        "author": doc.get("author"),
        "tagline": doc.get("tagline", ""),
        "builtIn": built_in,
        "path": rel,
        "style": style,
        "secondary": secondary,
        "corners": doc.get("corners", "soft"),
        "colors": colors,
    }


def themes(out: Path):
    """Every theme file, and an index the page draws its cards from. Built-in themes first."""
    src = ROOT / "docs" / "themes"
    index = []
    presets = sorted((src / "presets").glob("*.json"))
    order = [
        "fuse", "glass", "pitch", "starlight", "orbital", "crossbar", "wave", "blossom", "lagoon", "canopy", "blades",
        "sundown", "crt", "daylight", "channels", "opal", "noon", "ridge", "olive",
    ]
    presets.sort(key=lambda p: order.index(p.stem) if p.stem in order else len(order))
    for p in presets:
        copy(p, out / "themes" / "presets" / p.name)
        index.append(theme_entry(p, f"themes/presets/{p.name}", built_in=True))
    for p in sorted(src.glob("*.json")):
        copy(p, out / "themes" / p.name)
        index.append(theme_entry(p, f"themes/{p.name}", built_in=False))
    # Fill colours a file left to its base, so every card can be drawn.
    base = {e["path"].split("/")[-1][:-5]: e["colors"] for e in index if e["builtIn"]}
    for e in index:
        if not e["builtIn"]:
            doc = json.loads((out / e["path"]).read_text())
            start = base.get(doc.get("extends", "fuse"), base.get("fuse", {}))
            e["colors"] = {**start, **e["colors"]}
    (out / "themes" / "index.json").write_text(json.dumps(index, indent=1) + "\n")


def release_file(path, out: Path):
    """The parts of the latest release the page reads, or None."""
    if not path:
        return None
    try:
        rel = json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return None
    if not isinstance(rel, dict) or not rel.get("tag_name"):
        return None
    slim = {
        "tag_name": rel["tag_name"],
        "name": rel.get("name", ""),
        "html_url": rel.get("html_url", f"https://github.com/{REPO}/releases/latest"),
        "published_at": rel.get("published_at"),
        "body": rel.get("body", ""),
        "assets": [
            {"name": a["name"], "browser_download_url": a["browser_download_url"], "size": a.get("size", 0)}
            for a in rel.get("assets", [])
        ],
    }
    (out / "release.json").write_text(json.dumps(slim, indent=1) + "\n")
    return slim


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="_site")
    ap.add_argument("--release", help="The latest release, as GitHub's API returns it")
    args = ap.parse_args()
    out = Path(args.out).resolve()
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    for name in ["site.css", "site.js"]:
        copy(SITE / "assets" / name, out / "assets" / name)
    brand = ROOT / "docs" / "assets" / "brand"
    for name in ["icon.svg", "wordmark-dark.svg", "social-preview.png"]:
        copy(brand / name, out / "assets" / "brand" / name)
    for p in (ROOT / "docs" / "assets" / "icons").glob("*.svg"):
        copy(p, out / "assets" / "icons" / p.name)
    for p in (ROOT / "docs" / "assets" / "screenshots").glob("*.webp"):
        copy(p, out / "assets" / "screenshots" / p.name)
    for p in (ROOT / "ui" / "designsystem" / "src" / "commonMain" / "composeResources" / "font").glob("*.ttf"):
        copy(p, out / "assets" / "fonts" / p.name)
    themes(out)

    props = gradle_props()
    rel = release_file(args.release, out)
    version = rel["tag_name"].lstrip("v") if rel else props.get("fuse.version", "")
    name = rel["name"].split(" - ", 1)[1] if rel and " - " in rel.get("name", "") else props.get("fuse.releaseName", "")
    url = rel["html_url"] if rel else f"https://github.com/{REPO}/releases/latest"

    page = (SITE / "index.html").read_text()
    page = re.sub(
        r"\{\{GLYPH:(\w+)\}\}",
        lambda m: (SITE / "assets" / "platforms" / f"{m.group(1)}.svg").read_text().strip(),
        page,
    )
    page = page.replace("{{VERSION}}", html.escape(version)).replace("{{RELEASE_NAME}}", html.escape(name)).replace("{{RELEASE_URL}}", html.escape(url))
    left = re.findall(r"\{\{[A-Z_:a-z]+\}\}", page)
    if left:
        raise SystemExit(f"Unfilled placeholders: {left}")
    (out / "index.html").write_text(page)
    copy(SITE / "404.html", out / "404.html")
    copy(SITE / "manifest.webmanifest", out / "manifest.webmanifest")
    (out / ".nojekyll").write_text("")
    print(f"Built Fuse {version} ({name}) into {out}")


if __name__ == "__main__":
    main()
