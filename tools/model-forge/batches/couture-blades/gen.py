#!/usr/bin/env python3
"""Couturier's shears v3: THREE separate needle blades (founder correction 2026-10-27).

Reference (Limbus S2, ticks 20-60): the "shears" are three identical slim dagger blades, each with its own angular two-lobed
thorny wire handle loop. Held (t0-26) they are stacked into one raised rapier around a single floral rosette; from t28 they
fan apart, hover side by side (t36-42), fly as a bundle (t44-50) and end up criss-crossed in the target (t52-60).

Pivot = (8,8,*) (same as v2, ADR-0282 rig keys keep working). Blade k is a z layer; a fan angle phi_k rotates blade+neck about
the pivot, the handle loop rotates by psi_k = clip(2*phi_k) so the three loops separate faster than the blades.

Outputs (content/models/items):
  couture-rapier            held, assembled (rest fan +-REST deg)
  couture-rapier-open-15/30/45/60   fan opened further by N deg total (outer blades +-(REST+N/2))
  couture-rapier-blade-1/2/3        single blade + its own handle loop, straight (blade-1 carries the rosette)
  couture-rapier-thrown-flight/-stuck   fan baked 90 deg about the long axis so the fan is visible from a side camera when the
                                         rig rotates the item [90,0,0] (flight dir = +z)
usage: gen.py [repo_root]
"""
import copy, json, math, sys
from pathlib import Path

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[4]
OUT = root / 'content/models/items'
PIV = (8.0, 8.0)
REST = 10.0
TEX = {'iron': '#material_iron_block', 'wool': '#material_white_wool', 'pink': '#material_pink_wool'}
# tint indices (astera_tints): 0 gunmetal, 1 steel, 2 pale rose-white, 3 near-black, 4 silver, 5 white, 6 wine, 7 pink
T_GUN, T_STEEL, T_PALE, T_DARK, T_SILVER, T_WHITE, T_WINE, T_PINK = range(8)
TINTS = [0x3f4658, 0x8089a3, 0xf4eaf0, 0x1f232c, 0xcfd2dc, 0xfff3fa, 0x8a2f55, 0xe0809c]
Z_LAYER = {'A': 8.6, 'M': 7.4, 'B': 6.2}   # layer base z, thickness 1.2 (A left blade, M centre, B right)
THICK = 1.2
_seed = [0]


def _uv(dx, dy):
    _seed[0] += 1
    s = _seed[0]
    u = (s * 5) % max(1, int(16 - dx)) if dx < 16 else 0
    v = (s * 7) % max(1, int(16 - dy)) if dy < 16 else 0
    return [u, v, round(u + dx, 4), round(v + dy, 4)]


def box(name, f, t, tex, tint, rot=None, skip=(), group='blade', layer='M'):
    f = [round(x, 4) for x in f]
    t = [round(x, 4) for x in t]
    dx, dy, dz = t[0] - f[0], t[1] - f[1], t[2] - f[2]
    dims = {'north': (dx, dy), 'south': (dx, dy), 'east': (dz, dy), 'west': (dz, dy), 'up': (dx, dz), 'down': (dx, dz)}
    faces = {}
    for k, (a, b) in dims.items():
        if k in skip:
            continue
        faces[k] = {'texture': TEX[tex], 'uv': _uv(min(a, 16), min(b, 16)), 'rotation': 0, 'tintindex': tint}
    e = {'name': name, 'from': f, 'to': t, 'faces': faces, '_group': group, '_layer': layer}
    if rot:
        e['rotation'] = rot
    return e


def bar(name, p, q, w, z0, z1, tex, tint, group, layer, extra=0.0):
    """Rotated bar between two xy points (axis-aligned box + a z rotation about its centre)."""
    dx, dy = q[0] - p[0], q[1] - p[1]
    L = math.hypot(dx, dy) + extra
    cx, cy = (p[0] + q[0]) / 2, (p[1] + q[1]) / 2
    ang = math.degrees(math.atan2(-dx, dy))
    ang = (ang + 180) % 360 - 180
    if ang > 90: ang -= 180
    if ang < -90: ang += 180
    rot = {'origin': [cx, cy, (z0 + z1) / 2], 'axis': 'z', 'angle': round(ang, 4)} if abs(ang) > 1e-6 else None
    return box(name, (cx - w / 2, cy - L / 2, z0), (cx + w / 2, cy + L / 2, z1), tex, tint, rot, group=group, layer=layer)


