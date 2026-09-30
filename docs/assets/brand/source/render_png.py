#!/usr/bin/env python3
"""Renders ../social-preview.png (1280 x 640) from ../social-preview.svg with headless Chromium.

Usage: python3 render_png.py [chromium executable]    (needs: pip install playwright; pyoxipng optional)

The grain in the artwork keeps the dark gradients from banding, so the PNG stays large (about 0.8 MB,
under GitHub's 1 MB limit for social previews); oxipng shrinks it losslessly when installed.
"""
import os
import sys

from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.normpath(os.path.join(HERE, '..', 'social-preview.svg'))
OUT = os.path.normpath(os.path.join(HERE, '..', 'social-preview.png'))

with sync_playwright() as p:
    exe = sys.argv[1] if len(sys.argv) > 1 else None
    browser = p.chromium.launch(**({'executable_path': exe} if exe else {}))
    page = browser.new_page(viewport={'width': 1280, 'height': 640}, device_scale_factor=1)
    page.goto('file://' + SRC)
    page.wait_for_timeout(300)
    page.screenshot(path=OUT, clip={'x': 0, 'y': 0, 'width': 1280, 'height': 640})
    browser.close()
try:
    import oxipng
    oxipng.optimize(OUT, OUT, level=6, strip=oxipng.StripChunks.safe())
except ImportError:
    pass
print(OUT, os.path.getsize(OUT), 'bytes')
