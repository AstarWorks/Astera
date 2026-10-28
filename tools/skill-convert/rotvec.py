"""Euler -> rotation vector, the only place Euler exists (ADR-0303 section 7; the runtime has no Euler).

v1 (d41b82f1e, kernel/domain/.../geometry/math/Quaternion.kt `fromEulerDegrees`) read a track/rig rotation triple
[yaw, pitch, roll] in degrees as `Ry(-yaw) * Rx(pitch) * Rz(roll)` (right-hand axes of the frame: x = left, y = up,
z = forward) and its BakedRig lerped the three numbers per component at integer ticks. The v2 runtime reads a rotation
VECTOR (axis * degrees) and slerps, so a converted keyframe list is densified to one key per tick: the pose at every
tick equals the v1 pose at that tick, and the slerp between two adjacent ticks is the nearest-neighbour approximation of
the v1 per-component lerp (identical for single-axis spins).
"""
import math



def _q_axis(ax, ay, az, deg):
    h = math.radians(deg) / 2.0
    s = math.sin(h)
    return (math.cos(h), ax * s, ay * s, az * s)  # (w, x, y, z)


def _q_mul(a, b):
    aw, ax, ay, az = a
    bw, bx, by, bz = b
    return (
        aw * bw - ax * bx - ay * by - az * bz,
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
    )


def euler_to_quat(yaw, pitch, roll):
    q = _q_mul(_q_mul(_q_axis(0, 1, 0, -yaw), _q_axis(1, 0, 0, pitch)), _q_axis(0, 0, 1, roll))
    n = math.sqrt(sum(c * c for c in q))
    return tuple(c / n for c in q)


def quat_to_vec(q):
    w, x, y, z = q
    if w < 0:
        w, x, y, z = -w, -x, -y, -z
    s = math.sqrt(x * x + y * y + z * z)
    if s < 1e-12:
        return [0.0, 0.0, 0.0]
    ang = math.degrees(2.0 * math.atan2(s, w))
    return [x / s * ang, y / s * ang, z / s * ang]


def euler_to_vec(e):
    """[yaw, pitch, roll] degrees (v1) -> rotation vector [x, y, z] degrees."""
    v = quat_to_vec(euler_to_quat(float(e[0]), float(e[1]), float(e[2])))
    return [0.0 if abs(c) < 1e-9 else c for c in v]


def _bounce_out(t):
    n1, d1 = 7.5625, 2.75
    if t < 1.0 / d1:
        return n1 * t * t
    if t < 2.0 / d1:
        t -= 1.5 / d1
        return n1 * t * t + 0.75
    if t < 2.5 / d1:
        t -= 2.25 / d1
        return n1 * t * t + 0.9375
    t -= 2.625 / d1
    return n1 * t * t + 0.984375


