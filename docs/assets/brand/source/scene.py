"""Fuse's scene: a room lit by the focused tile's art, with a shelf of squircle tiles.

This is an illustration of the design language (DESIGN_SYSTEM.md), not a screenshot: the tile art is
invented and abstract, drawn like Fuse's generated placeholder art, and the focused tile shows the
spark (lift to 1.07, a glow tinted with its art, the top light edge, the light sweep and the accent bar).
"""
from geom import squircle_path
from mark import f

THEMES = {
    # Fuse theme (dark)
    'dark': dict(
        name='dark', ink='#07080B', text='#F3F4F6', tagline='#E4E6EB', muted='#A6ACB8', accent='#FF6A3D',
        hairline='#FFFFFF', hairline_a=.07, shadow='#000000', shadow_a=.55, room_a=.26, edge_a=.16,
        edge_focus_a=.34, glow_a=1.0, dim='#000000', hero_dim=.16, grain=.035, fade_w=230, fade_a=.9,
    ),
    # Daylight theme (light)
    'light': dict(
        name='light', ink='#F2F1EE', text='#15171C', tagline='#2A2D34', muted='#5B616D', accent='#E9522B',
        hairline='#15171C', hairline_a=.08, shadow='#15171C', shadow_a=.20, room_a=.12, edge_a=.22,
        edge_focus_a=.40, glow_a=.75, dim='#F2F1EE', hero_dim=.35, grain=0, fade_w=120, fade_a=.75,
    ),
}

# Invented art for the shelf: (light hue, deep end). The second one is the focused tile.
ARTS = [
    ('#8B7BFF', '#120E2B'),   # violet, rings
    ('#2FC7B4', '#041E1C'),   # teal, a low sun over ridges
    ('#E7A745', '#241604'),   # amber, stacked blocks
    ('#E45C78', '#260812'),   # rose, waves
]


class Defs:
    """Collects <defs> entries with ids unique to one SVG."""

    def __init__(self, uid):
        self.uid = uid
        self.items = []
        self.n = 0

    def add(self, body, prefix='d'):
        self.n += 1
        i = f'{self.uid}{prefix}{self.n}'
        self.items.append(body.replace('@ID', i))
        return i

    def blur(self, sd):
        return self.add(f'<filter id="@ID" x="-2000" y="-2000" width="6000" height="6000" filterUnits="userSpaceOnUse" '
                        f'color-interpolation-filters="sRGB"><feGaussianBlur stdDeviation="{f(sd)}"/></filter>', 'b')

    @staticmethod
    def _stops(stops):
        return ''.join(f'<stop offset="{f(o)}" stop-color="{c}"' + (f' stop-opacity="{f(a)}"' if a != 1 else '') + '/>'
                       for o, c, a in stops)

    def lin(self, x1, y1, x2, y2, stops):
        return self.add(f'<linearGradient id="@ID" x1="{f(x1)}" y1="{f(y1)}" x2="{f(x2)}" y2="{f(y2)}" '
                        f'gradientUnits="userSpaceOnUse">{self._stops(stops)}</linearGradient>', 'g')

    def rad(self, cx, cy, r, stops, transform=None):
        t = f' gradientTransform="{transform}"' if transform else ''
        return self.add(f'<radialGradient id="@ID" cx="{f(cx)}" cy="{f(cy)}" r="{f(r)}"{t} '
                        f'gradientUnits="userSpaceOnUse">{self._stops(stops)}</radialGradient>', 'g')

    def clip(self, d):
        return self.add(f'<clipPath id="@ID"><path d="{d}"/></clipPath>', 'c')

    def render(self):
        return '<defs>' + ''.join(self.items) + '</defs>'


def soft_falloff(c, a):
    """Stops for a light that falls off smoothly (roughly gaussian)."""
    return [(0, c, a), (.25, c, a * .62), (.5, c, a * .28), (.75, c, a * .08), (1, c, 0)]


