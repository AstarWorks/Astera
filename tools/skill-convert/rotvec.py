"""Euler -> rotation vector, the only place Euler exists (ADR-0303 section 7; the runtime has no Euler).

v1 (d41b82f1e, kernel/domain/.../geometry/math/Quaternion.kt `fromEulerDegrees`) read a track/rig rotation triple
[yaw, pitch, roll] in degrees as `Ry(-yaw) * Rx(pitch) * Rz(roll)` (right-hand axes of the frame: x = left, y = up,
z = forward) and its BakedRig lerped the three numbers per component at integer ticks. The v2 runtime reads a rotation
VECTOR (axis * degrees) and slerps, so a converted keyframe list is densified to one key per tick: the pose at every
tick equals the v1 pose at that tick, and the slerp between two adjacent ticks is the nearest-neighbour approximation of
the v1 per-component lerp (identical for single-axis spins).
"""
import math

EASES = ("linear", "ease_in", "ease_out", "ease_in_out")


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


def ease(name, f):
    if name == "ease_in":
        return f * f
    if name == "ease_out":
        return 1.0 - (1.0 - f) * (1.0 - f)
    if name == "ease_in_out":
        return 2.0 * f * f if f < 0.5 else 1.0 - 2.0 * (1.0 - f) * (1.0 - f)
    return f


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