def rotz(a, v):
    c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
    return (c * v[0] - s * v[1], s * v[0] + c * v[1])


def compose(el, phi):
    """Rotate an element by phi about the hinge (8,8) (z axis); same maths as batches/couture-shears-open/open_angles.py."""
    el = copy.deepcopy(el)
    if abs(phi) < 1e-9:
        return el
    r = el.get('rotation')
    alpha = r['angle'] if r else 0.0
    o = (r['origin'][0], r['origin'][1]) if r else PIV
    oz = r['origin'][2] if r else (el['from'][2] + el['to'][2]) / 2
    d = rotz(phi, (o[0] - PIV[0], o[1] - PIV[1]))
    c = (d[0] + PIV[0], d[1] + PIV[1])
    total = alpha + phi
    if abs(total) < 1e-9:
        dx, dy = c[0] - o[0], c[1] - o[1]
        el['from'][0] += dx; el['to'][0] += dx; el['from'][1] += dy; el['to'][1] += dy
        el.pop('rotation', None)
        return el
    ro = rotz(total, o)
    rhs = (c[0] - ro[0], c[1] - ro[1])
    cs, sn = math.cos(math.radians(total)), math.sin(math.radians(total))
    a, b, cc, dd = 1 - cs, sn, -sn, 1 - cs
    det = a * dd - b * cc
    ox = (rhs[0] * dd - b * rhs[1]) / det
    oy = (a * rhs[1] - cc * rhs[0]) / det
    el['rotation'] = {'origin': [round(ox, 6), round(oy, 6), oz], 'axis': 'z', 'angle': round(total, 6)}
    return el


def blade_parts(k, layer):
    """Straight needle blade k (points +y from the hinge) + neck + two-lobed wire loop (hangs -y), local frame, no fan."""
    z0 = Z_LAYER[layer]
    z1 = z0 + THICK
    zm = (z0 + z1) / 2
    els = []
    n = 8
    y0, y1 = 9.0, 31.4
    h = (y1 - y0) / n

    def hw(y):  # half width: broad ricasso -> needle point
        return 2.0 - (2.0 - 0.28) * ((y - y0) / (y1 - y0)) ** 0.9

    for i in range(n):
        ya, yb = y0 + i * h, y0 + (i + 1) * h
        w = hw((ya + yb) / 2)
        sp = max(0.16, 0.38 * w / 2)
        rim = min(0.42, w * 0.3)
        P = f'{k}_s{i}'
        # left / right rim (bright edge bevel), bevel faces (steel), centre spine (dark, raised)
        els.append(box(P + '_rimL', (8 - w, ya, zm - .3), (8 - w + rim, yb, zm + .3), 'iron', T_WHITE, layer=layer))
        els.append(box(P + '_rimR', (8 + w - rim, ya, zm - .3), (8 + w, yb, zm + .3), 'iron', T_WHITE, layer=layer))
        els.append(box(P + '_bevL', (8 - w + rim, ya, z0 + .1), (8 - sp, yb, z1 - .1), 'iron', T_STEEL, layer=layer))
        els.append(box(P + '_bevR', (8 + sp, ya, z0 + .1), (8 + w - rim, yb, z1 - .1), 'iron', T_STEEL, layer=layer))
        els.append(box(P + '_spine', (8 - sp, ya, z0 + .05), (8 + sp, yb, z1 + .35), 'iron', T_DARK, layer=layer))
    els.append(box(f'{k}_tip', (7.8, y1, zm - .25), (8.2, 32.0, zm + .25), 'iron', T_WHITE, layer=layer))
    # ricasso block at the hinge (dark), under the rosette
    els.append(box(f'{k}_ricasso', (6.2, 6.6, z0), (9.8, 9.0, z1), 'iron', T_GUN, layer=layer))
    # handle: neck + loop. group 'handle' rotates by psi
    H = []
    neck = bar(f'{k}_neck', (8, 8.4), (8, 3.2), 1.0, z0 + .1, z1 - .1, 'iron', T_GUN, 'handle', layer)
    H.append(neck)
    V = [(8, 3.2), (10.9, 0.6), (10.3, -3.6), (8, -6.8), (5.7, -3.6), (5.1, 0.6)]
    edges = [(0, 1), (1, 2), (2, 3), (3, 4), (4, 5), (5, 0), (1, 5)]
    for j, (a, b) in enumerate(edges):
        H.append(bar(f'{k}_ld{j}', V[a], V[b], 1.5, zm - .38, zm + .38, 'iron', T_DARK, 'handle', layer, extra=0.8))
        H.append(bar(f'{k}_lb{j}', V[a], V[b], 0.8, zm - .6, zm + .6, 'iron', T_SILVER, 'handle', layer, extra=0.5))
    # thorns at the outer corners
    for j, (vi, ang) in enumerate([(1, 55), (2, 100), (4, -100), (5, -55), (3, 180)]):
        p = V[vi]
        d = (math.sin(math.radians(ang)), math.cos(math.radians(ang)))
        q = (p[0] + d[0] * 1.7, p[1] + d[1] * 1.7)
        H.append(bar(f'{k}_th{j}', p, q, 0.55, zm - .3, zm + .3, 'iron', T_SILVER, 'handle', layer))
    for e in els:
        e['_group'] = 'blade'
    return els + H


