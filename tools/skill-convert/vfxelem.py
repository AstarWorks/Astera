"""v1 vfx element -> v2 display ops (block, quad, plate with formation / jitter / oscillate / group track / emit).

Everything here bakes v1 maths into plain v2 fields: per-slot `at` / `turn` tables indexed by `i` on a repeated op (ADR-0303
`times` / `every` / `life`), dense per-tick track keys for oscillate / group scale / emit particles, and a group frame node
for `group_keyframes`. Nothing is dropped silently: whatever cannot be expressed goes through `c.present(...)`, which raises
under `--strict`.

Quaternions are (w, x, y, z) tuples like rotvec.py; vectors are 3-lists.
"""
import math
import re

import copy
from rotvec import _q_mul, convert_keys, ease, ease_key, euler_to_quat, euler_to_vec, quat_to_vec

IDENT = (1.0, 0.0, 0.0, 0.0)


def sid(s):
    return re.sub(r"[^A-Za-z0-9_]", "_", str(s)).strip("_") or "x"

GOLDEN = math.pi * (3.0 - math.sqrt(5.0))


# ------------------------------------------------------------------ vector / quaternion helpers
def vadd(a, b):
    return [a[0] + b[0], a[1] + b[1], a[2] + b[2]]


def vsub(a, b):
    return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]


def vmul(a, k):
    return [a[0] * k, a[1] * k, a[2] * k]


