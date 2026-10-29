"""Heavy melee weapon recipes. Each returns (Model, spec). Model space: 1 unit = 1 vanilla texel = 1/16 block, +Y = long axis,
plates lie in XY and are thin in Z (centre z = 8); grip at the bottom, x = 8. spec: grip/root/tip points, edge direction, extra points."""
import math
from geom import *


def wraps(m, label, x0, x1, y0, y1, step, mat, z0=None, z1=None, pad=0.18, band=0.5):
    y = y0; i = 0
    while y + band <= y1 + 1e-6:
        m.box(f'{label} wrap {i}', x0 - pad, y, (z0 if z0 is not None else 8 - (x1 - x0) / 2) - pad, x1 + pad, y + band,
              (z1 if z1 is not None else 8 + (x1 - x0) / 2) + pad, mat)
        y += step; i += 1


def rivets(m, label, pts, mat, s=0.7, face_z=(7.0, 9.0), depth=0.14):
    for i, (x, y) in enumerate(pts):
        for side, z in zip('nf', face_z):
            zz = (z - depth, z) if side == 'n' else (z, z + depth)
            m.box(f'{label} rivet {i}{side}', x - s / 2, y - s / 2, zz[0], x + s / 2, y + s / 2, zz[1], GOLD)


# ----------------------------------------------------------------------------------------------------- cleaver
def cleaver():
    m = Model()
    # handle: flared stepped wood grip with copper pommel and brass rivets
    m.box('copper pommel cap', 6.5, -1.3, 6.5, 9.5, -0.2, 9.5, COPPER)
    m.box('copper pommel bead', 7.1, -1.7, 7.1, 8.9, -1.3, 8.9, COPPER)
    m.box('handle flare', 6.7, -0.2, 6.7, 9.3, 1.8, 9.3, WOOD_L)
    m.box('handle lower', 6.85, 1.8, 6.85, 9.15, 5.4, 9.15, WOOD_L)
    m.box('handle upper', 6.9, 5.4, 6.9, 9.1, 9.0, 9.1, WOOD_L)
    for i, y in enumerate((2.4, 4.0, 5.6, 7.2)):
        m.box(f'handle groove {i}', 6.78, y, 6.78, 9.22, y + 0.35, 9.22, WOOD_D)
    rivets(m, 'handle', [(8, 3.2), (8, 6.4), (8, 8.0)], GOLD, 0.8, (6.85, 9.15))
    m.box('ferrule lower', 6.55, 9.0, 6.55, 9.45, 9.7, 9.45, STEEL_D)
    m.box('ferrule upper', 6.45, 9.7, 6.7, 9.55, 10.4, 9.3, STEEL)
    # bolster across the blade foot
    m.box('bolster core', 6.9, 10.4, 6.9, 14.6, 11.4, 9.1, STEEL_D)
    m.box('bolster step', 7.1, 11.4, 7.05, 14.0, 11.8, 8.95, STEEL)
    # blade: thick spine, plate, three stepped bevels toward the cutting edge (+X)
    yb, yt = 11.8, 27.6
    m.box('spine cap', 6.9, yt, 6.85, 8.5, yt + 0.6, 9.15, STEEL_K)
    m.box('spine strip', 6.9, yb, 6.85, 8.5, yt, 9.15, STEEL_K)
    m.box('spine ridge', 7.1, yb, 6.7, 7.9, yt, 6.85, STEEL_D)
    m.box('spine ridge back', 7.1, yb, 9.15, 7.9, yt, 9.3, STEEL_D)
    m.box('plate left', 8.5, yb, 7.25, 9.7, yt, 8.75, STEEL)
    m.box('plate right', 11.4, yb, 7.25, 12.6, yt, 8.75, STEEL)
    m.box('plate mid foot', 9.7, yb, 7.25, 11.4, 22.6, 8.75, STEEL)
    m.box('plate mid head', 9.7, 25.2, 7.25, 11.4, yt, 8.75, STEEL)
    m.box('hang hole rim lo', 9.7, 22.6, 7.45, 11.4, 22.9, 8.55, STEEL_K)
    m.box('hang hole rim hi', 9.7, 24.9, 7.45, 11.4, 25.2, 8.55, STEEL_K)
    m.box('bevel 1 low', 12.6, yb, 7.5, 13.8, 20.0, 8.5, STEEL_L)
    m.box('bevel 1 high', 12.6, 20.0, 7.5, 13.8, 27.0, 8.5, STEEL_L)
    m.box('bevel 2', 13.8, yb + 0.3, 7.72, 14.6, 26.2, 8.28, STEEL_L)
    m.box('cutting edge', 14.6, yb + 0.6, 7.88, 15.0, 25.4, 8.12, STEEL_L)
    m.box('edge heel cap', 14.6, yb, 7.88, 15.0, yb + 0.6, 8.12, STEEL)
    m.box('corner chamfer 1', 12.6, 27.0, 7.6, 13.4, 27.6, 8.4, STEEL_L)
    m.box('corner chamfer 2', 13.4, 26.2, 7.8, 14.0, 26.8, 8.2, STEEL_L)
    for i, (y, c) in enumerate(((13.4, 0), (16.0, 1), (18.6, 0), (21.0, 1))):
        m.box(f'forge scale {i}', 8.9 + c * 0.9, y, 7.15, 10.1 + c * 0.9, y + 0.5, 7.25, STEEL_D)
        m.box(f'forge scale back {i}', 8.9 + c * 0.9, y, 8.75, 10.1 + c * 0.9, y + 0.5, 8.85, STEEL_D)
    rivets(m, 'blade', [(7.7, 14.6), (7.7, 18.4), (7.7, 24.2)], GOLD, 0.75, (6.7, 9.3))
    m.box('edge nick 1', 14.2, 17.2, 7.7, 15.0, 17.9, 8.3, STEEL_K)
    m.box('edge nick 2', 14.2, 22.0, 7.7, 15.0, 22.5, 8.3, STEEL_K)
    spec = dict(grip=(8, 4.2, 8), root=(14.8, 12.4, 8), tip=(14.8, 25.4, 8), edge=(1, 0, 0), points={'head': (11.0, 19.0, 8)})
    return m, spec