def landscape(D, x, y, w, h, hue, deep, sun=(.62, .5), detail=1.0):
    """The focused tile's invented art, a low sun over layered ridges, in any rectangle."""
    X = lambda v: f(x + w * v)
    Y = lambda v: f(y + h * v)
    m = min(w, h)
    sx, sy = x + w * sun[0], y + h * sun[1]
    sky = D.lin(x, y, x + w * .4, y + h, [(0, hue, 1), (.7, deep, 1), (1, deep, 1)])
    out = f'<rect x="{X(0)}" y="{Y(0)}" width="{f(w)}" height="{f(h)}" fill="url(#{sky})"/>'
    glow = D.rad(sx, sy, m * .5, [(0, '#FFE2B8', .95), (.2, '#FFB36E', .72), (.5, '#FF8A4C', .22), (1, '#FF8A4C', 0)])
    out += f'<rect x="{X(0)}" y="{Y(0)}" width="{f(w)}" height="{f(h)}" fill="url(#{glow})"/>'
    out += f'<circle cx="{f(sx)}" cy="{f(sy)}" r="{f(m * .12)}" fill="#FFEBD3" opacity="{f(.92 * detail)}"/>'
    ridge = D.lin(0, y + h * .5, 0, y + h, [(0, hue, .55), (1, deep, .9)])
    out += (f'<path d="M{X(0)} {Y(.62)}C{X(.18)} {Y(.54)} {X(.3)} {Y(.5)} {X(.46)} {Y(.58)}'
            f'S{X(.8)} {Y(.66)} {X(1)} {Y(.55)}V{Y(1)}H{X(0)}Z" fill="url(#{ridge})"/>')
    out += (f'<path d="M{X(0)} {Y(.78)}C{X(.22)} {Y(.68)} {X(.42)} {Y(.72)} {X(.6)} {Y(.8)}'
            f'S{X(.86)} {Y(.84)} {X(1)} {Y(.74)}V{Y(1)}H{X(0)}Z" fill="{deep}" opacity=".92"/>')
    return out


def motif(D, kind, x, y, s, hue, deep):
    """Abstract, original motifs so each tile feels like a different world."""
    X = lambda v: f(x + s * v)
    Y = lambda v: f(y + s * v)
    if kind == 0:   # rings
        return ''.join(f'<circle cx="{X(.7)}" cy="{Y(.72)}" r="{f(s * r)}" fill="none" stroke="#FFFFFF" '
                       f'stroke-opacity="{f(a)}" stroke-width="{f(s * .02)}"/>'
                       for r, a in ((.14, .22), (.26, .14), (.38, .08), (.5, .04)))
    if kind == 1:   # a low sun over ridges
        return landscape(D, x, y, s, s, hue, deep)
    if kind == 2:   # stacked blocks
        return ''.join(f'<rect x="{X(bx)}" y="{Y(by)}" width="{f(s * .22)}" height="{f(s * .22)}" rx="{f(s * .04)}" '
                       f'fill="#FFFFFF" opacity="{f(.16 - i * .03)}"/>'
                       for i, (bx, by) in enumerate(((.18, .64), (.42, .64), (.3, .44))))
    # waves
    return ''.join(f'<path d="M{X(-.05)} {Y(y0)}C{X(.2)} {Y(y0 - .14)} {X(.35)} {Y(y0 + .14)} {X(.55)} {Y(y0)}'
                   f'S{X(.9)} {Y(y0 - .14)} {X(1.05)} {Y(y0 - .02)}" fill="none" stroke="#FFFFFF" stroke-opacity="{a}" '
                   f'stroke-width="{f(s * .035)}" stroke-linecap="round"/>'
                   for y0, a in ((.66, '.2'), (.8, '.1')))


