#!/usr/bin/env python3
"""Builds Fuse's brand assets in docs/assets/brand/ from code.

Usage (from this folder):
    pip install fonttools skia-pathops uharfbuzz
    python3 build.py [--optimize]         # writes ../*.svg; --optimize runs svgo on path data only
    pip install playwright && python3 render_png.py   # renders ../social-preview.png

Everything is vector: the wordmark is original lettering drawn in wordmark.py, and the taglines and
button labels are Manrope outlines (SIL OFL 1.1) converted to paths, so no SVG needs a font. Colours
are the Fuse (dark) and Daylight (light) presets in ui/designsystem (theme/ThemePresets.kt). Files named
-light are for light backgrounds, -dark for dark backgrounds; the rest read on both.
"""
import os
import shutil
import subprocess
import sys

import lockup
import text
import wordmark
from banner import banner, social
from geom import squircle_path
from mark import ACCENT, INK_TEXT, LIGHT_TEXT, f, mark_body

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, '..'))


def write(name, svg):
    with open(os.path.join(OUT, name), 'w') as fh:
        fh.write(svg + '\n')
    print(f'{name:28s} {len(svg) + 1:7d} bytes')


def svg(w, h, vb, body, defs='', title=None):
    label = f' role="img" aria-label="{title}"><title>{title}</title>' if title else '>'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{f(w)}" height="{f(h)}" viewBox="{vb}"{label}'
            + (f'<defs>{defs}</defs>' if defs else '') + body + '</svg>')


def marks():
    variants = (
        ('mark-light.svg', dict(frame=INK_TEXT, glow_a=.75)),
        ('mark-dark.svg', dict(frame=LIGHT_TEXT)),
        # one colour, for places that cannot switch with the theme
        ('mark.svg', dict(frame='#F0582C', glow_a=.7, fuse_grad=('#F0582C', '#FF8F57'))),
    )
    for name, kw in variants:
        defs, body = mark_body(100, 0, 0, uid='m', **kw)
        write(name, svg(256, 256, '0 0 100 100', body, defs, 'Fuse'))


def icon():
    """The mark on an ink tile, like the app's launcher icon. Reads on any background."""
    S, r = 100, 23.5
    defs = ('<linearGradient id="ibg" x1="0" y1="0" x2="0" y2="100" gradientUnits="userSpaceOnUse">'
            '<stop offset="0" stop-color="#191C24"/><stop offset="1" stop-color="#07080B"/></linearGradient>'
            '<radialGradient id="iroom" cx="70" cy="32" r="62" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="{ACCENT}" stop-opacity=".22"/><stop offset=".45" stop-color="{ACCENT}" stop-opacity=".07"/>'
            f'<stop offset="1" stop-color="{ACCENT}" stop-opacity="0"/></radialGradient>'
            '<linearGradient id="iedge" x1="0" y1="0" x2="0" y2="50" gradientUnits="userSpaceOnUse">'
            '<stop offset="0" stop-color="#fff" stop-opacity=".2"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient>')
    tile = squircle_path(0, 0, S, S, r)
    body = (f'<path d="{tile}" fill="url(#ibg)"/><path d="{tile}" fill="url(#iroom)"/>'
            f'<path d="{squircle_path(.25, .25, S - .5, S - .5, r - .25)}" fill="none" stroke="url(#iedge)" stroke-width=".5"/>')
    d, b = mark_body(60, 20, 20, LIGHT_TEXT, 'i')
    write('icon.svg', svg(256, 256, '0 0 100 100', body + b, defs + d, 'Fuse'))


def wordmarks():
    cap = 100
    d, w = lockup.wordmark_d(cap / 700, 0, 0)
    h = cap + wordmark.OS * cap / 700            # room for the round letters' overshoot
    for name, colour in (('wordmark-light.svg', INK_TEXT), ('wordmark-dark.svg', LIGHT_TEXT)):
        write(name, svg(w * 2, h * 2, f'0 0 {f(w)} {f(h)}', f'<path d="{d}" fill="{colour}"/>', title='Fuse'))