# ------------------------------------------------------------------------------------------------------ scythe
def scythe():
    m = Model()
    m.box('steel butt cap', 7.0, -7.4, 7.0, 9.0, -6.6, 9.0, STEEL_D)
    m.box('butt knob', 7.3, -6.6, 7.3, 8.7, -6.0, 8.7, STEEL)
    m.box('snath lower', 7.1, -6.0, 7.1, 8.9, 8.0, 8.9, WOOD)
    wraps(m, 'grip', 7.1, 8.9, -4.6, 6.4, 1.0, LEATHER, band=0.62)
    m.box('grip collar low', 6.9, -5.1, 6.9, 9.1, -4.7, 9.1, STEEL_D)
    m.box('grip collar high', 6.9, 6.6, 6.9, 9.1, 7.0, 9.1, STEEL_D)
    m.box('snath mid', 7.2, 8.0, 7.2, 8.8, 20.0, 8.8, WOOD)
    m.box('snath upper', 7.25, 20.0, 7.25, 8.75, 29.2, 8.75, WOOD)
    for i, y in enumerate((11.0, 17.5, 24.0)):
        m.box(f'snath ring {i}', 7.0, y, 7.0, 9.0, y + 0.45, 9.0, STEEL_D)
    # side handle peg
    m.box('peg arm', 4.3, 13.6, 7.35, 7.2, 14.9, 8.65, WOOD)
    m.box('peg arm cap', 3.7, 13.45, 7.15, 4.3, 15.05, 8.85, STEEL_D)
    m.box('peg block', 7.0, 13.2, 6.95, 9.0, 15.3, 9.05, STEEL)
    # socket and tang
    m.box('socket lower', 6.85, 28.6, 6.85, 9.15, 29.4, 9.15, STEEL_D)
    m.box('socket ring', 6.7, 29.4, 6.7, 9.3, 30.1, 9.3, STEEL)
    m.box('tang back', 5.5, 29.5, 7.45, 7.0, 30.9, 8.55, STEEL_D)
    m.box('tang back tip', 4.4, 29.9, 7.65, 5.5, 30.6, 8.35, STEEL)
    rivets(m, 'socket', [(7.4, 29.75), (8.9, 29.75)], GOLD, 0.7, (6.7, 9.3), 0.12)
    # blade: turtle along the spine, outer edge on top, cutting edge on the inner (right-hand, lower) side
    path = [(0.0, 3.4, 3.7), (-22.5, 4.2, 3.6), (-45.0, 4.4, 3.2), (-67.5, 4.4, 2.6), (-67.5, 3.4, 1.8), (-90.0, 1.6, 1.0)]
    pos = (9.0, 29.9)
    pts = [pos]
    for i, (a, L, w) in enumerate(path):
        ar = math.radians(a)
        nxt = (pos[0] + L * math.cos(ar), pos[1] + L * math.sin(ar))
        k = 0.02 * (i % 3)
        m.seg(f'blade body {i}', pos, nxt, w, 1.0 + k, STEEL_L if i % 2 else STEEL)
        m.seg(f'blade spine {i}', pos, nxt, 0.8, 1.7 - k, STEEL_K, side=-(w / 2 - 0.1))
        m.seg(f'blade fuller {i}', pos, nxt, max(0.5, w * 0.28), 1.25 + k, STEEL_D, side=-(w * 0.12))
        m.seg(f'blade edge {i}', pos, nxt, 0.5, 0.42, STEEL_L, side=(w / 2 - 0.2))
        pts.append(nxt); pos = nxt
    tipp = (pts[-1][0] + 0.0, pts[-1][1] - 0.5)
    m.box('blade point', tipp[0] - 0.4, tipp[1] - 0.8, 7.8, tipp[0] + 0.4, tipp[1] + 0.6, 8.2, STEEL_L)
    # root / tip along the cutting (lower) edge line
    root = (pts[0][0] + 0.0, pts[0][1] - 1.7)
    tip = (tipp[0], tipp[1] - 0.8)
    spec = dict(grip=(8, 1.2, 8), root=(root[0] + 0.5, root[1], 8), tip=(tip[0], tip[1], 8), edge=None,
                points={'haft_top': (8, 29.5, 8)}, haft=(0, 1, 0))
    return m, spec