def tile(D, x, y, s, art, th, kind, light_pos, focused=False):
    """One squircle tile (the SOFT corner family: 0.20 of its side) with generated-style art."""
    r = s * 0.20
    d = squircle_path(x, y, s, s, r)
    hue, deep = art
    out = []
    if focused:
        # the glow, tinted with the tile's art, under a tighter contact shadow
        out.append(f'<path d="{squircle_path(x + s * .02, y + s * .12, s * .96, s * .96, r)}" fill="{hue}" '
                   f'opacity="{f(.85 * th["glow_a"])}" filter="url(#{D.blur(s * .2)})"/>')
        out.append(f'<path d="{squircle_path(x + s * .04, y + s * .06, s * .92, s * .92, r)}" fill="{th["shadow"]}" '
                   f'opacity="{f(th["shadow_a"] * .9)}" filter="url(#{D.blur(s * .05)})"/>')
    else:
        out.append(f'<path d="{squircle_path(x + s * .05, y + s * .07, s * .9, s * .9, r)}" fill="{th["shadow"]}" '
                   f'opacity="{f(th["shadow_a"] * .8)}" filter="url(#{D.blur(s * .06)})"/>')
    g = D.lin(x, y, x + s, y + s, [(0, hue, 1), (.62, deep, 1), (1, deep, 1)])
    parts = [f'<rect x="{f(x)}" y="{f(y)}" width="{f(s)}" height="{f(s)}" fill="url(#{g})"/>']
    lg = D.rad(x + s * light_pos[0], y + s * light_pos[1], s * .75, soft_falloff('#FFFFFF', .22 if focused else .16))
    parts.append(f'<rect x="{f(x)}" y="{f(y)}" width="{f(s)}" height="{f(s)}" fill="url(#{lg})"/>')
    parts.append(motif(D, kind, x, y, s, hue, deep))
    # faint diagonal lines, the generated-art texture
    lines = ''.join(f'M{f(x + i * s / 9)} {f(y + s)}L{f(x + i * s / 9 + s)} {f(y)}' for i in range(-9, 10))
    parts.append(f'<path d="{lines}" stroke="#FFFFFF" stroke-opacity=".022" stroke-width="{f(s * .012)}" fill="none"/>')
    if focused:
        # the sweep: one band of light, 55% of the tile wide, caught mid-pass
        bw, sx = s * .55, x + s * .52
        sg = D.lin(sx - bw / 2, 0, sx + bw / 2, 0, [(0, '#FFFFFF', 0), (.5, '#FFFFFF', .22), (1, '#FFFFFF', 0)])
        parts.append(f'<rect x="{f(sx - bw / 2)}" y="{f(y - s * .2)}" width="{f(bw)}" height="{f(s * 1.4)}" '
                     f'fill="url(#{sg})" transform="rotate(18 {f(sx)} {f(y + s / 2)})"/>')
    out.append(f'<g clip-path="url(#{D.clip(d)})">' + ''.join(parts) + '</g>')
    # the light edge along the top
    ea = th['edge_focus_a'] if focused else th['edge_a']
    eg = D.lin(0, y, 0, y + s * .45, [(0, '#FFFFFF', ea), (1, '#FFFFFF', 0)])
    out.append(f'<path d="{squircle_path(x + .5, y + .5, s - 1, s - 1, r - .5)}" fill="none" stroke="url(#{eg})" stroke-width="1"/>')
    if focused:
        # the accent bar below the tile, glowing
        bw, bh = s * .24, s * .032
        bx, by = x + s / 2 - bw / 2, y + s + s * .07
        out.append(f'<rect x="{f(bx - bh)}" y="{f(by - bh * .5)}" width="{f(bw + 2 * bh)}" height="{f(bh * 2)}" rx="{f(bh)}" '
                   f'fill="{th["accent"]}" opacity=".85" filter="url(#{D.blur(bh * 1.8)})"/>')
        out.append(f'<rect x="{f(bx)}" y="{f(by)}" width="{f(bw)}" height="{f(bh)}" rx="{f(bh / 2)}" fill="{th["accent"]}"/>')
    return ''.join(out)


def shelf(D, th, x0, y0, s, focus_lift):
    """Four tiles, the second one focused (scaled 1.07 about its centre and lifted)."""
    gap = round(s * .18)
    lights = [(.3, .25), (.62, .45), (.7, .3), (.25, .6)]
    out = [tile(D, x0 + i * (s + gap), y0, s, ARTS[i], th, i, lights[i]) for i in (0, 2, 3)]
    fs = s * 1.07
    out.append(tile(D, x0 + s + gap - (fs - s) / 2, y0 - (fs - s) / 2 - focus_lift, fs, ARTS[1], th, 1, lights[1], True))
    return ''.join(out)


