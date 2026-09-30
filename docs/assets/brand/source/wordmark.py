"""The Fuse wordmark: original geometric lettering in the spirit of Sora, drawn here, not set in a font.

Letters are centerlines stroked with an elliptical pen (horizontals 86% of verticals), bowls are
squircles (superness 0.64) like Fuse's tiles, and the F's top corner is a continuous curve that
echoes the mark's frame. Design units: baseline 0, x-height 520, cap height 700, y up.
"""
from geom import P, stroke, union, translate

CAP, XH, OS = 700, 520, 11      # cap height, x-height, overshoot of round letters
T = 106                         # vertical stroke
K = 0.86                        # horizontal stroke / vertical stroke
TH = T * K
SUP = 0.64                      # superness of the bowls (0.5523 would be circular)


def letter_F():
    arm, bar, bar_y, corner = 430, 388, 322, 80
    yt = CAP - TH / 2
    stem = P(T / 2, 0).L(T / 2, yt - corner).Q4(T / 2 + corner, yt, 'v', 0.62).L(arm, yt)
    mid = P(T / 2, bar_y).L(bar, bar_y)
    return union(stroke(stem, T, K), stroke(mid, T, K)), arm


def letter_u():
    W = 468
    L, R = T / 2, W - T / 2
    bot = -OS + TH / 2
    cx = (L + R) / 2 - 6
    yq = 236
    bowl = P(L, XH).L(L, yq).Q4(cx, bot, 'v', SUP).Q4(R, yq, 'h', SUP)
    stem = P(R, XH).L(R, 0)
    return union(stroke(bowl, T, K), stroke(stem, T, K)), W


def letter_s():
    W = 424
    L, R = T / 2 + 1, W - T / 2
    top = XH + OS - TH / 2
    bot = -OS + TH / 2
    mid = 268
    Rt = W - 16 - T / 2               # the top bowl is a little narrower
    cxt = (L + Rt) / 2 + 6
    cxb = (L + R) / 2 - 6
    ymt = (top + mid) / 2 + 4
    ymb = (mid + bot) / 2 - 4
    a = P(cxt, top).Q4cut(Rt, ymt, 'h', SUP, 0.62)
    b = P(cxt, top).Q4(L, ymt, 'h', SUP).Q4(cxt, mid, 'v', SUP).L(cxb, mid)
    b.Q4(R, ymb, 'h', SUP).Q4(cxb, bot, 'v', SUP).Q4cut(L, ymb, 'h', SUP, 0.66)
    return union(stroke(a, T, K), stroke(b, T, K)), W


def letter_e():
    W = 492
    L, R = T / 2, W - T / 2
    top = XH + OS - TH / 2
    bot = -OS + TH / 2
    bar_y = 274
    cx = W / 2
    yq = bar_y + TH / 2 - 8           # where the upper bowl starts to curve
    bowl = P(R, bar_y - TH / 2).L(R, yq).Q4(cx, top, 'v', SUP).Q4(L, yq - 14, 'h', SUP)
    bowl.L(L, bar_y - 30).Q4(cx, bot, 'v', SUP)
    bowl.C((cx + 100, bot), (R - 30, bot + 10), (R + 6, bot + 92))
    bar = P(L, bar_y).L(R, bar_y)
    return union(stroke(bowl, T, K), stroke(bar, T, K)), W


def wordmark(gaps=(14, 62, 54)):
    """The joined outline and its advance width. gaps are the spaces F-u, u-s and s-e."""
    F, fw = letter_F()
    u, uw = letter_u()
    s, sw = letter_s()
    e, ew = letter_e()
    x = fw + gaps[0]
    parts = [F, translate(u, x)]
    x += uw + gaps[1]
    parts.append(translate(s, x))
    x += sw + gaps[2]
    parts.append(translate(e, x))
    return union(*parts), x + ew