# -------------------------------------------------------------------------------------------------------- club
def club():
    m = Model()
    m.box('pommel knob', 6.8, -1.6, 6.8, 9.2, -0.4, 9.2, WOOD_D)
    m.box('pommel cap', 6.3, -0.4, 6.3, 9.7, 0.6, 9.7, STEEL_D)
    m.box('grip core', 6.9, 0.6, 6.9, 9.1, 8.6, 9.1, WOOD_D)
    wraps(m, 'grip', 6.9, 9.1, 1.0, 8.2, 1.15, BLACK, band=0.7, pad=0.2)
    m.box('grip ring', 6.4, 8.6, 6.4, 9.6, 9.3, 9.6, STEEL_D)
    # tapering stacked sections of the striking head
    secs = [(9.3, 12.5, 2.4), (12.5, 15.5, 3.0), (15.5, 18.6, 3.5), (18.6, 21.6, 3.9), (21.6, 24.0, 4.1)]
    for i, (y0, y1, hw) in enumerate(secs):
        m.box(f'head section {i}', 8 - hw, y0, 8 - hw, 8 + hw, y1, 8 + hw, WOOD if i % 2 == 0 else WOOD_L)
        m.box(f'head grain {i}', 8 - hw - 0.15, y0 + 0.7, 8 - hw * 0.5, 8 + hw + 0.15, y0 + 1.1, 8 + hw * 0.5, WOOD_D)
    for i, (y, hw) in enumerate(((12.3, 2.9), (15.4, 3.5), (18.5, 4.0), (21.5, 4.3))):
        m.box(f'iron band {i}', 8 - hw - 0.3, y, 8 - hw - 0.3, 8 + hw + 0.3, y + 0.8, 8 + hw + 0.3, STEEL_D)
        m.box(f'iron band lip {i}', 8 - hw - 0.1, y + 0.8, 8 - hw - 0.1, 8 + hw + 0.1, y + 1.0, 8 + hw + 0.1, STEEL)
    m.box('iron cap', 4.0, 24.0, 4.0, 12.0, 24.9, 12.0, STEEL_D)
    m.box('iron cap step', 4.8, 24.9, 4.8, 11.2, 25.5, 11.2, STEEL)
    m.box('cap spike base', 6.6, 25.5, 6.6, 9.4, 26.4, 9.4, STEEL)
    m.box('cap spike mid', 7.2, 26.4, 7.2, 8.8, 27.5, 8.8, STEEL_L)
    m.box('cap spike tip', 7.65, 27.5, 7.65, 8.35, 28.3, 8.35, STEEL_L)
    k = 0
    for y in (13.9, 17.0, 20.1, 22.8):
        hw = {13.9: 3.0, 17.0: 3.5, 20.1: 4.0, 22.8: 4.2}[y]
        for s in (-1, 1):
            for ax in (0, 1):
                if ax == 0: m.box(f'stud {k}', 8 - 0.6, y, 8 + s * hw - (0.0 if s > 0 else 0.5), 8 + 0.6, y + 1.0, 8 + s * hw + (0.5 if s > 0 else 0.0), STEEL_L)
                else: m.box(f'stud {k}', 8 + s * hw - (0.0 if s > 0 else 0.5), y, 8 - 0.6, 8 + s * hw + (0.5 if s > 0 else 0.0), y + 1.0, 8 + 0.6, STEEL_L)
                k += 1
    spec = dict(grip=(8, 4.6, 8), root=(8, 9.5, 8), tip=(8, 27.0, 8), edge=(1, 0, 0), points={'head_top': (8, 25.5, 8), 'head_center': (8, 17.5, 8)})
    return m, spec