def lockups():
    for name, colour, glow in (('lockup-light.svg', INK_TEXT, .75), ('lockup-dark.svg', LIGHT_TEXT, 1.0)):
        defs, body, w, h = lockup.lockup_parts(70, 0, 0, colour, colour, 'l', glow_a=glow)
        write(name, svg(480, h * 480 / w, f'0 0 {f(w)} {f(h)}', body, defs, 'Fuse'))


def dividers():
    """A hairline that burns toward a spark in the middle."""
    W, H = 960, 36
    cx, cy = W / 2, H / 2
    R, G = 4.4, 17                 # spark core and glow radii
    for name, line, a, glow_a in (('divider-light.svg', INK_TEXT, .2, .6), ('divider-dark.svg', LIGHT_TEXT, .2, .95)):
        defs = (f'<linearGradient id="dl" x1="0" y1="0" x2="{f(cx - R - 4)}" y2="0" gradientUnits="userSpaceOnUse">'
                f'<stop offset="0" stop-color="{line}" stop-opacity="0"/><stop offset=".5" stop-color="{line}" stop-opacity="{a}"/>'
                f'<stop offset=".82" stop-color="{ACCENT}" stop-opacity=".5"/><stop offset="1" stop-color="{ACCENT}"/></linearGradient>'
                f'<linearGradient id="dr" x1="{cx + G}" y1="0" x2="{W}" y2="0" gradientUnits="userSpaceOnUse">'
                f'<stop offset="0" stop-color="{line}" stop-opacity="{a}"/><stop offset="1" stop-color="{line}" stop-opacity="0"/></linearGradient>'
                f'<radialGradient id="dg" cx="{f(cx)}" cy="{f(cy)}" r="{G}" gradientUnits="userSpaceOnUse">'
                f'<stop offset="0" stop-color="{ACCENT}" stop-opacity="{glow_a}"/><stop offset=".3" stop-color="{ACCENT}" stop-opacity="{f(glow_a * .5)}"/>'
                f'<stop offset=".65" stop-color="{ACCENT}" stop-opacity="{f(glow_a * .14)}"/><stop offset="1" stop-color="{ACCENT}" stop-opacity="0"/></radialGradient>'
                f'<radialGradient id="dc" cx="{f(cx)}" cy="{f(cy)}" r="{R}" gradientUnits="userSpaceOnUse">'
                f'<stop offset="0" stop-color="#FFF4EA"/><stop offset=".45" stop-color="#FFB085"/><stop offset=".85" stop-color="{ACCENT}"/></radialGradient>')
        body = (f'<rect x="0" y="{f(cy - 1)}" width="{f(cx)}" height="2" rx="1" fill="url(#dl)"/>'
                f'<rect x="{f(cx + G)}" y="{f(cy - .75)}" width="{f(W - cx - G)}" height="1.5" rx=".75" fill="url(#dr)"/>'
                f'<circle cx="{f(cx)}" cy="{f(cy)}" r="{G}" fill="url(#dg)"/><circle cx="{f(cx)}" cy="{f(cy)}" r="{R}" fill="url(#dc)"/>')
        write(name, svg(W, H, f'0 0 {W} {H}', body, defs))


def spark():
    """The spark on its own, for small accents. Reads on both backgrounds."""
    defs = ('<radialGradient id="sg" cx="32" cy="32" r="32" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="{ACCENT}" stop-opacity=".9"/><stop offset=".3" stop-color="{ACCENT}" stop-opacity=".45"/>'
            f'<stop offset=".6" stop-color="{ACCENT}" stop-opacity=".13"/><stop offset="1" stop-color="{ACCENT}" stop-opacity="0"/></radialGradient>'
            '<radialGradient id="sc" cx="32" cy="32" r="10" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="#FFF4EA"/><stop offset=".42" stop-color="#FFB085"/><stop offset=".8" stop-color="{ACCENT}"/></radialGradient>')
    body = '<circle cx="32" cy="32" r="32" fill="url(#sg)"/><circle cx="32" cy="32" r="10" fill="url(#sc)"/>'
    write('spark.svg', svg(64, 64, '0 0 64 64', body, defs))