def rosette(zbase):
    els = []
    c = (8.0, 8.0)
    z1 = zbase + 0.9
    els.append(box('ro_disc', (6.0, 6.0, zbase), (10.0, 10.0, z1), 'iron', T_SILVER, group='fixed'))
    els.append(box('ro_disc45', (6.0, 6.0, zbase), (10.0, 10.0, z1), 'iron', T_STEEL,
                   rot={'origin': [8, 8, (zbase + z1) / 2], 'axis': 'z', 'angle': 45}, group='fixed'))
    for i in range(8):
        a = i * 45
        px, py = 8 + 3.9 * math.sin(math.radians(a)), 8 + 3.9 * math.cos(math.radians(a))
        els.append(box(f'ro_pet{i}', (px - .85, py - .85, zbase + .2), (px + .85, py + .85, zbase + 1.0), 'wool', T_WHITE,
                       rot={'origin': [px, py, zbase + .6], 'axis': 'z', 'angle': 45}, group='fixed'))
    els.append(box('ro_core', (7.0, 7.0, z1), (9.0, 9.0, z1 + .5), 'iron', T_WINE, group='fixed'))
    els.append(box('ro_heart', (7.4, 7.4, z1 + .5), (8.6, 8.6, z1 + .8), 'pink', T_PINK, group='fixed'))
    return els


def build(fan=(0.0, 0.0, 0.0), with_rosette=True, only=None):
    """fan = (phiA, phiM, phiB) blade angles (deg, + = tip toward -x)."""
    out = []
    parts = {'A': 'a', 'M': 'm', 'B': 'b'}
    for (layer, phi) in zip(('A', 'M', 'B'), fan):
        if only and layer != only:
            continue
        psi = max(-40.0, min(40.0, 2.0 * phi))
        for e in blade_parts(parts[layer], layer):
            ang = phi if e['_group'] == 'blade' else psi
            out.append(compose(e, ang))
    if with_rosette and (not only or only == 'M'):
        zb = Z_LAYER['A'] + THICK + 0.05 if not only else Z_LAYER[only] + THICK + 0.05
        out += rosette(zb)
    for e in out:
        e.pop('_group', None); e.pop('_layer', None)
    return out


BASE = json.loads((OUT / 'couture-rapier.model.json').read_text()) if (OUT / 'couture-rapier.model.json').exists() else None