# -------------------------------------------------------------------------------------------------------- mace
def mace():
    m = Model()
    m.box('pommel knob', 6.8, -1.6, 6.8, 9.2, -0.5, 9.2, STEEL_D)
    m.box('pommel flange', 6.3, -0.5, 6.3, 9.7, 0.4, 9.7, STEEL)
    m.box('shaft lower', 7.0, 0.4, 7.0, 9.0, 15.2, 9.0, WOOD)
    wraps(m, 'grip', 7.0, 9.0, 0.8, 9.4, 1.1, LEATHER, band=0.65, pad=0.2)
    m.box('grip collar', 6.5, 9.6, 6.5, 9.5, 10.3, 9.5, STEEL_D)
    m.box('shaft grain', 6.9, 12.0, 7.1, 9.1, 12.6, 8.9, WOOD_D)
    m.box('collar lower', 6.4, 14.4, 6.4, 9.6, 15.2, 9.6, STEEL_D)
    m.box('collar upper', 5.6, 15.2, 5.6, 10.4, 16.0, 10.4, STEEL)
    # flanged head: core, rotated core, 8 stepped flanges
    m.box('head core', 6.2, 16.0, 6.2, 9.8, 26.4, 9.8, STEEL)
    m.rotbox('head core diag', 6.2, 16.6, 6.2, 9.8, 25.8, 9.8, STEEL_D, 'y', 45)
    for i, (y0, y1, a) in enumerate(((16.6, 17.6, 0), (24.8, 25.8, 0))):
        m.box(f'head ring {i}', 5.6, y0, 5.6, 10.4, y1, 10.4, STEEL_D)
        m.rotbox(f'head ring diag {i}', 5.6, y0, 5.6, 10.4, y1, 10.4, STEEL, 'y', 45)
    fl = [(0, 'x+', 9.8, 12.9), (1, 'x-', 3.1, 6.2), (2, 'z+', 9.8, 12.9), (3, 'z-', 3.1, 6.2)]
    for i, side, a, b in fl:
        if side[0] == 'x':
            m.box(f'flange {side} base', a, 17.4, 7.35, b, 25.0, 8.65, STEEL_L)
        else:
            m.box(f'flange {side} base', 7.35, 17.4, a, 8.65, 25.0, b, STEEL_L)
    # outer bevel step on each axis flange
    m.box('flange x+ bevel', 12.9, 18.4, 7.65, 13.6, 24.0, 8.35, STEEL)
    m.box('flange x- bevel', 2.4, 18.4, 7.65, 3.1, 24.0, 8.35, STEEL)
    m.box('flange z+ bevel', 7.65, 18.4, 12.9, 8.35, 24.0, 13.6, STEEL)
    m.box('flange z- bevel', 7.65, 18.4, 2.4, 8.35, 24.0, 3.1, STEEL)
    # diagonal flanges
    for i, (ang, nm) in enumerate(((45, 'a'), (-45, 'b'))):
        m.rotbox(f'diag flange {nm} +', 9.4, 17.8, 7.4, 12.6, 24.6, 8.6, STEEL_D, 'y', ang, origin=[8, 21, 8])
        m.rotbox(f'diag flange {nm} -', 3.4, 17.8, 7.4, 6.6, 24.6, 8.6, STEEL_D, 'y', ang, origin=[8, 21, 8])
    for i, (x, z) in enumerate(((13.8, 8), (2.2, 8), (8, 13.8), (8, 2.2))):
        m.box(f'flange tip {i}', x - 0.5, 19.8, z - 0.5, x + 0.5, 22.6, z + 0.5, STEEL_L)
    m.box('top spike base', 6.7, 26.4, 6.7, 9.3, 27.4, 9.3, STEEL)
    m.box('top spike mid', 7.25, 27.4, 7.25, 8.75, 28.6, 8.75, STEEL_L)
    m.box('top spike tip', 7.7, 28.6, 7.7, 8.3, 29.6, 8.3, STEEL_L)
    m.box('bottom spike', 7.4, 14.9, 7.4, 8.6, 16.0, 8.6, STEEL_L)
    for i, (x, z) in enumerate(((8, 12.6), (8, 3.4), (12.6, 8), (3.4, 8))):
        m.box(f'gold stud {i}', x - 0.35, 25.2, z - 0.35, x + 0.35, 25.9, z + 0.35, GOLD)
    spec = dict(grip=(8, 4.8, 8), root=(8, 16.2, 8), tip=(8, 29.4, 8), edge=(1, 0, 0), points={'head': (8, 21.0, 8)})
    return m, spec


