"""The README hero banner (1280 x 420, dark and light) and the social preview (1280 x 640)."""
from geom import squircle_path
from lockup import lockup_parts
from mark import f
from scene import THEMES, Defs, room, shelf, far_fade, grain
from text import text_path

TAGLINE = 'A controller-first home for your games.'
META = ['ANDROID', 'LINUX', 'FREE SOFTWARE']
TITLE = 'Fuse. A controller-first home for your games, on Android and Linux.'


def meta_line(th, x, y, size, gap):
    """Overline-style items separated by small spark dots."""
    out = []
    for i, item in enumerate(META):
        d, w = text_path('manrope_bold', item, size, x, y, tracking=0.14)
        out.append(f'<path d="{d}" fill="{th["muted"]}"/>')
        x += w
        if i < len(META) - 1:
            out.append(f'<circle cx="{f(x + gap / 2)}" cy="{f(y - size * .36)}" r="{f(size * .17 + .02)}" fill="{th["accent"]}"/>')
            x += gap
    return ''.join(out)


def _svg(W, H, D, body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" role="img" '
            f'aria-label="{TITLE}"><title>{TITLE}</title>' + D.render() + body + '</svg>')


def banner(theme, W=1280, H=420):
    th = THEMES[theme]
    D = Defs('b' + theme[0])
    card = squircle_path(0, 0, W, H, 28)
    g = [room(D, th, W, H, hero=(560, -70, 760, 520, 28, (.5, .46)), left=(430, 900), bottom=.45, top=110,
              glow=(620, 250, 170, 40)),
         shelf(D, th, 676, 186, 150, 7),
         far_fade(D, th, W, H, th['fade_w'], th['fade_a'])]
    if th['grain']:
        g.append(grain(D, W, H, th['grain']))
    # the brand block, centred on the card's height
    cap, lx = 72, 78
    ms = cap * 1.3
    ly = (H - (ms + 50 + 44)) / 2 - 12
    ld, lb, _, lh = lockup_parts(cap, lx, ly, th['text'], th['text'], D.uid + 'k', glow_a=th['glow_a'])
    D.items.append(ld)
    g.append(lb)
    tx = lx + ms * .05                      # optically aligned with the mark's frame
    ty = ly + lh + 54
    d, _ = text_path('manrope_medium', TAGLINE, 27, tx, ty, tracking=-0.006)
    g.append(f'<path d="{d}" fill="{th["tagline"]}"/>')
    g.append(meta_line(th, tx, ty + 42, 14, 30))
    body = (f'<g clip-path="url(#{D.clip(card)})">' + ''.join(g) + '</g>'
            f'<path d="{squircle_path(.5, .5, W - 1, H - 1, 27.5)}" fill="none" stroke="{th["hairline"]}" '
            f'stroke-opacity="{f(th["hairline_a"])}"/>')
    return _svg(W, H, D, body)


def social(W=1280, H=640):
    """GitHub's social preview. Full bleed, dark, the tagline on two lines so it reads at half size."""
    th = THEMES['dark']
    D = Defs('sp')
    g = [room(D, th, W, H, hero=(480, -90, 860, 700, 34, (.52, .4)), left=(360, 860), bottom=.5, top=140,
              glow=(720, 330, 180, 50)),
         shelf(D, th, 612, 262, 196, 9),
         far_fade(D, th, W, H, 260, .92),
         grain(D, W, H, th['grain'])]
    cap, lx, size = 92, 92, 38
    ms = cap * 1.3
    lines = ['A controller-first home', 'for your games.']
    lead = size * 1.26
    ly = (H - (ms + 64 + lead + 50)) / 2 - 16
    ld, lb, _, lh = lockup_parts(cap, lx, ly, th['text'], th['text'], 'spk', glow_a=th['glow_a'])
    D.items.append(ld)
    g.append(lb)
    tx = lx + ms * .05
    ty = ly + lh + 70
    for i, line in enumerate(lines):
        d, _ = text_path('manrope_medium', line, size, tx, ty + i * lead, tracking=-0.01)
        g.append(f'<path d="{d}" fill="{th["tagline"]}"/>')
    g.append(meta_line(th, tx, ty + lead + 50, 16, 34))
    return _svg(W, H, D, ''.join(g))
