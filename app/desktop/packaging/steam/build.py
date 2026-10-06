"""
Renders the art "Add Fuse to Steam" gives Steam (app/desktop/src/main/resources/steam/) from the brand
SVGs in docs/assets/brand: the banner's scene without its words, the lockup and the icon, laid out
by the HTML pages beside this file, drawn by headless Chromium.

    python3 app/desktop/packaging/steam/build.py [path/to/chrome]

portrait.png 600x900 (library capsule), capsule.png 920x430 (wide capsule), hero.png 1920x620,
logo.png (drawn over the hero) and icon.png 256x256.
"""
import os, re, shutil, sys, tempfile
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "../../../.."))
BRAND = os.path.join(ROOT, "docs/assets/brand")
OUT = os.path.join(ROOT, "app/desktop/src/main/resources/steam")
sys.path.insert(0, HERE)
import render

if len(sys.argv) > 1:
    render.CH = sys.argv[1]

work = tempfile.mkdtemp(prefix="fuse-steam-art-")
for f in ["lockup-dark.svg", "icon.svg"]:
    shutil.copy(os.path.join(BRAND, f), work)
for f in os.listdir(HERE):
    if f.endswith(".html"):
        shutil.copy(os.path.join(HERE, f), work)
# The banner's scene, full bleed, without its logo, words and outline.
banner = open(os.path.join(BRAND, "banner-dark.svg")).read()
scene = banner[: banner.index('<path d="M124.61')] + "</g></svg>\n"
scene = re.sub(r"<title>.*?</title>", "", scene.replace('clip-path="url(#bdc37)"', "", 1))
open(os.path.join(work, "scene.svg"), "w").write(scene)

os.makedirs(OUT, exist_ok=True)
for name, w, h, clear in [("portrait", 600, 900, False), ("capsule", 920, 430, False), ("hero", 1920, 620, False), ("logo", 1200, 344, True), ("icon", 256, 256, True)]:
    out = os.path.join(work, name + ".png")
    render.render(os.path.join(work, name + ".html"), w, h, out, clear)
    Image.open(out).convert("RGBA" if clear else "RGB").save(os.path.join(OUT, name + ".png"), optimize=True)
    print("wrote", os.path.join(OUT, name + ".png"))
shutil.rmtree(work)