# ----------------------------------------------------------------------------------------------------- war-axe
def war_axe():
    m = Model()
    m.box('pommel spike', 7.55, -3.2, 7.55, 8.45, -1.8, 8.45, STEEL_L)
    m.box('pommel cap', 6.7, -1.8, 6.7, 9.3, -0.4, 9.3, STEEL_D)
    m.box('haft', 7.1, -0.4, 7.1, 8.9, 27.0, 8.9, WOOD_D)
    m.box('haft grain', 6.95, 6.0, 7.2, 9.05, 6.6, 8.8, WOOD)
    m.box('haft grain 2', 6.95, 12.0, 7.2, 9.05, 12.6, 8.8, WOOD)
    wraps(m, 'grip', 7.1, 8.9, 0.4, 10.4, 1.25, RED, band=0.8, pad=0.2)
    m.box('grip ring', 6.7, 10.5, 6.7, 9.3, 11.1, 9.3, STEEL_D)
    m.box('socket lower', 6.5, 21.4, 6.5, 9.5, 22.2, 9.5, STEEL_D)
    m.box('socket mid', 6.2, 22.2, 6.4, 9.8, 26.8, 9.6, STEEL)
    m.box('socket top', 6.5, 26.8, 6.5, 9.5, 27.6, 9.5, STEEL_D)
    # crescent blade on +X: stacked strips, each with core + bevel + edge
    rows = [(22.2, 23.4, 9.8, 14.6), (23.4, 24.6, 9.8, 15.4), (24.6, 25.8, 9.8, 15.9), (25.8, 27.0, 9.8, 16.1),
            (27.0, 28.2, 9.8, 16.2), (28.2, 29.4, 9.8, 16.1), (29.4, 30.6, 9.8, 15.8), (30.6, 31.6, 9.8, 15.2)]
    for i, (y0, y1, xa, xb) in enumerate(rows):
        m.box(f'blade core {i}', xa, y0, 7.35, xb - 2.4, y1, 8.65, STEEL)
        m.box(f'blade bevel {i}', xb - 2.4, y0, 7.6, xb - 0.6, y1, 8.4, STEEL_L)
        m.box(f'blade edge {i}', xb - 0.6, y0, 7.85, xb, y1, 8.15, STEEL_L)
    m.box('blade spine rib', 9.8, 22.2, 7.0, 10.6, 31.6, 9.0, STEEL_D)
    m.box('blade fuller', 11.4, 24.0, 7.2, 12.4, 30.0, 7.35, STEEL_K)
    m.box('blade fuller back', 11.4, 24.0, 8.65, 12.4, 30.0, 8.8, STEEL_K)
    m.box('horn tip', 14.4, 31.6, 7.85, 15.4, 32.6, 8.15, STEEL_L)
    m.box('beard tip', 13.4, 21.4, 7.85, 14.6, 22.2, 8.15, STEEL_L)
    # back poll + spike
    m.box('poll block', 3.8, 23.6, 7.0, 6.2, 27.0, 9.0, STEEL)
    m.box('poll step', 2.6, 24.2, 7.3, 3.8, 26.4, 8.7, STEEL_D)
    m.box('back spike base', 1.2, 24.6, 7.55, 2.6, 26.0, 8.45, STEEL)
    m.box('back spike tip', -0.4, 25.0, 7.75, 1.2, 25.6, 8.25, STEEL_L)
    for i, (x, y) in enumerate(((7.0, 23.2), (7.0, 26.4), (5.0, 25.3))):
        for s, z in (('n', 6.9), ('f', 9.0)):
            m.box(f'rivet {i}{s}', x - 0.4, y - 0.4, z - (0.1 if s == 'n' else 0), x + 0.4, y + 0.4, z + (0 if s == 'n' else 0.1), GOLD)
    m.box('top cap', 7.3, 27.6, 7.3, 8.7, 28.4, 8.7, STEEL_D)
    spec = dict(grip=(8, 5.4, 8), root=(14.2, 21.8, 8), tip=(15.0, 32.2, 8), edge=(1, 0, 0), points={'head': (11.0, 26.8, 8)})
    return m, spec