def grain(D, W, H, alpha):
    """Fine film grain; it also dithers the large dark gradients so they never band."""
    n = D.add('<filter id="@ID" x="0" y="0" width="100%" height="100%" color-interpolation-filters="sRGB">'
              '<feTurbulence type="fractalNoise" baseFrequency=".85" numOctaves="2" seed="7" stitchTiles="stitch"/>'
              '<feColorMatrix values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 1.2 -.35"/></filter>', 'n')
    return f'<rect x="0" y="0" width="{W}" height="{H}" filter="url(#{n})" opacity="{f(alpha)}"/>'


def hero_backdrop(D, th, W, H, x, y, w, h, blur, sun):
    """The focused art filling the room behind everything, defocused, as HeroBackdrop does in the app.
    In the bright room the art reads as coloured light on paper instead."""
    hue, deep = ARTS[1]
    b = D.blur(blur)
    if th['name'] == 'dark':
        return (f'<g filter="url(#{b})">' + landscape(D, x, y, w, h, hue, deep, sun=sun, detail=.8)
                + f'<ellipse cx="{f(x + w * .95)}" cy="{f(y + h * .12)}" rx="{f(w * .3)}" ry="{f(h * .3)}" fill="#3F7BFF" opacity=".35"/>'
                + '</g>'
                + f'<rect x="0" y="0" width="{W}" height="{H}" fill="{th["dim"]}" opacity="{f(th["hero_dim"])}"/>')
    sx, sy = x + w * sun[0], y + h * sun[1]
    return (f'<g filter="url(#{b})">'
            f'<ellipse cx="{f(x + w * .42)}" cy="{f(y + h * .18)}" rx="{f(w * .42)}" ry="{f(h * .34)}" fill="{hue}" opacity=".30"/>'
            f'<ellipse cx="{f(x + w * .95)}" cy="{f(y + h * .2)}" rx="{f(w * .3)}" ry="{f(h * .3)}" fill="#4F8DFF" opacity=".16"/>'
            f'<circle cx="{f(sx)}" cy="{f(sy)}" r="{f(h * .2)}" fill="#FFB36E" opacity=".42"/>'
            f'<circle cx="{f(sx)}" cy="{f(sy)}" r="{f(h * .08)}" fill="#FFE2B8" opacity=".7"/>'
            f'</g>')


def room(D, th, W, H, hero, left, bottom, top, glow):
    """Backdrop, scrims into the room colour (left, bottom, top) and the low glow from the bottom left."""
    ink = th['ink']
    out = [f'<rect x="0" y="0" width="{W}" height="{H}" fill="{ink}"/>', hero_backdrop(D, th, W, H, *hero)]
    sl = D.lin(left[0], 0, left[1], 0, [(0, ink, 1), (.55, ink, .55), (1, ink, 0)])
    out.append(f'<rect x="0" y="0" width="{W}" height="{H}" fill="url(#{sl})"/>')
    sb = D.lin(0, H, 0, H * bottom, [(0, ink, .96), (1, ink, 0)])
    out.append(f'<rect x="0" y="0" width="{W}" height="{H}" fill="url(#{sb})"/>')
    st = D.lin(0, 0, 0, top, [(0, ink, .5), (1, ink, 0)])
    out.append(f'<rect x="0" y="0" width="{W}" height="{top}" fill="url(#{st})"/>')
    rg = D.rad(0, 0, 1, soft_falloff(ARTS[1][0], th['room_a']), transform=f'matrix({glow[0]} 0 0 {glow[1]} {glow[2]} {H + glow[3]})')
    out.append(f'<rect x="0" y="0" width="{W}" height="{H}" fill="url(#{rg})"/>')
    return ''.join(out)


def far_fade(D, th, W, H, width, alpha):
    """The room swallows the far end of the shelf."""
    sr = D.lin(W - width, 0, W, 0, [(0, th['ink'], 0), (1, th['ink'], alpha)])
    return f'<rect x="{W - width}" y="0" width="{width}" height="{H}" fill="url(#{sr})"/>'
