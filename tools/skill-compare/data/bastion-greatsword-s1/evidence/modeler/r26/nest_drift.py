"""nest_drift.py <rig yaml> [first-last key chunk range, e.g. 20-35]  -- do nested rig parts poke through their cover mid-interpolation?

The planner sends every rig part as translation = pivot + R*corner (corner = offset - size/2, the box's min corner) and
left_rotation = R. Between two keys the client LERPS the translation and SLERPS the rotation, so mid-chunk a part sits
err = lerp(R0 c, R1 c) - slerp(R0, R1) c away from where its box should be. Parts with different corners get different
errors; when the difference along the cover's face normal exceeds the nesting margin, a part that should be hidden inside
a thicker one shows through it (r25 compare f31/f37: the 0.03-block blackstone rims / tip_1 covered the 0.05-block iron
core). Prints, per nested pair and axis, the nesting margin and the worst drift difference over every key chunk and
sub-tick phase, in block; a negative 'worst' means the inner box comes out of the outer one."""
import math, re, sys
import numpy as np

def ax(a, deg):
    t = math.radians(deg); c, s = math.cos(t), math.sin(t)
    if a == 'x': return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if a == 'y': return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])
def eul(y, p, r): return ax('y', -y) @ ax('x', p) @ ax('z', r)
def mat2q(m):
    w = math.sqrt(max(0.0, 1 + m[0, 0] + m[1, 1] + m[2, 2])) / 2
    x = math.copysign(math.sqrt(max(0.0, 1 + m[0, 0] - m[1, 1] - m[2, 2])) / 2, m[2, 1] - m[1, 2])
    y = math.copysign(math.sqrt(max(0.0, 1 - m[0, 0] + m[1, 1] - m[2, 2])) / 2, m[0, 2] - m[2, 0])
    z = math.copysign(math.sqrt(max(0.0, 1 - m[0, 0] - m[1, 1] + m[2, 2])) / 2, m[1, 0] - m[0, 1])
    return np.array([w, x, y, z])
def q2mat(q):
    w, x, y, z = q / np.linalg.norm(q)
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
def slerp(a, b, s):
    d = a @ b
    if d < 0: b, d = -b, -d
    if d > 0.9995: return (a + s * (b - a)) / np.linalg.norm(a + s * (b - a))
    th = math.acos(d)
    return (math.sin((1 - s) * th) * a + math.sin(s * th) * b) / math.sin(th)

txt = open(sys.argv[1]).read()
keys = [tuple(map(float, m)) for m in re.findall(r"\{ t: \d+, rotate: \[([-\d.]+), ([-\d.]+), ([-\d.]+)\] \}", txt)]
parts = {m[0]: (np.array(list(map(float, m[1].split(',')))), np.array(list(map(float, m[2].split(',')))))
         for m in re.findall(r"id: (\w+), block: \w+, offset: \[([^\]]+)\], size: \[([^\]]+)\]", txt)}
tilt, rest = eul(0, 0, -20), eul(0, 90, 0)
Rk = [tilt @ eul(*k) @ rest for k in keys]
Qk = [mat2q(R) for R in Rk]

# inner part, outer part, axes on which inner is strictly inside outer (local: x edge, y length, z face normal)
PAIRS = [('rims', 'blade_face', 'z'), ('rims', 'edges', 'z'), ('tip_1', 'blade_face', 'z'), ('tip_1', 'edges', 'xz'),
         ('tip_2', 'blade_face', 'xz'), ('edges', 'blade_face', 'z'), ('blade_face', 'fuller', 'z'),
         ('inlay_frame', 'inlay', 'z')]
KR = range(len(keys) - 1)
if len(sys.argv) > 2:
    k0, k1 = map(int, sys.argv[2].split('-')); KR = range(k0, min(k1, len(keys) - 1))
def corner(p): o, s = parts[p]; return o - s / 2
print(f"{len(keys)} keys, {len(parts)} parts")
print("inner        outer        axis  margin  worst   (block; worst < 0 = pokes through)  at chunk/phase")
for a, b, axes in PAIRS:
    if a not in parts or b not in parts: continue
    for k in axes:
        i = 'xyz'.index(k)
        (oa, sa), (ob, sb) = parts[a], parts[b]
        margin = min((ob[i] + sb[i] / 2) - (oa[i] + sa[i] / 2), (oa[i] - sa[i] / 2) - (ob[i] - sb[i] / 2))
        worst, where = margin, '-'
        for n in KR:
            for s in np.linspace(0, 1, 11):
                Rs = q2mat(slerp(Qk[n], Qk[n + 1], s))
                e = {}
                for p in (a, b):
                    c = corner(p)
                    e[p] = Rs.T @ ((1 - s) * Rk[n] @ c + s * Rk[n + 1] @ c - Rs @ c)  # error in the part's local frame
                d = e[a][i] - e[b][i]
                m = margin - abs(d)
                if m < worst: worst, where = m, f"k{n}->{n+1} s{s:.1f}"
        print(f"{a:12s} {b:12s} {k:4s} {margin:6.4f} {worst:7.4f}  {where}")
