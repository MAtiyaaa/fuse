"""The horizontal lockup: the mark at 1.3 times the cap height (so its frame stroke matches the
wordmark's horizontal strokes), centred on the capitals, then the wordmark."""
import wordmark as W
from geom import svg_d
from mark import mark_body

ND = 2          # decimals in path data
_wm = None


def wordmark_d(scale, dx, dy):
    """Wordmark path data with the cap top at dy and the left edge at dx. Returns (d, width)."""
    global _wm
    if _wm is None:
        _wm = W.wordmark()
    wm, width = _wm
    return svg_d(wm, scale, dx, dy, flip_h=W.CAP, nd=ND), width * scale


def lockup_parts(cap, x, y, frame, text, uid, glow_a=1.0):
    """Mark and wordmark with the mark's box at (x, y). Returns (defs, body, width, height)."""
    ms = cap * 1.3
    defs, body = mark_body(ms, x, y, frame, uid, glow_a=glow_a)
    gap = cap * 0.46
    d, ww = wordmark_d(cap / 700, x + ms + gap, y + (ms - cap) / 2)
    body += f'<path d="{d}" fill="{text}"/>'
    return defs, body, ms + gap + ww, ms