# ----------------------------------------------------------------------------------------------------- halberd
def halberd():
    m = Model()
    m.box('butt spike tip', 7.6, -14.4, 7.6, 8.4, -13.4, 8.4, STEEL_L)
    m.box('butt spike', 7.1, -13.4, 7.1, 8.9, -11.6, 8.9, STEEL)
    m.box('butt cap', 6.6, -11.6, 6.6, 9.4, -10.6, 9.4, STEEL_D)
    m.box('pole lower', 7.1, -10.6, 7.1, 8.9, 5.0, 8.9, WOOD_D)
    m.box('pole upper', 7.1, 5.0, 7.1, 8.9, 21.0, 8.9, WOOD)
    m.box('pole grain', 6.95, -3.6, 7.2, 9.05, -3.0, 8.8, WOOD)
    m.box('pole grain 2', 6.95, 10.0, 7.2, 9.05, 10.6, 8.8, WOOD_D)
    wraps(m, 'grip', 7.1, 8.9, -4.6, 1.4, 1.2, LEATHER, band=0.7, pad=0.2)
    m.box('grip ring lo', 6.7, -5.2, 6.7, 9.3, -4.7, 9.3, STEEL_D)
    m.box('grip ring hi', 6.7, 1.5, 6.7, 9.3, 2.0, 9.3, STEEL_D)
    for i, y in enumerate((8.0, 16.0)):
        m.box(f'pole band {i}', 6.8, y, 6.8, 9.2, y + 0.5, 9.2, STEEL_D)
    # langets running down the pole
    m.box('langet n', 7.55, 14.2, 6.6, 8.45, 21.4, 7.1, STEEL_D)
    m.box('langet s', 7.55, 14.2, 8.9, 8.45, 21.4, 9.4, STEEL_D)
    m.box('langet w', 6.6, 14.2, 7.55, 7.1, 21.4, 8.45, STEEL_D)
    m.box('langet e', 8.9, 14.2, 7.55, 9.4, 21.4, 8.45, STEEL_D)
    # tassel
    m.box('tassel knot', 6.9, 11.8, 6.9, 9.1, 12.6, 9.1, RED)
    m.box('tassel upper', 6.6, 9.8, 6.6, 9.4, 11.8, 9.4, RED)
    m.box('tassel lower', 6.9, 8.4, 6.9, 9.1, 9.8, 9.1, RED)
    # socket
    m.box('socket lower', 6.6, 20.6, 6.6, 9.4, 21.6, 9.4, STEEL_D)
    m.box('socket collar', 6.3, 21.6, 6.3, 9.7, 22.6, 9.7, STEEL)
    m.box('socket top', 6.7, 22.6, 6.7, 9.3, 23.4, 9.3, STEEL_D)
    # spear spike (stepped)
    m.box('spike base', 6.5, 23.4, 7.3, 9.5, 26.0, 8.7, STEEL)
    m.box('spike mid', 6.9, 26.0, 7.4, 9.1, 29.0, 8.6, STEEL_L)
    m.box('spike upper', 7.2, 29.0, 7.55, 8.8, 31.4, 8.45, STEEL_L)
    m.box('spike tip', 7.6, 31.4, 7.7, 8.4, 33.0, 8.3, STEEL_L)
    m.box('spike rib', 7.75, 24.0, 6.95, 8.25, 31.0, 7.3, STEEL_D)
    m.box('spike rib back', 7.75, 24.0, 8.7, 8.25, 31.0, 9.05, STEEL_D)
    # axe blade, +X crescent
    rows = [(14.8, 16.2, 9.4, 13.4), (16.2, 17.8, 9.4, 14.6), (17.8, 19.6, 9.4, 15.4), (19.6, 21.4, 9.4, 15.8),
            (21.4, 23.2, 9.4, 15.9), (23.2, 24.8, 9.4, 15.5), (24.8, 26.2, 9.4, 14.6)]
    for i, (y0, y1, xa, xb) in enumerate(rows):
        m.box(f'blade core {i}', xa, y0, 7.4, xb - 2.2, y1, 8.6, STEEL)
        m.box(f'blade bevel {i}', xb - 2.2, y0, 7.62, xb - 0.5, y1, 8.38, STEEL_L)
        m.box(f'blade edge {i}', xb - 0.5, y0, 7.86, xb, y1, 8.14, STEEL_L)
    m.box('blade rib', 9.4, 14.8, 7.0, 10.2, 26.2, 9.0, STEEL_D)
    m.box('blade eye', 11.0, 18.8, 7.25, 12.2, 22.4, 7.4, STEEL_K)
    m.box('blade eye back', 11.0, 18.8, 8.6, 12.2, 22.4, 8.75, STEEL_K)
    m.box('beard point', 12.0, 14.0, 7.85, 13.4, 14.8, 8.15, STEEL_L)
    m.box('horn point', 13.6, 26.2, 7.85, 14.6, 26.9, 8.15, STEEL_L)
    # back hook: stepped up-curving fluke on -X
    m.box('hook root', 4.6, 17.4, 7.35, 6.6, 20.2, 8.65, STEEL)
    m.box('hook mid', 3.0, 18.2, 7.5, 4.6, 21.2, 8.5, STEEL)
    m.box('hook up', 2.0, 20.6, 7.6, 3.4, 23.4, 8.4, STEEL_L)
    m.box('hook tip', 1.7, 23.4, 7.8, 2.8, 25.2, 8.2, STEEL_L)
    for i, (x, y) in enumerate(((8.0, 22.4), (8.0, 18.0))):
        m.box(f'gold rivet {i}n', x - 0.45, y - 0.45, 6.85, x + 0.45, y + 0.45, 7.0, GOLD)
        m.box(f'gold rivet {i}f', x - 0.45, y - 0.45, 9.0, x + 0.45, y + 0.45, 9.15, GOLD)
    spec = dict(grip=(8, -1.6, 8), root=(8, 22.8, 8), tip=(8, 32.8, 8), edge=(1, 0, 0), points={'blade_edge': (15.8, 20.5, 8), 'axe_head': (12.0, 20.5, 8)})
    return m, spec