_C1 = 1.70158
_C3 = _C1 + 1.0
_C2 = _C1 * 1.525
_C4 = 2.0 * math.pi / 3.0
_C5 = 2.0 * math.pi / 4.5
EASING = {
    "linear": lambda t: t,
    "step": lambda t: 1.0 if t >= 1.0 else 0.0,
    "in_sine": lambda t: 1.0 - math.cos(t * math.pi / 2.0),
    "out_sine": lambda t: math.sin(t * math.pi / 2.0),
    "in_out_sine": lambda t: -(math.cos(math.pi * t) - 1.0) / 2.0,
    "in_quad": lambda t: t * t,
    "out_quad": lambda t: 1.0 - (1.0 - t) ** 2,
    "in_out_quad": lambda t: 2.0 * t * t if t < 0.5 else 1.0 - (-2.0 * t + 2.0) ** 2 / 2.0,
    "in_cubic": lambda t: t ** 3,
    "out_cubic": lambda t: 1.0 - (1.0 - t) ** 3,
    "in_out_cubic": lambda t: 4.0 * t ** 3 if t < 0.5 else 1.0 - (-2.0 * t + 2.0) ** 3 / 2.0,
    "in_quart": lambda t: t ** 4,
    "out_quart": lambda t: 1.0 - (1.0 - t) ** 4,
    "in_out_quart": lambda t: 8.0 * t ** 4 if t < 0.5 else 1.0 - (-2.0 * t + 2.0) ** 4 / 2.0,
    "in_quint": lambda t: t ** 5,
    "out_quint": lambda t: 1.0 - (1.0 - t) ** 5,
    "in_out_quint": lambda t: 16.0 * t ** 5 if t < 0.5 else 1.0 - (-2.0 * t + 2.0) ** 5 / 2.0,
    "in_expo": lambda t: 0.0 if t == 0.0 else 2.0 ** (10.0 * t - 10.0),
    "out_expo": lambda t: 1.0 if t == 1.0 else 1.0 - 2.0 ** (-10.0 * t),
    "in_out_expo": lambda t: 0.0 if t == 0.0 else 1.0 if t == 1.0 else (2.0 ** (20.0 * t - 10.0) / 2.0 if t < 0.5 else (2.0 - 2.0 ** (-20.0 * t + 10.0)) / 2.0),
    "in_circ": lambda t: 1.0 - math.sqrt(1.0 - t * t),
    "out_circ": lambda t: math.sqrt(1.0 - (t - 1.0) ** 2),
    "in_out_circ": lambda t: (1.0 - math.sqrt(1.0 - (2.0 * t) ** 2)) / 2.0 if t < 0.5 else (math.sqrt(1.0 - (-2.0 * t + 2.0) ** 2) + 1.0) / 2.0,
    "in_back": lambda t: _C3 * t ** 3 - _C1 * t * t,
    "out_back": lambda t: 1.0 + _C3 * (t - 1.0) ** 3 + _C1 * (t - 1.0) ** 2,
    "in_out_back": lambda t: ((2.0 * t) ** 2 * ((_C2 + 1.0) * 2.0 * t - _C2)) / 2.0 if t < 0.5 else ((2.0 * t - 2.0) ** 2 * ((_C2 + 1.0) * (t * 2.0 - 2.0) + _C2) + 2.0) / 2.0,
    "in_elastic": lambda t: 0.0 if t == 0.0 else 1.0 if t == 1.0 else -(2.0 ** (10.0 * t - 10.0)) * math.sin((t * 10.0 - 10.75) * _C4),
    "out_elastic": lambda t: 0.0 if t == 0.0 else 1.0 if t == 1.0 else 2.0 ** (-10.0 * t) * math.sin((t * 10.0 - 0.75) * _C4) + 1.0,
    "in_out_elastic": lambda t: 0.0 if t == 0.0 else 1.0 if t == 1.0 else (-(2.0 ** (20.0 * t - 10.0) * math.sin((20.0 * t - 11.125) * _C5)) / 2.0 if t < 0.5 else (2.0 ** (-20.0 * t + 10.0) * math.sin((20.0 * t - 11.125) * _C5)) / 2.0 + 1.0),
    "out_bounce": _bounce_out,
    "in_bounce": lambda t: 1.0 - _bounce_out(1.0 - t),
    "in_out_bounce": lambda t: (1.0 - _bounce_out(1.0 - 2.0 * t)) / 2.0 if t < 0.5 else (1.0 + _bounce_out(2.0 * t - 1.0)) / 2.0,
}


def ease_key(name):
    """v1 / v2 ease name (case, '-' and an 'ease_' prefix tolerated like Easing.fromKey) or None when unknown."""
    k = name.strip().lower().replace("-", "_")
    if k.startswith("ease_"):
        k = k[5:]
    return k if k in EASING else None


def ease(name, f):
    if name is None:
        return f
    k = ease_key(name)
    if k is None:
        raise ValueError("unknown ease '%s'" % name)
    return EASING[k](min(1.0, max(0.0, f)))


def convert_keys(keys, src="rotation"):
    """Key dicts {t, offset?, rotation?(Euler), scale?, ease?} -> {t, offset?, turn?(vector), scale?, ease?}.

    Between two consecutive rotation-bearing keys a and b (b.t - a.t > 1) one turn-only key per integer tick is inserted, its
    Euler triple being v1's per-component lerp with b's ease; every other channel is untouched (channels are independent)."""
    keys = sorted(keys, key=lambda k: k["t"])
    out = {}
    for k in keys:
        e = dict(k)
        if src in e:
            e["turn"] = euler_to_vec(e.pop(src))
        out[e["t"]] = e
    rot = [k for k in keys if src in k]
    for a, b in zip(rot, rot[1:]):
        if b["t"] - a["t"] <= 1 or list(a[src]) == list(b[src]):
            continue
        for t in range(a["t"] + 1, b["t"]):
            f = ease(b.get("ease"), (t - a["t"]) / float(b["t"] - a["t"]))
            eul = [a[src][i] + (b[src][i] - a[src][i]) * f for i in range(3)]
            slot = out.setdefault(t, {"t": t})
            slot["turn"] = euler_to_vec(eul)
    return [out[t] for t in sorted(out)]
