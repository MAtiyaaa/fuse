"""Geometry helpers: open centerlines, elliptical-pen stroking, boolean union, squircle outlines."""
import math

import pathops
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen


class P:
    """An open centerline built from lines and cubic segments (y-up design space)."""

    def __init__(self, x, y):
        self.segs = []
        self.start = (x, y)
        self.cur = (x, y)

    def L(self, x, y):
        self.segs.append(('L', (x, y)))
        self.cur = (x, y)
        return self

    def C(self, c1, c2, e):
        self.segs.append(('C', c1, c2, e))
        self.cur = e
        return self

    def _quarter(self, x, y, first, k):
        x0, y0 = self.cur
        if first == 'h':
            return (x0, y0), (x0 + k * (x - x0), y0), (x, y - k * (y - y0)), (x, y)
        return (x0, y0), (x0, y0 + k * (y - y0)), (x - k * (x - x0), y), (x, y)

    def Q4(self, x, y, first, k=0.6):
        """Quarter curve to (x, y). first is 'h' when the tangent at the start is horizontal, 'v' when
        vertical. k is the superness: 0.5523 draws a circle, larger values a squircle."""
        _, c1, c2, e = self._quarter(x, y, first, k)
        return self.C(c1, c2, e)

    def Q4cut(self, x, y, first, k, t):
        """Like Q4, but stops at parameter t (for terminals that end part way round a bowl)."""
        a, b, c, d = self._quarter(x, y, first, k)
        lerp = lambda p, q: (p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t)
        ab, bc, cd = lerp(a, b), lerp(b, c), lerp(c, d)
        abc, bcd = lerp(ab, bc), lerp(bc, cd)
        return self.C(ab, abc, lerp(abc, bcd))

    def path(self):
        p = pathops.Path()
        p.moveTo(*self.start)
        for s in self.segs:
            if s[0] == 'L':
                p.lineTo(*s[1])
            else:
                p.cubicTo(*s[1], *s[2], *s[3])
        return p


def stroke(center, width, ratio=1.0):
    """Stroke with an elliptical pen: vertical strokes get `width`, horizontal ones width * ratio,
    the optical correction type designers make so horizontals do not look heavier."""
    p = center.path().transform(1, 0, 0, 1 / ratio, 0, 0)
    p.stroke(width, pathops.LineCap.BUTT_CAP, pathops.LineJoin.MITER_JOIN, 10)
    return p.transform(1, 0, 0, ratio, 0, 0)


def union(*paths):
    out = pathops.Path()
    for p in paths:
        out.addPath(p)
    return pathops.simplify(out, fix_winding=True)


def translate(p, dx, dy=0):
    return p.transform(1, 0, 0, 1, dx, dy)


def svg_d(p, scale=1.0, dx=0.0, dy=0.0, flip_h=None, nd=2):
    """SVG path data. flip_h is the design-space height used to flip y (SVG y = flip_h - y)."""
    def fmt(v):
        s = ('%.' + str(nd) + 'f') % v
        s = s.rstrip('0').rstrip('.') if '.' in s else s
        return '0' if s == '-0' else s
    pen = SVGPathPen(None, ntos=fmt)
    if flip_h is None:
        t = (scale, 0, 0, scale, dx, dy)
    else:
        t = (scale, 0, 0, -scale, dx, dy + flip_h * scale)
    p.draw(TransformPen(pen, t))
    return pen.getCommands()


def squircle_path(x, y, w, h, radius, smoothing=0.6):
    """SVG path data for Fuse's continuous-corner rectangle, the same construction as
    squirclePath in ui/designsystem (shape/SquircleShape.kt)."""
    half = min(w, h) / 2
    r = min(radius, half)
    pp = min((1 + smoothing) * r, half)
    s = min(max(pp / r - 1, 0), 1)
    arc_measure = 90 * (1 - s)
    arc_section = math.sin(math.radians(arc_measure / 2)) * r * math.sqrt(2)
    alpha = (90 - arc_measure) / 2
    p3p4 = r * math.tan(math.radians(alpha / 2))
    beta = 45 * s
    c = p3p4 * math.cos(math.radians(beta))
    d = c * math.tan(math.radians(beta))
    b = (pp - arc_section - c - d) / 3
    a = 2 * b
    f = lambda v: ('%.2f' % v).rstrip('0').rstrip('.')
    P_ = lambda *v: ' '.join(f(q) for q in v)

    def arc_to(cx, cy, deg):
        return 'A' + P_(r, r, 0, 0, 1, cx + r * math.cos(math.radians(deg)), cy + r * math.sin(math.radians(deg)))

    return ''.join([
        'M' + P_(x + pp, y),
        'H' + f(x + w - pp),
        'C' + P_(x + w - pp + a, y, x + w - pp + a + b, y, x + w - pp + a + b + c, y + d),
        arc_to(x + w - r, y + r, -90 + alpha + arc_measure),
        'C' + P_(x + w, y + pp - a - b, x + w, y + pp - a, x + w, y + pp),
        'V' + f(y + h - pp),
        'C' + P_(x + w, y + h - pp + a, x + w, y + h - pp + a + b, x + w - d, y + h - pp + a + b + c),
        arc_to(x + w - r, y + h - r, alpha + arc_measure),
        'C' + P_(x + w - pp + a + b, y + h, x + w - pp + a, y + h, x + w - pp, y + h),
        'H' + f(x + pp),
        'C' + P_(x + pp - a, y + h, x + pp - a - b, y + h, x + pp - a - b - c, y + h - d),
        arc_to(x + r, y + h - r, 90 + alpha + arc_measure),
        'C' + P_(x, y + h - pp + a + b, x, y + h - pp + a, x, y + h - pp),
        'V' + f(y + pp),
        'C' + P_(x, y + pp - a, x, y + pp - a - b, x + d, y + pp - a - b - c),
        arc_to(x + r, y + r, 180 + alpha + arc_measure),
        'C' + P_(x + pp - a - b, y, x + pp - a, y, x + pp, y),
        'Z',
    ])
