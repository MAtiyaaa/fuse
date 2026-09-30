"""Shapes text with HarfBuzz and turns it into outline paths, so no SVG depends on an installed font.
Fonts are Fuse's own (Sora and Manrope, SIL Open Font License 1.1)."""
import os

import uharfbuzz as hb
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

FONT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', '..',
                        'ui', 'designsystem', 'src', 'commonMain', 'composeResources', 'font')
PRECISION = 2   # decimals in path data
_cache = {}


def _font(name):
    if name not in _cache:
        path = os.path.join(FONT_DIR, name + '.ttf')
        tt = TTFont(path)
        font = hb.Font(hb.Face(hb.Blob.from_file_path(path)))
        _cache[name] = (font, tt, tt.getGlyphSet(), tt['head'].unitsPerEm)
    return _cache[name]


def _fmt(v):
    s = ('%.' + str(PRECISION) + 'f') % v
    s = s.rstrip('0').rstrip('.') if '.' in s else s
    return '0' if s == '-0' else s


def _layout(name, text, size, tracking):
    font, tt, _, upem = _font(name)
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(font, buf, {'kern': True, 'liga': True})
    order = tt.getGlyphOrder()
    scale = size / upem
    glyphs, x = [], 0.0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        glyphs.append((order[info.codepoint], x + pos.x_offset * scale, pos.y_offset * scale))
        x += pos.x_advance * scale + tracking * size
    return glyphs, x - tracking * size, scale


def text_width(name, text, size, tracking=0.0):
    return _layout(name, text, size, tracking)[1]


def text_path(name, text, size, x, y, tracking=0.0):
    """Path data for `text` with its baseline at y and its left edge at x. Returns (d, width)."""
    glyphs, width, scale = _layout(name, text, size, tracking)
    gs = _font(name)[2]
    pen = SVGPathPen(gs, ntos=_fmt)
    for gname, gx, gy in glyphs:
        gs[gname].draw(TransformPen(pen, (scale, 0, 0, -scale, x + gx, y - gy)))
    return pen.getCommands(), width


def cap_height(name, size):
    _, tt, _, upem = _font(name)
    return tt['OS/2'].sCapHeight * size / upem