def buttons():
    """Pill buttons for the README's download links, in the primary and secondary button styles."""
    H, size = 48, 16
    styles = {  # fill, edge colour, edge opacity, label colour
        'primary-dark': (ACCENT, '#FFFFFF', .28, '#1C0A04'),
        'primary-light': ('#E9522B', '#FFFFFF', .30, '#FFFFFF'),
        'secondary-dark': ('#1A1D25', '#FFFFFF', .12, LIGHT_TEXT),
        'secondary-light': ('#FFFFFF', INK_TEXT, .14, INK_TEXT),
    }
    for key, label, style in (('android', 'Download for Android', 'primary'), ('linux', 'Download for Linux', 'secondary')):
        for theme in ('dark', 'light'):
            fill, edge, ea, fg = styles[f'{style}-{theme}']
            pad, icon_w, gap = 22, 18, 10
            W = pad + icon_w + gap + text.text_width('manrope_semibold', label, size, 0.005) + pad
            baseline = H / 2 + text.cap_height('manrope_semibold', size) / 2
            d, _ = text.text_path('manrope_semibold', label, size, pad + icon_w + gap, baseline, 0.005)
            ix, iy = pad, H / 2 - 9
            glyph = (f'<path d="M{f(ix + 9)} {f(iy + 1.5)}V{f(iy + 11.5)}M{f(ix + 4.5)} {f(iy + 7.5)}L{f(ix + 9)} {f(iy + 12)}'
                     f'L{f(ix + 13.5)} {f(iy + 7.5)}M{f(ix + 2)} {f(iy + 12.5)}V{f(iy + 14)}A2.5 2.5 0 0 0 {f(ix + 4.5)} {f(iy + 16.5)}'
                     f'H{f(ix + 13.5)}A2.5 2.5 0 0 0 {f(ix + 16)} {f(iy + 14)}V{f(iy + 12.5)}" fill="none" stroke="{fg}" '
                     f'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>')
            mid, end = (.25, .1) if style == 'primary' else (.7, .9)
            defs = (f'<linearGradient id="be" x1="0" y1="0" x2="0" y2="{H}" gradientUnits="userSpaceOnUse">'
                    f'<stop offset="0" stop-color="{edge}" stop-opacity="{ea}"/><stop offset=".55" stop-color="{edge}" stop-opacity="{f(ea * mid)}"/>'
                    f'<stop offset="1" stop-color="{edge}" stop-opacity="{f(ea * end)}"/></linearGradient>')
            body = (f'<rect x="0" y="0" width="{f(W)}" height="{H}" rx="{f(H / 2)}" fill="{fill}"/>'
                    f'<rect x=".5" y=".5" width="{f(W - 1)}" height="{H - 1}" rx="{f(H / 2 - .5)}" fill="none" stroke="url(#be)"/>'
                    + glyph + f'<path d="{d}" fill="{fg}"/>')
            write(f'button-{key}-{theme}.svg', svg(W, H, f'0 0 {f(W)} {H}', body, defs, label))


def scenes():
    text.PRECISION = lockup.ND = 1       # a tenth of a pixel is plenty at banner scale
    write('banner-dark.svg', banner('dark'))
    write('banner-light.svg', banner('light'))
    write('social-preview.svg', social())
    text.PRECISION = lockup.ND = 2


def optimize():
    """svgo with path data conversion only (see svgo.config.cjs): ids, gradients and filters are untouched."""
    if not shutil.which('npx'):
        sys.exit('npx not found; skipped --optimize')
    subprocess.run(['npx', '--yes', 'svgo@3', '--config', os.path.join(HERE, 'svgo.config.cjs'), '-f', OUT, '-o', OUT],
                   check=True)


if __name__ == '__main__':
    marks()
    icon()
    wordmarks()
    lockups()
    dividers()
    spark()
    buttons()
    scenes()
    if '--optimize' in sys.argv:
        optimize()