# ----------------------------------------------------------------------------------------------------- shield
def shield():
    """Kite-ish heater shield in the XY plane, outer face +Z. Width 14, height 22; grip bar on the back."""
    m = Model()
    # planks (outer, +Z side thicker)
    rows = [(1.0, 5.0, 2.0, 14.0), (5.0, 9.0, 1.5, 14.5), (9.0, 13.0, 1.5, 14.5), (13.0, 17.0, 1.5, 14.5), (17.0, 20.0, 2.0, 14.0)]
    for i, (y0, y1, xa, xb) in enumerate(rows):
        w = xb - xa; n = 4; step = w / n
        for j in range(n):
            mat = (WOOD, WOOD_L, WOOD, WOOD_D)[(i + j) % 4]
            m.box(f'plank {i}{j}', xa + j * step, y0, 8.0, xa + (j + 1) * step - 0.05, y1, 9.4, mat)
    m.box('lower taper', 3.5, -2.0, 8.0, 12.5, 1.0, 9.4, WOOD)
    m.box('lower point', 5.5, -4.0, 8.0, 10.5, -2.0, 9.4, WOOD_D)
    m.box('upper cap', 3.0, 20.0, 8.0, 13.0, 21.4, 9.4, WOOD_L)
    # iron rim (front lip)
    m.box('rim left', 0.6, 1.0, 8.0, 1.5, 20.0, 9.9, STEEL_D)
    m.box('rim right', 14.5, 1.0, 8.0, 15.4, 20.0, 9.9, STEEL_D)
    m.box('rim top', 2.0, 21.4, 8.0, 14.0, 22.2, 9.9, STEEL_D)
    m.box('rim top left', 1.5, 20.0, 8.0, 3.0, 21.4, 9.9, STEEL_D)
    m.box('rim top right', 13.0, 20.0, 8.0, 14.5, 21.4, 9.9, STEEL_D)
    m.box('rim low left', 1.5, -0.5, 8.0, 3.5, 1.0, 9.9, STEEL_D)
    m.box('rim low right', 12.5, -0.5, 8.0, 14.5, 1.0, 9.9, STEEL_D)
    m.box('rim tip left', 3.5, -3.0, 8.0, 5.5, -0.5, 9.9, STEEL_D)
    m.box('rim tip right', 10.5, -3.0, 8.0, 12.5, -0.5, 9.9, STEEL_D)
    m.box('rim tip', 5.5, -4.8, 8.0, 10.5, -4.0, 9.9, STEEL_D)
    m.box('rim low mid l', 3.5, -2.0, 8.0, 4.4, -0.5, 9.9, STEEL_D)
    # corner plates and cross straps
    for nm, (x0, y0, x1, y1) in {'tl': (1.5, 17.5, 4.5, 20.4), 'tr': (11.5, 17.5, 14.5, 20.4), 'bl': (2.0, 1.0, 4.5, 3.8), 'br': (11.5, 1.0, 14.0, 3.8)}.items():
        m.box(f'corner {nm}', x0, y0, 9.4, x1, y1, 9.95, STEEL)
        m.box(f'corner {nm} lip', x0 + 0.5, y0 + 0.5, 9.95, x1 - 0.5, y1 - 0.5, 10.2, STEEL_L)
    m.box('strap h', 1.5, 10.2, 9.4, 14.5, 11.8, 9.8, STEEL_D)
    m.box('strap v', 7.2, 3.0, 9.4, 8.8, 18.5, 9.8, STEEL_D)
    # boss (blue enamel) with gold ring and rivets
    m.box('boss ring', 5.0, 8.0, 9.8, 11.0, 14.0, 10.4, GOLD)
    m.box('boss base', 5.6, 8.6, 10.4, 10.4, 13.4, 10.9, STEEL_D)
    m.box('boss body', 6.2, 9.2, 10.9, 9.8, 12.8, 11.5, ('gem.lapis_block', None))
    m.box('boss cap', 6.8, 9.8, 11.5, 9.2, 12.2, 12.0, ('gem.lapis_block', None))
    m.box('boss point', 7.5, 10.5, 12.0, 8.5, 11.5, 12.8, STEEL_L)
    for i, (x, y) in enumerate(((5.4, 8.4), (10.6, 8.4), (5.4, 13.6), (10.6, 13.6), (3.0, 11.0), (13.0, 11.0), (8.0, 4.4), (8.0, 17.4))):
        m.box(f'rivet {i}', x - 0.4, y - 0.4, 9.8, x + 0.4, y + 0.4, 10.2, GOLD)
    # back: grip bar, arm strap, bracing
    m.box('back brace h', 2.0, 7.0, 7.4, 14.0, 8.0, 8.0, WOOD_D)
    m.box('back brace v', 7.2, 3.0, 7.4, 8.8, 19.0, 8.0, WOOD_D)
    m.box('grip bar', 6.9, 7.2, 5.6, 9.1, 13.0, 7.4, LEATHER)
    m.box('grip bar wrap', 6.7, 9.0, 5.4, 9.3, 9.6, 7.6, BLACK)
    m.box('arm strap', 4.2, 12.8, 6.4, 11.8, 14.0, 7.4, LEATHER)
    m.box('arm strap post l', 4.2, 9.0, 6.4, 5.2, 12.8, 7.4, STEEL_D)
    m.box('arm strap post r', 10.8, 9.0, 6.4, 11.8, 12.8, 7.4, STEEL_D)
    spec = dict(grip=(8, 10.0, 6.5), root=(8, -4.8, 8.5), tip=(8, 22.2, 8.5), edge=(-1, 0, 0), points={'center': (8, 9.0, 11.0)}, up=(0, 1, 0), face=(0, 0, 1))
    return m, spec


def greatsword_spec():
    return dict(grip=(8, 2.2, 8), root=(8, 7.8, 8), tip=(8, 28.2, 8), edge=(1, 0, 0), points={})