def header(display):
    return {'textures': {'material_iron_block': 'minecraft:block/iron_block', 'particle': 'minecraft:block/iron_block',
                         'material_white_wool': 'minecraft:block/white_wool', 'material_pink_wool': 'minecraft:block/pink_wool'},
            'display': display, 'gui_light': 'front', 'ambientocclusion': False, 'astera_tints': TINTS}


def bake_ry90(els):
    """Rotate the whole model 90 deg about the long (y) axis through (8,*,8): x<->z. Element z-rotations become x-rotations."""
    out = []
    for e in els:
        e = copy.deepcopy(e)
        f, t = e['from'], e['to']
        nf = [f[2], f[1], 16 - t[0]]
        nt = [t[2], t[1], 16 - f[0]]
        e['from'], e['to'] = nf, nt
        fc = e['faces']
        m = {'east': 'north', 'west': 'south', 'south': 'east', 'north': 'west', 'up': 'up', 'down': 'down'}
        nfaces = {}
        for k, v in fc.items():
            v = copy.deepcopy(v)
            if k in ('up', 'down'):
                u = v['uv']
                v['uv'] = [u[0], u[1], round(u[0] + (u[3] - u[1]), 4), round(u[1] + (u[2] - u[0]), 4)]
                if v['uv'][2] > 16 or v['uv'][3] > 16:
                    v['uv'] = [0, 0, min(16, v['uv'][2] - v['uv'][0]), min(16, v['uv'][3] - v['uv'][1])]
            nfaces[m[k]] = v
        e['faces'] = nfaces
        r = e.get('rotation')
        if r:
            ox, oy, oz = r['origin']
            e['rotation'] = {'origin': [oz, oy, 16 - ox], 'axis': 'x', 'angle': r['angle']}
        out.append(e)
    return out


DISPLAY_HELD = {
    'gui': {'rotation': [10, -15, -35], 'translation': [0.5, -2.5, 0], 'scale': [0.48, 0.48, 0.48]},
    'firstperson_righthand': {'rotation': [0, -35, 12], 'translation': [0.5, 1.0, -4.5], 'scale': [0.42, 0.42, 0.42]},
    'firstperson_lefthand': {'rotation': [0, -35, 12], 'translation': [0.5, 1.0, -4.5], 'scale': [0.42, 0.42, 0.42]},
    'thirdperson_righthand': {'rotation': [35, -90, 0], 'translation': [0.0, 2.4, 1.5], 'scale': [0.75, 0.75, 0.75]},
    'thirdperson_lefthand': {'rotation': [35, -90, 0], 'translation': [0.0, 2.4, 1.5], 'scale': [0.75, 0.75, 0.75]},
    'ground': {'rotation': [75, 0, -90], 'translation': [0, 1, 0], 'scale': [0.36, 0.36, 0.36]},
    'fixed': {'rotation': [0, 180, -35], 'translation': [0, -2, -1.2], 'scale': [0.62, 0.62, 0.62]},
}
DISPLAY_FLY = copy.deepcopy(DISPLAY_HELD)
DISPLAY_FLY['fixed'] = {'rotation': [0, 0, 0], 'translation': [0, -3.1, 0], 'scale': [0.62, 0.62, 0.62]}


def write(name, els, display):
    m = header(display)
    m['elements'] = els
    (OUT / f'{name}.model.json').write_text(json.dumps(m, indent=2) + '\n')
    print(name, len(els))


if __name__ == '__main__':
    rest = (REST, 0.0, -REST)
    write('couture-rapier', build(rest), DISPLAY_HELD)
    for n in (15, 30, 45, 60):
        a = REST + n / 2
        write(f'couture-rapier-open-{n}', build((a, 0.0, -a)), DISPLAY_HELD)
    for k, layer in (('1', 'M'), ('2', 'A'), ('3', 'B')):
        els = build((0, 0, 0), with_rosette=(k == '1'), only=layer)
        write(f'couture-rapier-blade-{k}', els, DISPLAY_FLY)
    for name, n in (('flight', 12), ('stuck', 44)):
        a = REST + n / 2
        write(f'couture-rapier-thrown-{name}', bake_ry90(build((a, 0.0, -a))), DISPLAY_FLY)
