"""The Fuse mark: a rounded frame, a fuse curving into it and a glowing spark at its end.

Geometry follows FuseMark in ui/shell (app/Hud.kt), in fractions of the mark's width: frame stroke
0.1 with corner 0.28, fuse from (0.28, 0.7) to (0.64, 0.34), spark of radius 0.07 at (0.7, 0.3) with a
glow of radius 0.22. Refinements for print and large sizes: the frame uses Fuse's continuous
(squircle) corner, the glow falls off smoothly instead of linearly, the spark has a white-hot core,
and the fuse runs on into the spark's centre so its round cap never shows beside the spark.
"""
from geom import squircle_path

ACCENT = '#FF6A3D'      # Fuse theme accent
INK_TEXT = '#15171C'    # Daylight theme text: the mark on light backgrounds
LIGHT_TEXT = '#F3F4F6'  # Fuse theme text: the mark on dark backgrounds


def f(v):
    s = '%.2f' % v
    s = s.rstrip('0').rstrip('.') if '.' in s else s
    return '0' if s == '-0' else s


def mark_body(size, x, y, frame, uid, spark=ACCENT, glow_a=1.0, fuse_grad=None):
    """Returns (defs, body) for a mark of `size` with its top-left at (x, y)."""
    w = size
    s = w * 0.1
    X = lambda v: f(x + w * v)
    Y = lambda v: f(y + w * v)
    cx, cy = x + w * 0.7, y + w * 0.3
    defs = ''
    fuse_paint = frame
    if fuse_grad:
        defs += (f'<linearGradient id="{uid}f" x1="{X(.28)}" y1="{Y(.7)}" x2="{X(.66)}" y2="{Y(.33)}" gradientUnits="userSpaceOnUse">'
                 f'<stop offset="0" stop-color="{fuse_grad[0]}"/><stop offset="1" stop-color="{fuse_grad[1]}"/></linearGradient>')
        fuse_paint = f'url(#{uid}f)'
    defs += (f'<radialGradient id="{uid}g" cx="{f(cx)}" cy="{f(cy)}" r="{f(w * .22)}" gradientUnits="userSpaceOnUse">'
             f'<stop offset="0" stop-color="{spark}" stop-opacity="{f(glow_a)}"/>'
             f'<stop offset=".3" stop-color="{spark}" stop-opacity="{f(glow_a * .55)}"/>'
             f'<stop offset=".6" stop-color="{spark}" stop-opacity="{f(glow_a * .18)}"/>'
             f'<stop offset="1" stop-color="{spark}" stop-opacity="0"/></radialGradient>'
             f'<radialGradient id="{uid}c" cx="{f(cx)}" cy="{f(cy)}" r="{f(w * .07)}" gradientUnits="userSpaceOnUse">'
             f'<stop offset="0" stop-color="#FFF4EA"/><stop offset=".42" stop-color="#FFB085"/><stop offset=".78" stop-color="{spark}"/></radialGradient>')
    body = (f'<path d="{squircle_path(x + s / 2, y + s / 2, w - s, w - s, w * 0.28)}" fill="none" stroke="{frame}" stroke-width="{f(s)}"/>'
            f'<path d="M{X(.28)} {Y(.7)}C{X(.42)} {Y(.7)} {X(.44)} {Y(.34)} {X(.64)} {Y(.34)}'
            f'C{X(.672)} {Y(.34)} {X(.692)} {Y(.325)} {X(.7)} {Y(.3)}" '
            f'fill="none" stroke="{fuse_paint}" stroke-width="{f(s)}" stroke-linecap="round"/>'
            f'<circle cx="{f(cx)}" cy="{f(cy)}" r="{f(w * .22)}" fill="url(#{uid}g)"/>'
            f'<circle cx="{f(cx)}" cy="{f(cy)}" r="{f(w * .07)}" fill="url(#{uid}c)"/>')
    return defs, body
