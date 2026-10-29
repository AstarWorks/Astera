"""Cuboid builder, display-transform solver and frame-point maths shared by the heavy-weapon recipes."""
import math
import numpy as np

STEEL = ('metal.iron_block', '#b4bfcc')
STEEL_D = ('metal.iron_block', '#7f8996')
STEEL_L = ('metal.iron_block', '#e4ebf3')
STEEL_K = ('metal.iron_block', '#5b636e')
GOLD = ('metal.gold_block', None)
COPPER = ('metal.copper_block', None)
WOOD = ('wood.stripped_spruce_log', None)
WOOD_D = ('wood.stripped_dark_oak_log', None)
WOOD_L = ('wood.stripped_oak_log', None)
LEATHER = ('cloth.brown_wool', None)
BLACK = ('cloth.black_wool', None)
RED = ('cloth.red_wool', None)


def R(deg):
    x, y, z = [math.radians(a) for a in deg]
    Rx = np.array([[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]])
    Ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    Rz = np.array([[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]])
    return Rx @ Ry @ Rz


def q(p, d):
    """Model point (pixels) -> item-display-local block units (RotY(180) * (T + Rxyz * (S * (p/16 - 0.5))))."""
    v = np.array(p, float) / 16 - 0.5
    v = R(d['rotation']) @ (np.array(d['scale']) * v) + np.array(d['translation']) / 16
    return R([0, 180, 0]) @ v


def qdir(v, d):
    out = R([0, 180, 0]) @ R(d['rotation']) @ np.array(v, float)
    return out / np.linalg.norm(out)


class Model:
    def __init__(self):
        self.els = []

    def box(self, name, x0, y0, z0, x1, y1, z1, mat, rot=None):
        assert x1 > x0 and y1 > y0 and z1 > z0, (name, x0, y0, z0, x1, y1, z1)
        self.els.append(dict(name=name, f=[x0, y0, z0], t=[x1, y1, z1], mat=mat, rot=rot))

    def rotbox(self, name, x0, y0, z0, x1, y1, z1, mat, axis, angle, origin=None):
        origin = origin or [(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2]
        self.box(name, x0, y0, z0, x1, y1, z1, mat, rot=dict(axis=axis, angle=angle, origin=list(origin)))

    def seg(self, name, p0, p1, w, t, mat, zc=8.0, side=0.0):
        """Plate segment in the XY plane from p0 to p1: width w across, thickness t in Z; side shifts it to the right-hand normal."""
        dx, dy = p1[0] - p0[0], p1[1] - p0[1]
        L = math.hypot(dx, dy)
        d = math.degrees(math.atan2(dy, dx))
        a = d
        while a <= -90: a += 180
        while a > 90: a -= 180
        if abs(a) <= 45 + 1e-6:
            rot, longx = a, True
        else:
            rot, longx = (a - 90 if a > 0 else a + 90), False
        snapped = round(rot / 22.5) * 22.5
        assert abs(rot - snapped) < 0.6, (name, d, rot)
        rot = snapped
        nx, ny = math.sin(math.radians(d)), -math.cos(math.radians(d))
        cx, cy = (p0[0] + p1[0]) / 2 + nx * side, (p0[1] + p1[1]) / 2 + ny * side
        if longx: x0, y0, x1, y1 = cx - L / 2, cy - w / 2, cx + L / 2, cy + w / 2
        else: x0, y0, x1, y1 = cx - w / 2, cy - L / 2, cx + w / 2, cy + L / 2
        r = None if rot == 0 else dict(axis='z', angle=rot, origin=[cx, cy, zc])
        self.box(name, x0, y0, zc - t / 2, x1, y1, zc + t / 2, mat, rot=r)

    def bbox(self):
        pts = []
        for e in self.els:
            xs = [e['f'][0], e['t'][0]]; ys = [e['f'][1], e['t'][1]]; zs = [e['f'][2], e['t'][2]]
            for x in xs:
                for y in ys:
                    for z in zs:
                        p = np.array([x, y, z], float)
                        r = e['rot']
                        if r:
                            o = np.array(r['origin']); ax = {'x': 0, 'y': 1, 'z': 2}[r['axis']]
                            ang = [0, 0, 0]; ang[ax] = r['angle']
                            p = o + R(ang) @ (p - o)
                        pts.append(p)
        pts = np.array(pts)
        return pts.min(0), pts.max(0)


def display(lo, hi, grip, size=1.4, tp_rot=(35, -90, 0)):
    """Display block: grip sits on the hand origin (third person) / the greatsword's first-person grip point; bbox centred in GUI/ground/fixed."""
    c = (np.array(lo) + np.array(hi)) / 2
    L = hi[1] - lo[1]
    D = math.hypot(hi[0] - lo[0], hi[1] - lo[1])
    out = {}
    def solve(rot, s, target, point):
        return (16 * (np.array(target) - R(rot) @ (s * (np.array(point) / 16 - 0.5)))).tolist()
    r = [10, -15, -35]; s = 18.2 / D
    out['gui'] = dict(rotation=r, translation=[round(v, 3) for v in solve(r, s, [0, 0, 0], c)], scale=[round(s, 3)] * 3)
    s_fp = 0.56 * (size * 16 / L) / 0.75; r = [0, -35, 12]
    out['firstperson_righthand'] = dict(rotation=r, translation=[round(v, 4) for v in solve(r, s_fp, [0, -0.0630, -0.1875], grip)], scale=[round(s_fp, 3)] * 3)
    out['firstperson_lefthand'] = dict(out['firstperson_righthand'])
    s_tp = size * 16 / L; r = list(tp_rot)
    t = [round(v, 4) for v in solve(r, s_tp, [0, 0, 0], grip)]
    out['thirdperson_righthand'] = dict(rotation=r, translation=t, scale=[round(s_tp, 3)] * 3)
    out['thirdperson_lefthand'] = dict(rotation=r, translation=[-t[0] if t[0] else 0.0, t[1], t[2]], scale=[round(s_tp, 3)] * 3)
    r = [75, 0, -90]; s = 11.7 / D
    out['ground'] = dict(rotation=r, translation=[round(v, 4) for v in solve(r, s, [0, 0.0625, 0], c)], scale=[round(s, 3)] * 3)
    r = [0, 180, -35]; s = s_tp
    out['fixed'] = dict(rotation=r, translation=[round(v, 4) for v in solve(r, s, [0, 0, 0], c)], scale=[round(s, 3)] * 3)
    return out
