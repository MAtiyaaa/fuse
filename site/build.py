#!/usr/bin/env python3
"""Builds Fuse's website (site/) into a folder GitHub Pages can serve.

    python3 site/build.py --out _site [--release release.json] [--releases releases.json]

It copies the page and its scripts, the brand art, feature icons, screenshots and fonts from the
repository (so nothing is kept twice), the theme files from docs/themes with an index of them, and
every release's notes from docs/releases as releases.json for the update viewer, and the latest
release, if given, as release.json. The version and release name are written into the
page too, so it reads right before its script runs (or without it). Standard library only.
"""

import argparse
import html
import json
import re
import shutil
import subprocess
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


def inline(text: str) -> str:
    """One line of a release note as safe HTML: bold, code and web links, everything else escaped."""
    out = html.escape(text, quote=True)
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    out = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", out)
    out = re.sub(r"\[([^\]]+)\]\((https?://[^)\s]+)\)", r'<a href="\2" rel="noopener">\1</a>', out)
    return out


def release_notes(path: Path):
    """A release's notes (docs/releases/<version>.md) as the update viewer reads them."""
    lines = path.read_text(encoding="utf-8").splitlines()
    m = re.match(r"# Fuse (\S+) - (.+)", lines[0] if lines else "")
    if not m:
        return None
    intro, sections = [], []
    para, item = [], None

    def close_para(into):
        if para:
            into.append(inline(" ".join(para)))
            para.clear()

    for line in lines[1:] + [""]:
        if line.startswith("## "):
            close_para(intro if not sections else sections[-1]["lead"])
            item = None
            sections.append({"title": line[3:].strip(), "lead": [], "items": []})
        elif re.match(r"^- ", line) and sections:
            close_para(sections[-1]["lead"])
            text = line[2:].strip()
            t = re.match(r"^\*\*(.+?)\*\*([.:]?)\s*(.*)$", text)
            rest = t.group(3) if t else text
            # "**System health** in Settings: ..." runs on from its title: the rest starts a sentence.
            if t and not t.group(2) and not t.group(1).endswith((".", ":")) and rest[:1].islower():
                rest = rest[:1].upper() + rest[1:]
            item = {"title": inline(t.group(1).rstrip(".:")) if t else None, "text": rest, "sub": []}
            sections[-1]["items"].append(item)
        elif re.match(r"^ {2,}- ", line) and item is not None:
            item["sub"].append(line.strip()[2:])
        elif re.match(r"^ {2,}\S", line) and item is not None:
            if item["sub"]:
                item["sub"][-1] += " " + line.strip()
            else:
                item["text"] += " " + line.strip()
        elif line.strip() == "":
            close_para(intro if not sections else sections[-1]["lead"])
            item = None
        else:
            item = None
            para.append(line.strip())
    for sec in sections:
        for it in sec["items"]:
            it["text"] = inline(it["text"])
            it["sub"] = [inline(x) for x in it["sub"]]
    return {"version": m.group(1), "name": m.group(2).strip(), "intro": intro, "sections": sections}


def released_on(path: Path):
    """The day a release's notes first went in, from git, or None without its whole history."""
    try:
        shallow = subprocess.run(["git", "rev-parse", "--is-shallow-repository"], cwd=ROOT, capture_output=True, text=True, timeout=30).stdout.strip()
        if shallow != "false":
            return None
        out = subprocess.run(
            ["git", "log", "--diff-filter=A", "--follow", "--format=%cs", "--", str(path.relative_to(ROOT))],
            cwd=ROOT, capture_output=True, text=True, check=True, timeout=30,
        ).stdout.split()
        return out[-1] if out else None
    except (OSError, subprocess.SubprocessError, ValueError):
        return None


def published(path):
    """Each tag's publishing day from GitHub's list of releases, if given."""
    if not path:
        return {}
    try:
        data = json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return {}
    # `gh api --paginate` writes one array per page, one after another.
    if isinstance(data, list) and data and isinstance(data[0], list):
        data = [r for page in data for r in page]
    return {r["tag_name"].lstrip("v"): (r.get("published_at") or "")[:10] or None for r in data if isinstance(r, dict) and r.get("tag_name")}


def releases(out: Path, every=None):
    """Every release's notes, newest first, for the update viewer."""
    key = lambda p: tuple(int(x) for x in re.findall(r"\d+", p.stem))
    days = published(every)
    found = []
    for p in sorted((ROOT / "docs" / "releases").glob("*.md"), key=key, reverse=True):
        notes = release_notes(p)
        if notes:
            notes["date"] = days.get(notes["version"]) or released_on(p)
            found.append(notes)
    (out / "releases.json").write_text(json.dumps(found, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    return found


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
    ap.add_argument("--releases", help="Every release, as GitHub's API lists them (for their dates)")
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
    notes = releases(out, args.releases)

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
    print(f"Built Fuse {version} ({name}) with {len(notes)} releases' notes into {out}")


if __name__ == "__main__":
    main()