def vdot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def vcross(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def vlen2(a):
    return vdot(a, a)


def vnorm(a):
    n = math.sqrt(vlen2(a))
    return [0.0, 0.0, 0.0] if n == 0.0 else [a[0] / n, a[1] / n, a[2] / n]


def vlerp(a, b, t):
    return vadd(a, vmul(vsub(b, a), t))


def qconj(q):
    return (q[0], -q[1], -q[2], -q[3])


def qrot(q, v):
    w, x, y, z = q
    # v' = q * (0, v) * q^-1
    p = _q_mul(_q_mul(q, (0.0, v[0], v[1], v[2])), (w, -x, -y, -z))
    return [p[1], p[2], p[3]]


def qnorm(q):
    n = math.sqrt(sum(c * c for c in q))
    return tuple(c / n for c in q)


def look_rotation(forward, up=(0.0, 1.0, 0.0)):
    f = vnorm(forward)
    if vlen2(f) == 0.0:
        return IDENT
    left = vcross(up, f)
    if vlen2(left) < 1e-12:
        alt = [1.0, 0.0, 0.0] if abs(f[0]) < 0.9 else [0.0, 0.0, 1.0]
        left = vcross(alt, f)
    left = vnorm(left)
    u = vcross(f, left)
    m00, m01, m02 = left[0], u[0], f[0]
    m10, m11, m12 = left[1], u[1], f[1]
    m20, m21, m22 = left[2], u[2], f[2]
    trace = m00 + m11 + m22
    if trace > 0.0:
        s = math.sqrt(trace + 1.0) * 2.0
        x, y, z, w = (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s
    elif m00 > m11 and m00 > m22:
        s = math.sqrt(1.0 + m00 - m11 - m22) * 2.0
        x, y, z, w = 0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s
    elif m11 > m22:
        s = math.sqrt(1.0 + m11 - m00 - m22) * 2.0
        x, y, z, w = (m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s
    else:
        s = math.sqrt(1.0 + m22 - m00 - m11) * 2.0
        x, y, z, w = (m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s
    return qnorm((w, x, y, z))


class SeededRandom:
    """v1 SeededRandom (splitmix64)."""
    M = (1 << 64) - 1

    def __init__(self, seed):
        self.state = seed & self.M

    def next_long(self):
        self.state = (self.state + 0x9E3779B97F4A7C15) & self.M
        z = self.state
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & self.M
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & self.M
        return z ^ (z >> 31)

    def next_double(self, lo=0.0, hi=1.0):
        r = (self.next_long() >> 11) * (2.0 ** -53)
        return lo + (hi - lo) * r


# ------------------------------------------------------------------ curves
def _polyline_point(v, t):
    n = len(v) - 1
    s = min(1.0, max(0.0, t)) * n
    i = min(max(int(math.floor(s)), 0), n - 1)
    return vlerp(v[i], v[i + 1], s - i)


def _polyline_tangent(v, t):
    n = len(v) - 1
    i = min(max(int(math.floor(min(1.0, max(0.0, t)) * n)), 0), n - 1)
    return vnorm(vsub(v[i + 1], v[i]))


class Curve:
    def __init__(self, spec, count):
        self.spec = spec
        self.kind = spec["type"]
        self.count = count
        k = self.kind
        if k == "lightning":
            frm, to = [float(x) for x in spec["from"]], [float(x) for x in spec["to"]]
            seg = int(spec["segments"])
            amp = float(spec["amplitude"])
            d = vnorm(vsub(to, frm))
            helper = [0.0, 1.0, 0.0] if abs(d[1]) < 0.9 else [1.0, 0.0, 0.0]
            p1 = vnorm(vcross(d, helper))
            p2 = vnorm(vcross(d, p1))
            rng = SeededRandom(int(spec.get("seed", 1)))
            verts = []
            for i in range(seg + 1):
                base = vlerp(frm, to, i / seg)
                if i in (0, seg):
                    verts.append(base)
                else:
                    a = rng.next_double(-amp, amp)
                    b = rng.next_double(-amp, amp)
                    verts.append(vadd(vadd(base, vmul(p1, a)), vmul(p2, b)))
            self.verts = verts
        elif k == "polygon":
            sides, skip = int(spec["sides"]), int(spec.get("skip", 1))
            r = float(spec["radius"])
            rot = float(spec.get("rotation_deg", 0.0))
            edges = sides // math.gcd(sides, skip)
            self.verts = []
            for j in range(edges + 1):
                a = math.radians(rot + 360.0 * (j * skip) / sides)
                self.verts.append([r * math.sin(a), 0.0, r * math.cos(a)])
        elif k not in ("line", "arc", "circle", "helix", "spiral", "sphere", "bezier"):
            raise ValueError("unknown formation curve '%s'" % k)

    @property
    def closed(self):
        k, s = self.kind, self.spec
        if k == "circle" or k == "polygon":
            return True
        if k == "arc":
            f, t = float(s["from_deg"]), float(s["to_deg"])
            return t != f and abs(t - f) % 360.0 == 0.0
        return False

    def is_sphere(self):
        return self.kind == "sphere"

    def distribute(self, count):
        if self.closed:
            return [i / count for i in range(count)]
        if count == 1:
            return [0.5]
        return [i / (count - 1) for i in range(count)]

    def _sphere_at(self, i):
        r0 = float(self.spec["radius"])
        y = 1.0 - 2.0 * (i + 0.5) / self.count
        r = math.sqrt(max(0.0, 1.0 - y * y))
        phi = i * GOLDEN
        return [r0 * r * math.cos(phi), r0 * y, r0 * r * math.sin(phi)]

    def _sphere_index(self, t):
        # Kotlin roundToInt: half away from zero (values here are >= 0)
        return min(max(int(math.floor(min(1.0, max(0.0, t)) * (self.count - 1) + 0.5)), 0), self.count - 1)

    def point(self, t):
        k, s = self.kind, self.spec
        u = min(1.0, max(0.0, t))
        if k == "line":
            return vlerp([float(x) for x in s["from"]], [float(x) for x in s["to"]], u)
        if k in ("arc", "circle"):
            f, to = (0.0, 360.0) if k == "circle" else (float(s["from_deg"]), float(s["to_deg"]))
            a = math.radians(f + (to - f) * u)
            r = float(s["radius"])
            return [r * math.sin(a), 0.0, r * math.cos(a)]
        if k == "helix":
            a = math.radians(float(s.get("start_deg", 0.0)) + 360.0 * float(s["turns"]) * u)
            r = float(s["radius"])
            return [r * math.sin(a), float(s["height"]) * u, r * math.cos(a)]
        if k == "spiral":
            r = float(s["start_radius"]) + (float(s["end_radius"]) - float(s["start_radius"])) * u
            a = math.radians(float(s.get("start_deg", 0.0)) + 360.0 * float(s["turns"]) * u)
            return [r * math.sin(a), 0.0, r * math.cos(a)]
        if k == "sphere":
            return self._sphere_at(self._sphere_index(t))
        if k in ("polygon", "lightning"):
            return _polyline_point(self.verts, t)
        raise ValueError("formation curve '%s' is not supported" % k)

    def tangent(self, t):
        k, s = self.kind, self.spec
        u = min(1.0, max(0.0, t))
        if k == "line":
            return vnorm(vsub([float(x) for x in s["to"]], [float(x) for x in s["from"]]))
        if k in ("arc", "circle"):
            f, to = (0.0, 360.0) if k == "circle" else (float(s["from_deg"]), float(s["to_deg"]))
            a = math.radians(f + (to - f) * u)
            d = 1.0 if to >= f else -1.0
            sr = 1.0 if float(s["radius"]) >= 0.0 else -1.0
            return vnorm([math.cos(a) * d * sr, 0.0, -math.sin(a) * d * sr])
        if k == "helix":
            a = math.radians(float(s.get("start_deg", 0.0)) + 360.0 * float(s["turns"]) * u)
            w = 2.0 * math.pi * float(s["turns"])
            r = float(s["radius"])
            return vnorm([r * math.cos(a) * w, float(s["height"]), -r * math.sin(a) * w])
        if k == "spiral":
            sr, er = float(s["start_radius"]), float(s["end_radius"])
            r = sr + (er - sr) * u
            dr = er - sr
            a = math.radians(float(s.get("start_deg", 0.0)) + 360.0 * float(s["turns"]) * u)
            w = 2.0 * math.pi * float(s["turns"])
            return vnorm([dr * math.sin(a) + r * math.cos(a) * w, 0.0, dr * math.cos(a) - r * math.sin(a) * w])
        if k == "sphere":
            if self.count == 1:
                return [0.0, 0.0, 0.0]
            i = self._sphere_index(t)
            if i < self.count - 1:
                return vnorm(vsub(self._sphere_at(i + 1), self._sphere_at(i)))
            return vnorm(vsub(self._sphere_at(i), self._sphere_at(i - 1)))
        if k in ("polygon", "lightning"):
            return _polyline_tangent(self.verts, t)
        raise ValueError("formation curve '%s' is not supported" % k)


def slot_tables(el):
    """[(position, rotation quaternion)] per formation slot index (v1 slot order, before `reverse`)."""
    count = int(el.get("count", 1))
    fo = el.get("formation")
    if not fo:
        return [([0.0, 0.0, 0.0], IDENT)] * count
    curve = Curve(fo["curve"], count)
    tilt_d = fo.get("tilt") or {}
    tilt = euler_to_quat(float(tilt_d.get("yaw", 0.0)), float(tilt_d.get("pitch", 0.0)), float(tilt_d.get("roll", 0.0)))
    off = [float(x) for x in fo.get("offset", [0.0, 0.0, 0.0])]
    align = fo.get("align", "none")
    out = []
    for t in curve.distribute(count):
        p = curve.point(t)
        pos = vadd(off, qrot(tilt, p))
        if align == "tangent":
            rot = qnorm(_q_mul(tilt, look_rotation(curve.tangent(t))))
        elif align == "outward":
            flat = [p[0], 0.0, p[2]]
            out_v = p if (curve.is_sphere() or vlen2(flat) <= 1e-9) else flat
            rot = qnorm(_q_mul(tilt, look_rotation(out_v)))
        elif align == "anchor":
            rot = tilt
        elif align == "none":
            rot = IDENT
        else:
            raise ValueError("formation align '%s' is not supported" % align)
        out.append((pos, rot))
    return out


# ------------------------------------------------------------------ keyframe sampling (v1 carry-forward semantics)
def _vec3(v, what):
    if isinstance(v, (int, float)):
        return [float(v)] * 3
    nums = [x for x in v]
    if len(nums) != 3 or not all(isinstance(x, (int, float)) for x in nums):
        raise ValueError("%s %r is not a number triple" % (what, v))
    return [float(x) for x in nums]


def sample(kfs, ch, default, u, what=None):
    """Channel `ch` at tick `u`. With a [default] the keys carry forward from it exactly like v1 (VfxTimeline.resolveKeyframes /
    resolveGroup: a key that omits a channel keeps the previous key's value, the first one starts at identity, and every key's `ease`
    shapes the segment that ends at it); without one (alpha / colour) only the keys that name the channel count."""
    if default is not None:
        cur = default
        pts = []
        for k in sorted(kfs, key=lambda k: k["t"]):
            cur = k[ch] if ch in k else cur
            pts.append((int(k["t"]), cur, k.get("ease")))
    else:
        pts = [(int(k["t"]), k[ch], k.get("ease")) for k in sorted(kfs, key=lambda k: k["t"]) if ch in k]
    if not pts:
        return default
    scalar = isinstance(pts[0][1], (int, float))
    conv = (lambda v: float(v)) if scalar else (lambda v: _vec3(v, ch))
    if u <= pts[0][0]:
        return conv(pts[0][1])
    if u >= pts[-1][0]:
        return conv(pts[-1][1])
    for (t0, v0, _), (t1, v1, e1) in zip(pts, pts[1:]):
        if t0 <= u <= t1:
            f = ease(e1, (u - t0) / float(t1 - t0)) if t1 > t0 else 1.0
            a, b = conv(v0), conv(v1)
            return a + (b - a) * f if scalar else vlerp(a, b, f)
    return conv(pts[-1][1])


def key_times(kfs):
    return sorted({int(k["t"]) for k in kfs})


# ------------------------------------------------------------------ CEL text
def num(x):
    s = repr(round(float(x), 5))
    if s in ("-0.0",):
        s = "0.0"
    if "." not in s and "e" not in s and "n" not in s:
        s += ".0"
    return s


def cel_vec(v):
    return "vec(%s, %s, %s)" % (num(v[0]), num(v[1]), num(v[2]))


def cel_table(rows):
    """`= [vec(..), ..][int(i)]` (or the plain list when every row is equal)."""
    if all(r == rows[0] for r in rows):
        return [round(float(x), 5) + 0.0 for x in rows[0]]
    return "= [" + ", ".join(cel_vec(r) for r in rows) + "][int(i)]"


def rv(q):
    """Rotation vector (degrees) of a quaternion, -0 cleaned."""
    return [0.0 if abs(c) < 1e-9 else c for c in quat_to_vec(q)]


def clean(v):
    return [round(float(x), 5) + 0.0 for x in v]


# ------------------------------------------------------------------ keys
def op_keys(kfs, extra=None, ticks=None):
    """v1 keyframes -> v2 track keys. Channels: translate -> offset, rotate -> turn (densified), scale, alpha, color, block, text, ease.
    `extra` overrides the sampled channels per tick: {tick: {"offset"|"scale": vec}} (oscillate / group-scale bakes)."""
    ks = []
    for k in kfs:
        e = {"t": int(k["t"])}
        if "translate" in k:
            e["offset"] = [float(x) for x in k["translate"]]
        if "rotate" in k:
            e["rotation"] = [float(x) for x in k["rotate"]]
        if "scale" in k:
            e["scale"] = _vec3(k["scale"], "scale")
        if "alpha" in k:
            e["alpha"] = float(k["alpha"])
        if "color" in k:
            e["color"] = k["color"]
        if "block" in k:
            e["block"] = k["block"]
        if "text" in k:
            e["text"] = k["text"]
        if "ease" in k:
            ek = ease_key(str(k["ease"]))
            if ek is None:
                raise ValueError("unknown ease '%s'" % k["ease"])
            e["ease"] = ek
        ks.append(e)
    if extra:
        merged = {e["t"]: e for e in ks}
        for t, ch in extra.items():
            e = merged.setdefault(t, {"t": t})
            e.update(ch)
        ks = [merged[t] for t in sorted(merged)]
    out = convert_keys(ks)
    for e in out:
        for ch in ("offset", "scale", "turn"):
            if ch in e:
                e[ch] = clean(e[ch])
    return out


def with_initial_look(keys, alpha0, color0):
    """A track key sets alpha / colour only from its tick on; the op's own value covers the ticks before the first key."""
    out = list(keys)
    for ch, v in (("alpha", alpha0), ("color", color0)):
        firsts = [k for k in out if ch in k]
        if v is not None and firsts and firsts[0]["t"] > 0 and not any(k["t"] == 0 and ch in k for k in out):
            z = next((k for k in out if k["t"] == 0), None)
            if z is None:
                z = {"t": 0}
                out.insert(0, z)
            z[ch] = v
    return out


# ------------------------------------------------------------------ oscillate / group bake
def oscillate(kfs, osc, length):
    """Dense per-tick scale / offset keys with v1's `amplitude * cos(2 pi u / period)` added after keyframe sampling."""
    axis = osc["axis"]
    amp = float(osc["amplitude"])
    per = float(osc["period_ticks"])
    extra = {}
    for u in range(0, length + 1):
        d = amp * math.cos(2.0 * math.pi * u / per)
        if axis.startswith("scale"):
            sc = list(sample(kfs, "scale", [1.0, 1.0, 1.0], u))
            for ax in axis[len("scale_"):] if axis != "scale_xyz" else "xyz":
                sc["xyz".index(ax)] += d
            extra.setdefault(u, {})["scale"] = sc
        elif axis.startswith("translate"):
            off = list(sample(kfs, "translate", [0.0, 0.0, 0.0], u))
            off["xyz".index(axis[-1])] += d
            extra.setdefault(u, {})["offset"] = off
        else:
            raise ValueError("oscillate axis '%s' is not supported" % axis)
    return extra


def group_state(gkfs, u):
    t = sample(gkfs, "translate", [0.0, 0.0, 0.0], u)
    r = sample(gkfs, "rotate", [0.0, 0.0, 0.0], u)
    s = sample(gkfs, "scale", 1.0, u)
    return t, r, s


# ------------------------------------------------------------------ emit elements
def curve_at(keys, f, default):
    pts = sorted(((float(k["t"]), k["v"]) for k in keys), key=lambda p: p[0])
    if not pts:
        return default
    if f <= pts[0][0]:
        return pts[0][1]
    if f >= pts[-1][0]:
        return pts[-1][1]
    for (t0, v0), (t1, v1) in zip(pts, pts[1:]):
        if t0 <= f <= t1:
            g = (f - t0) / (t1 - t0) if t1 > t0 else 1.0
            if isinstance(v0, (int, float)):
                return v0 + (v1 - v0) * g
            return [v0, v1, g]
    return pts[-1][1]


def hex_rgb(c):
    c = c.lstrip("#")
    return [int(c[i:i + 2], 16) for i in (0, 2, 4)]


def rgb_hex(v):
    return "#%02x%02x%02x" % tuple(min(255, max(0, int(round(x)))) for x in v)


def emit_direction(em, slot, count):
    d = em.get("direction", "up")
    if d == "forward":
        base = [0.0, 0.0, 1.0]
    elif d == "up":
        base = [0.0, 1.0, 0.0]
    elif d == "tangent":
        base = [1.0, 0.0, 0.0]
    elif d == "outward":
        y = 1.0 - 2.0 * (slot + 0.5) / max(1, count)
        r = math.sqrt(max(0.0, 1.0 - y * y))
        return [r * math.cos(slot * GOLDEN), y, r * math.sin(slot * GOLDEN)]
    elif isinstance(d, list):
        base = vnorm([float(x) for x in d])
    else:
        raise ValueError("emit direction %r is not supported" % (d,))
    spread = float(em.get("spread_deg", 0.0))
    if spread <= 0.0:
        return base
    cos_max = math.cos(math.radians(spread))
    frac = ((slot * 0.6180339887) % 1.0)
    cos_t = cos_max + (1.0 - cos_max) * (1.0 - frac)
    sin_t = math.sqrt(max(0.0, 1.0 - cos_t * cos_t))
    phi = slot * GOLDEN
    helper = [0.0, 1.0, 0.0] if abs(base[1]) < 0.99 else [1.0, 0.0, 0.0]
    u = vnorm(vcross(base, helper))
    w = vcross(base, u)
    return vadd(vadd(vmul(base, cos_t), vmul(u, sin_t * math.cos(phi))), vmul(w, sin_t * math.sin(phi)))


def mid(rng, default=None):
    if rng is None:
        return default
    if isinstance(rng, (int, float)):
        return float(rng)
    return (float(rng[0]) + float(rng[1])) / 2.0


def emit_quads(c, el, fid, where, delay, when, dur, removes):
    """A v1 display-particle emitter -> one baked quad per particle with dense ballistic track keys (random draws become a
    deterministic spread; that is the approved difference)."""
    em = el["emit"]
    kind = el["kind"]
    if kind["type"] != "quad":
        c.present("emit element of kind %s (only quad emitters are converted)" % kind["type"])
        return
    for k in em:
        if k not in ("count", "rate_per_tick", "speed", "spread_deg", "direction", "gravity", "drag", "life_ticks", "size",
                     "size_over_life", "color_over_life", "alpha_over_life", "spin_over_life", "start_frame", "frame_ticks"):
            c.present("emit element field %s" % k)
    count = int(em["count"])
    start = int(el.get("start_tick", 0))
    origin = [0.0, 0.0, 0.0]
    for k in el.get("keyframes", []):
        if "translate" in k:
            origin = [float(x) for x in k["translate"]]
            break
    rate = em.get("rate_per_tick")
    g = float(em.get("gravity", 0.0))
    drag = float(em.get("drag", 0.0))
    life0 = int(round(mid(em.get("life_ticks", 10))))
    speed0 = mid(em.get("speed", 0.0))
    size0 = mid(em.get("size", 0.1))
    spin = em.get("spin_over_life") or em.get("spin_deg_per_tick")
    base_color = kind.get("color", "#ffffff")
    base_alpha = float(kind.get("alpha", 1.0))
    glyph = kind.get("glyph")
    for slot in range(count):
        spawn = start + (int(slot / rate) if rate else 0)
        if spawn >= dur:
            continue
        life = min(dur, spawn + life0) - spawn
        anim = life - 1
        if anim <= 0:
            continue
        d = emit_direction(em, slot, count)
        vel = vmul(d, speed0)
        pos = list(origin)
        keys = []
        spin_deg = 0.0
        sign = -1.0 if slot % 2 == 0 else 1.0
        for u in range(0, anim + 1):
            f = u / float(anim)
            e = {"t": u}
            e["offset"] = clean(pos)
            sz = size0 * curve_at(em.get("size_over_life", []), f, 1.0)
            e["scale"] = [sz, sz, 1.0]
            if em.get("alpha_over_life"):
                e["alpha"] = round(float(curve_at(em["alpha_over_life"], f, base_alpha)), 5)
            if em.get("color_over_life"):
                pts = em["color_over_life"]
                v = curve_at([{"t": p["t"], "v": 0.0} for p in pts], f, 0.0)
                # piecewise rgb lerp between hex keys
                col = base_color
                kp = sorted(pts, key=lambda p: p["t"])
                if f <= kp[0]["t"]:
                    col = kp[0]["v"]
                elif f >= kp[-1]["t"]:
                    col = kp[-1]["v"]
                else:
                    for a, b in zip(kp, kp[1:]):
                        if a["t"] <= f <= b["t"]:
                            gg = (f - a["t"]) / (b["t"] - a["t"]) if b["t"] > a["t"] else 1.0
                            col = rgb_hex(vlerp(hex_rgb(a["v"]), hex_rgb(b["v"]), gg))
                            break
                e["color"] = col
            if spin:
                if isinstance(spin, list):
                    spin_deg += float(curve_at(spin, (u + 0.5) / anim, 0.0))
                else:
                    spin_deg += float(spin)
                e["turn"] = [0.0, 0.0, round(sign * spin_deg, 5) + 0.0]
            keys.append(e)
            vel = vmul([vel[0], vel[1] - g, vel[2]], 1.0 - drag)
            pos = vadd(pos, vel)
        eid = "%s_%s_%d" % (fid, sid(el["id"]), slot)
        face = {"viewer": "center"}
        if el.get("billboard") not in (None, "center"):
            face = {"plane": {"frame": fid, "axes": ["left", "up"]}}
        op = {"id": eid, "op": "quad", "parent": fid, "face": face, "size": [1.0, 1.0], "color": base_color,
              "alpha": round(float(em["alpha_over_life"][0]["v"]) if em.get("alpha_over_life") else base_alpha, 5)}
        if em.get("color_over_life"):
            op["color"] = sorted(em["color_over_life"], key=lambda p: p["t"])[0]["v"]
        if glyph:
            op["text"] = glyph
        if kind.get("see_through"):
            op["see_through"] = True
        if "brightness" in el:
            op["brightness"] = int(el["brightness"])
        at = delay + spawn
        c.p(where, at, c._w(op, when))
        c.p(where, at, c._w({"id": eid + "_trk", "op": "track", "target": eid, "keys": keys_shift(keys), "sample_ticks": c.vfx_sample}, when))
        removes.setdefault(spawn + life, []).append(eid)


def keys_shift(keys):
    """The clock of a track starts at the op; v1's animation clock starts one tick after spawn, the engine lead covers that."""
    return keys


# ------------------------------------------------------------------ display elements
UNSUPPORTED = ("glow_color", "respect_hit_stop", "view_range")


def argb_split(c):
    """v1 `#AARRGGBB` (or `#RRGGBB`) -> (`#rrggbb`, alpha 0..1)."""
    h = str(c).lstrip("#")
    if len(h) == 8:
        return "#" + h[2:].lower(), round(int(h[:2], 16) / 255.0, 3)
    return "#" + h.lower(), 1.0


def hexc(col):
    if isinstance(col, dict):
        return "#%02x%02x%02x" % (int(col.get("r", 255)), int(col.get("g", 255)), int(col.get("b", 255)))
    return col


def cel_rows(rows):
    """CEL expression text (no leading '=') for a per-copy vec table."""
    if all(r == rows[0] for r in rows):
        return cel_vec(rows[0])
    return "[" + ", ".join(cel_vec(r) for r in rows) + "][int(i)]"


def hex_alpha(argb):
    """'#AARRGGBB' (v1 text background) -> ('#rrggbb', alpha 0..1)."""
    h = str(argb).lstrip("#")
    if len(h) == 8:
        return "#" + h[2:].lower(), round(int(h[:2], 16) / 255.0, 3)
    return "#" + h.lower(), 1.0


def base_fields(kind, el, c, fid):
    """The kind-specific op skeleton (without id / placement)."""
    kt = kind["type"]
    bb = el.get("billboard")
    face = {"viewer": "center"} if bb == "center" else {"viewer": "vertical"} if bb == "vertical" \
        else {"plane": {"frame": fid, "axes": ["left", "up"]}}
    if kt == "block":
        return {"op": "block", "parent": fid, "block": kind["block"], "size": [1.0, 1.0, 1.0]}
    if kt == "quad":
        op = {"op": "quad", "parent": fid, "face": face, "size": [1.0, 1.0], "color": hexc(kind["color"]), "alpha": float(kind.get("alpha", 1.0))}
        if kind.get("glyph"):
            op["text"] = kind["glyph"]
        if kind.get("see_through"):
            op["see_through"] = True
        return op
    if kt == "sprite":
        op = {"op": "plate", "parent": fid, "face": face, "sprite": kind["sprite"], "size": c.sprite_size(kind["sprite"])}
        if "alpha" in kind:
            op["alpha"] = kind["alpha"]
        if kind.get("frame_ticks", 1) != 1:
            op["frame_ticks"] = int(kind["frame_ticks"])
        if kind.get("loop"):
            op["loop"] = True
        if "color" in kind:
            op["color"] = hexc(kind["color"])
        if kind.get("see_through"):
            op["see_through"] = True
        return op
    if kt == "text":
        # blocks per text unit: v1 scale 1 = 0.2 blocks (8 px * 0.025), so v1 scale keys carry over unchanged
        op = {"op": "text", "parent": fid, "face": face, "size": [0.2, 0.2], "text": kind["text"]}
        bg = kind.get("background")
        if bg:
            rgb, alpha = argb_split(bg)
            if alpha > 0:
                op["background"] = rgb
                op["background_alpha"] = alpha
        if kind.get("shadow"):
            op["shadow"] = True
        if kind.get("see_through"):
            op["see_through"] = True
        return op
    if kt == "text":
        # v1 VfxPlayer.displayKind VfxElementKind.Text: DisplayKind.Text(text, backgroundArgb, shadow, seeThrough); one text unit is
        # the 8 px vanilla font = 0.2 blocks, so size 0.2 puts the keyframe scale on vanilla's own text scale.
        rgb, a = hex_alpha(kind.get("background", "#40000000"))
        op = {"op": "quad", "parent": fid, "face": face, "size": [0.2, 0.2], "color": rgb, "alpha": a, "text": kind["text"]}
        if kind.get("shadow"):
            op["shadow"] = True
        if kind.get("see_through"):
            op["see_through"] = True
        return op
    c.present("element kind %s has no v2 op" % kt, str(kind)[:80])
    return None


def display_element(c, el, fid, where, delay, when, dur, removes):
    kind = el["kind"]
    for k in UNSUPPORTED:
        if k in el:
            c.present("element %s (no v2 equivalent)" % k, str(el[k])[:60])
    if "emit" in el:
        for k in ("lane", "target_track", "target_line"):
            if el.get(k) not in (None, "core"):
                c.present("emit element %s (an emitter has no per-copy lane / pull)" % k, str(el[k])[:60])
        emit_quads(c, el, fid, where, delay, when, dur, removes)
        return
    skel = base_fields(kind, el, c, fid)
    if skel is None:
        return
    if el.get("lane", "core") != "core":
        skel["lane"] = el["lane"]
    for k in kind:
        if k not in ("type", "block", "color", "alpha", "glyph", "see_through", "sprite", "frame_ticks", "loop", "text", "background", "shadow"):
            c.present("element kind field %s" % k)
    eid = c.oid("%s_%s" % (fid, sid(el["id"])))
    start = int(el.get("start_tick", 0))
    end = int(el.get("end_tick", dur))
    n = int(el.get("count", 1))
    fo = el.get("formation") or {}
    stagger = int(fo.get("stagger_ticks", 0))
    reverse = bool(fo.get("reverse", False))
    slot_life = el.get("slot_lifetime_ticks")
    kfs = el.get("keyframes", [])
    gkfs = el.get("group_keyframes")
    jit = el.get("jitter")
    osc = el.get("oscillate")
    for kf in kfs:
        if "text" in kf and skel["op"] != "text":
            c.present("keyframe text swap on a non-text element")
    try:
        slots = slot_tables(el)
    except ValueError as e:
        c.present(str(e))
        return
    order = [(n - 1 - k if reverse else k) for k in range(n)]
    rows = [slots[s] for s in order]
    if "brightness" in el:
        skel["brightness"] = int(el["brightness"])
    spawns = [start + k * stagger for k in range(n)]

    def life_of(k):
        return (min(dur, spawns[k] + int(slot_life)) if slot_life else min(dur, end)) - spawns[k]

    live = [k for k in range(n) if spawns[k] < dur and life_of(k) > 0]
    if not live:
        return
    base_alpha = skel.get("alpha")
    base_color = skel.get("color") if skel["op"] == "quad" else None

    if gkfs:
        if jit:
            c.present("jitter together with group_keyframes")
        group_slots(c, el, skel, eid, where, delay, when, rows, spawns, life_of, live, kfs, gkfs, osc, removes)
        return

    length = max(life_of(k) for k in live)
    extra = oscillate(kfs, osc, length) if osc else None
    keys = op_keys(kfs, extra) if (kfs or extra) else []
    keys = with_initial_look(keys, base_alpha, base_color)
    keys = [k for k in keys if len(k) > 1]
    single = n == 1
    pos_e = cel_rows([r[0] for r in rows])
    rot_e = cel_rows([rv(r[1]) for r in rows])
    # v1 seeds jitter from actor.hashCode() + the tick (VfxTimeline.kt:177-214), so it is not reproducible even in v1:
    # a per-element random stream with the same distribution is the faithful mapping.
    stream = "j_" + eid
    dyn_at = (not single and "[int(i)]" in pos_e) or bool(jit)
    dyn_turn = (not single and "[int(i)]" in rot_e) or bool(jit and float(jit.get("rotation_deg", 0.0)))
    if single:
        pos_e = pos_e.replace("[int(i)]", "[0]")
    if jit:
        p = float(jit.get("position", 0.0))
        r = float(jit.get("rotation_deg", 0.0))
        if p:
            pos_e = "(%s) + vec((rand('%s')*2.0-1.0)*%s, (rand('%s')*2.0-1.0)*%s, (rand('%s')*2.0-1.0)*%s)" % (pos_e, stream, num(p), stream, num(p), stream, num(p))
        if r:
            rot_e = "(%s) + vec((rand('%s')*2.0-1.0)*%s, (rand('%s')*2.0-1.0)*%s, (rand('%s')*2.0-1.0)*%s)" % (rot_e, stream, num(r), stream, num(r), stream, num(r))
        sc = float(jit.get("scale", 0.0))
        if sc:
            unit = skel["size"]
            skel["size"] = "= %s * (1.0 + (rand('%s')*2.0-1.0)*%s)" % (cel_vec(unit if len(unit) == 3 else unit + [1.0]), stream, num(sc))
    skel["at"] = ("= " + pos_e) if dyn_at else clean(rows[0][0])
    skel["turn"] = ("= " + rot_e) if dyn_turn else clean(rv(rows[0][1]))
    if skel["at"] == [0.0, 0.0, 0.0]:
        del skel["at"]
    if skel["turn"] == [0.0, 0.0, 0.0]:
        del skel["turn"]
    if not single:
        skel["times"] = n
        if stagger:
            skel["every"] = stagger
        if slot_life:
            skel["life"] = int(slot_life)
    op = dict(skel)
    op["id"] = eid
    at = delay + start
    c.pull(el, eid, skel["op"], where, at, when)
    c.p(where, at, c._w(op, when))
    if keys:
        if stagger and not single:
            for k in live:
                tk = {"id": "%s_trk%d" % (eid, k), "op": "track", "target": "%s#%d" % (eid, k), "keys": keys, "sample_ticks": c.vfx_sample}
                c.p(where, delay + spawns[k], c._w(tk, when))
        else:
            c.p(where, at, c._w({"id": eid + "_trk", "op": "track", "target": eid, "keys": keys, "sample_ticks": c.vfx_sample}, when))
    last = max(spawns[k] + life_of(k) for k in live)
    if slot_life and not single:
        if last > dur:
            removes.setdefault(dur, []).append(eid)
    else:
        removes.setdefault(min(dur, end), []).append(eid)


def group_slots(c, el, skel, eid, where, delay, when, rows, spawns, life_of, live, kfs, gkfs, osc, removes):
    """group_keyframes: one op per slot with absolute dense track keys (the group moves the whole formation)."""
    length = max(life_of(k) for k in live)
    extra = oscillate(kfs, osc, length) if osc else None
    side = op_keys(kfs, extra) if (kfs or extra) else []
    side = with_initial_look(side, skel.get("alpha"), skel.get("color") if skel["op"] == "quad" else None)
    other = {k["t"]: {x: v for x, v in k.items() if x in ("alpha", "color", "block", "text")} for k in side}
    other = {t: v for t, v in other.items() if v}
    for k in live:
        sp, rs = rows[k]
        life = life_of(k)
        keys = []
        for u in range(0, life):
            lt = sample(kfs, "translate", [0.0, 0.0, 0.0], u)
            lr = sample(kfs, "rotate", [0.0, 0.0, 0.0], u)
            ls = sample(kfs, "scale", [1.0, 1.0, 1.0], u)
            if extra and u in extra:
                if "offset" in extra[u]:
                    lt = extra[u]["offset"]
                if "scale" in extra[u]:
                    ls = extra[u]["scale"]
            gt, gr, gs = group_state(gkfs, u)
            rg = euler_to_quat(*gr)
            rsl = _q_mul(rg, rs)
            pos = vadd(gt, vadd(qrot(rg, vmul(sp, gs)), qrot(rsl, vmul(lt, gs))))
            rot = qnorm(_q_mul(rsl, euler_to_quat(*lr)))
            e = {"t": u, "offset": clean(pos), "turn": clean(rv(rot)), "scale": clean(vmul(ls, gs))}
            e.update(other.get(u, {}))
            keys.append(e)
        have = {e["t"] for e in keys}
        for t, v in other.items():
            if t < life and t not in have:
                keys.append({"t": t, **v})
        keys.sort(key=lambda e: e["t"])
        op = dict(skel)
        op["id"] = "%s_s%d" % (eid, k)
        c.pull(el, op["id"], skel["op"], where, delay + spawns[k], when)
        c.p(where, delay + spawns[k], c._w(op, when))
        c.p(where, delay + spawns[k], c._w({"id": op["id"] + "_trk", "op": "track", "target": op["id"], "keys": keys, "sample_ticks": c.vfx_sample}, when))
        removes.setdefault(spawns[k] + life, []).append(op["id"])


def _mul(v, s):
    return [float(x) * s for x in v]


def _mul(v, s):
    return [float(x) * s for x in v]


def scale_rig(r, s):
    """v1 `play_vfx scale` on a rig that holds the hand pose itself: a scaled copy of its pivot, part offsets / sizes, trails, boards,
    emitter points and weapon points (VfxRigPlanner partPlan :255, pivotWorld :170)."""
    r = copy.deepcopy(r)
    r["pivot"] = _mul(r["pivot"], s)
    for p in r.get("parts", []):
        p["offset"], p["size"] = _mul(p["offset"], s), _mul(p["size"], s)
    for t in r.get("trails", []):
        t["from"], t["to"] = _mul(t["from"], s), _mul(t["to"], s)
    for b in r.get("boards", []):
        b["from"], b["to"] = _mul(b["from"], s), _mul(b["to"], s)
    for e in r.get("emitters", []):
        e["point"] = _mul(e["point"], s)
    for k in ("weapon_tip", "weapon_edge", "weapon_root"):
        if r.get(k) is not None:
            r[k] = _mul(r[k], s)
    return r


def resolve_palette(d):
    """ADR-0290 palette: the plain vfx id plays the first variant, so `$name` colours resolve to it (no stage selects another)."""
    pal = d.get("palette")
    if not pal:
        return d
    first = {name: next(iter(v.values())) for name, v in pal.items()}

    def walk(x):
        if isinstance(x, dict):
            return {k: walk(v) for k, v in x.items() if k != "palette"}
        if isinstance(x, list):
            return [walk(v) for v in x]
        if isinstance(x, str) and x.startswith("$") and x[1:] in first:
            return first[x[1:]]
        return x
    return walk(d)
