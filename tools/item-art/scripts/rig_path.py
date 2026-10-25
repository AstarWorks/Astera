#!/usr/bin/env python3
"""Blade path of a VFX weapon rig, read from the content YAML (no hardcoded keys): reusable by any particle / sprite
generator that wants to attach to a swinging weapon.

What it reads
  * the rig file  content/vfx/<rig_vfx>.yaml   (rigs[]: pivot, rest, tilt, start_tick, swing keys, parts, respect_hit_stop,
    sample_ticks)
  * the skill file content/skills/<skill>.yaml (the cast steps: the play_vfx that plays the rig, its `freeze` windows and
    delay, the play_vfx that plays the fx file you are writing particles into, and every dash_strike that moves the caster)

What it reproduces (same maths as the runtime, plugin/core/.../vfx/VfxRigPlanner.kt `VfxRigMotion`)
  * R(t) = tilt * euler(swing(t)) * rest, euler = Minecraft yaw * pitch * roll (Quaternion.fromEulerDegrees);
  * swing keys interpolated component-wise with the key's ease, cut into chunks of <= sample_ticks ticks, halved while a
    chunk turns > 60 deg, each chunk slerped (= what the client shows between updates);
  * rig tick l is reached at effect tick start_tick + 1 + l + frozen ticks before it (hit-stop freezes stop the clock);
  * the caster's dash_strike displacement (one teleport per tick, the first in the cast tick: DashStrikeRunner; capped by
    stop_before_target at the capture distance; a play_vfx in the dash's last tick is assumed to spawn after that step),
    because a follow / attach_to_anchor rig rides the caster while a follow: false fx file stays where it was spawned.

Frame of every returned point: the fx file's anchor frame (x = left, y = up, z = forward, origin = caster feet at the
moment the fx file was spawned), i.e. exactly what a particle element's `translate` means in that fx file.

    from rig_path import RigPath
    rp = RigPath("bastion-greatsword-s1", "bastion-s1-rig", fx_vfx="bastion-s1-fx-high")
    rp.blade_point(fx_tick=4.0, frac=1.0)       # blade tip at fx tick 4
    rp.contact_fx_ticks()                        # fx ticks of the freeze windows (= contacts) inside this fx file

CLI (prints the per-tick tip path of an fx file's window, for briefs and evidence):
    python3 tools/item-art/scripts/rig_path.py bastion-greatsword-s1 bastion-s1-rig bastion-s1-fx-high
"""
import math
import pathlib
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[3]
ANIMATION_DELAY_TICKS = 1  # VfxTimeline.ANIMATION_DELAY_TICKS
MAX_STEP_DEG = 60.0        # VfxRigMotion.MAX_STEP_DEG
CAPTURE_DISTANCE = 4.0     # tools/vfx-shot DISTANCE default (caster -> target), caps dash_strike via stop_before_target


# ---- quaternion (w, x, y, z) helpers, conventions of core/domain/geometry/math/Quaternion.kt ----------------------
def qmul(a, b):
    aw, ax, ay, az = a
    bw, bx, by, bz = b
    return (aw * bw - ax * bx - ay * by - az * bz,
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw)


def qaxis(axis, rad):
    s = math.sin(rad / 2)
    return (math.cos(rad / 2), axis[0] * s, axis[1] * s, axis[2] * s)


def qnorm(q):
    n = math.sqrt(sum(c * c for c in q))
    return tuple(c / n for c in q)


def qeuler(yaw, pitch, roll):
    """Quaternion.fromEulerDegrees: yaw about +Y (negated), pitch about X, roll about Z, composed yaw * pitch * roll."""
    return qnorm(qmul(qmul(qaxis((0, 1, 0), math.radians(-yaw)), qaxis((1, 0, 0), math.radians(pitch))),
                      qaxis((0, 0, 1), math.radians(roll))))


def qrot(q, v):
    w, x, y, z = q
    tx, ty, tz = 2 * (y * v[2] - z * v[1]), 2 * (z * v[0] - x * v[2]), 2 * (x * v[1] - y * v[0])
    return (v[0] + w * tx + (y * tz - z * ty), v[1] + w * ty + (z * tx - x * tz), v[2] + w * tz + (x * ty - y * tx))


def qangle_deg(a, b):
    d = abs(sum(x * y for x, y in zip(a, b)))
    return math.degrees(2 * math.acos(min(1.0, d)))


def qslerp(a, b, t):
    d = sum(x * y for x, y in zip(a, b))
    if d < 0:
        d, b = -d, tuple(-c for c in b)
    if d > 0.9995:
        return qnorm(tuple(x + (y - x) * t for x, y in zip(a, b)))
    th = math.acos(max(-1.0, min(1.0, d)))
    s = math.sin(th)
    wa, wb = math.sin((1 - t) * th) / s, math.sin(t * th) / s
    return tuple(x * wa + y * wb for x, y in zip(a, b))


# ---- easing (core/domain/animation/Easing.kt; the YAML names drop the EASE_ prefix, lower case) -------------------
def _ease(name):
    n = (name or "linear").lower().replace("ease_", "")
    pw = {"quad": 2, "cubic": 3, "quart": 4, "quint": 5}
    if n == "linear":
        return lambda t: t
    kind, _, fam = n.partition("_") if not n.startswith("in_out_") else ("in_out", None, n[len("in_out_"):])
    if fam in pw:
        p = pw[fam]
        return {"in": lambda t: t ** p, "out": lambda t: 1 - (1 - t) ** p,
                "in_out": lambda t: (2 ** (p - 1)) * t ** p if t < 0.5 else 1 - (-2 * t + 2) ** p / 2}[kind]
    if fam == "sine":
        return {"in": lambda t: 1 - math.cos(t * math.pi / 2), "out": lambda t: math.sin(t * math.pi / 2),
                "in_out": lambda t: -(math.cos(math.pi * t) - 1) / 2}[kind]
    if fam == "expo":
        return {"in": lambda t: 0.0 if t == 0 else 2 ** (10 * t - 10),
                "out": lambda t: 1.0 if t == 1 else 1 - 2 ** (-10 * t),
                "in_out": lambda t: t if t in (0, 1) else (2 ** (20 * t - 10) / 2 if t < 0.5 else (2 - 2 ** (-20 * t + 10)) / 2)}[kind]
    if fam == "circ":
        return {"in": lambda t: 1 - math.sqrt(1 - t * t), "out": lambda t: math.sqrt(1 - (t - 1) ** 2),
                "in_out": lambda t: (1 - math.sqrt(1 - (2 * t) ** 2)) / 2 if t < 0.5 else (math.sqrt(1 - (-2 * t + 2) ** 2) + 1) / 2}[kind]
    raise NotImplementedError("rig_path: ease %r not reproduced; add it from Easing.kt" % name)


def _literal(v, default=0):
    if v is None:
        return default
    if isinstance(v, dict):
        return v.get("value", default)
    return v


class RigPath:
    def __init__(self, skill_id, rig_vfx, rig_id=None, fx_vfx=None, distance=CAPTURE_DISTANCE):
        self.skill = yaml.safe_load((ROOT / "content/skills" / (skill_id + ".yaml")).read_text())
        rdef = yaml.safe_load((ROOT / "content/vfx" / (rig_vfx + ".yaml")).read_text())
        rigs = rdef["rigs"]
        self.rig = next(r for r in rigs if rig_id is None or r["id"] == rig_id)
        self.sample_ticks = int(rdef.get("sample_ticks", 2))
        steps = self.skill["phases"]["cast"]
        play = [s for s in steps if s.get("type") == "play_vfx"]
        rstep = next(s for s in play if s["vfx_id"] == rig_vfx)
        self.rig_delay = int(_literal(rstep.get("delay_ticks")))
        self.fx_delay = int(_literal(next(s for s in play if s["vfx_id"] == fx_vfx).get("delay_ticks"))) if fx_vfx else self.rig_delay
        self.fx_duration = int(yaml.safe_load((ROOT / "content/vfx" / (fx_vfx + ".yaml")).read_text())["duration_ticks"]) if fx_vfx else None
        self.follow = bool(rdef.get("follow", False)) or bool(rdef.get("attach_to_anchor", False))
        self.pivot = tuple(self.rig["pivot"])
        self.tilt = qeuler(*self.rig.get("tilt", [0, 0, 0]))
        self.rest = qeuler(*self.rig.get("rest", [0, 0, 0]))
        self.start = int(self.rig.get("start_tick", 0))
        self.base = self.start + ANIMATION_DELAY_TICKS
        freezes = [(int(f["at"]), int(f["ticks"])) for f in (rstep.get("freeze") or [])] if self.rig.get("respect_hit_stop") else []
        self.windows = self._merge(freezes)
        self.stops, acc = [], 0
        for at, n in self.windows:
            a = at - self.base - acc
            if a >= 0:
                self.stops.append((a, n))
                acc += n
        cur = (0.0, 0.0, 0.0)
        self.keys = []
        for k in self.rig.get("swing", []):
            cur = tuple(k.get("rotate", cur))
            self.keys.append((int(k["t"]), cur, _ease(k.get("ease"))))
        self.chunks = self._chunks()
        # blade segment in rig-local space: grip end -> farthest +Y extent of any part (the tip)
        # ADR-0254: a `weapon: main_hand` rig has no parts; the blade ends are declared (weapon_tip / weapon_root).
        parts = self.rig.get("parts", [])
        if self.rig.get("weapon") and "weapon_tip" in self.rig:
            self.tip_local = float(self.rig["weapon_tip"][1])
            self.root_local = float(self.rig.get("weapon_root", [0.0, 0.0, 0.0])[1])
        else:
            self.tip_local = max((p["offset"][1] + p["size"][1] / 2 for p in parts), default=1.0)
            guard = [p for p in parts if "guard" in p["id"]]
            self.root_local = max((p["offset"][1] + p["size"][1] / 2 for p in guard), default=0.0)
        # caster dashes (skill ticks): (start, ticks, distance capped by stop_before_target at `distance`)
        self.dashes, left = [], distance
        for s in sorted((s for s in steps if s.get("type") == "dash_strike"), key=lambda s: _literal(s.get("delay_ticks"))):
            d = min(float(s["distance"]), max(0.0, left - float(s.get("stop_before_target", 0.0))))
            self.dashes.append((int(_literal(s.get("delay_ticks"))), int(s["ticks"]), d))
            left -= d
        self.distance = distance

    # ---- runtime clock ---------------------------------------------------------------------------------------
    @staticmethod
    def _merge(fs):
        out = []
        for at, n in sorted(fs):
            if out and at <= out[-1][0] + out[-1][1]:
                out[-1] = (out[-1][0], max(out[-1][0] + out[-1][1], at + n) - out[-1][0])
            else:
                out.append((at, n))
        return out

    def _euler_at(self, u):
        ks = self.keys
        if not ks:
            return (0.0, 0.0, 0.0)
        if u <= ks[0][0]:
            return ks[0][1]
        if u >= ks[-1][0]:
            return ks[-1][1]
        i = next(i for i, k in enumerate(ks) if k[0] >= u)
        a, b = ks[i - 1], ks[i]
        f = b[2]((u - a[0]) / (b[0] - a[0]))
        return tuple(x + (y - x) * f for x, y in zip(a[1], b[1]))

    def _exact(self, u):
        return qmul(qmul(self.tilt, qeuler(*self._euler_at(u))), self.rest)

    def _turn(self, a, b):
        ea, eb = self._euler_at(a), self._euler_at(b)
        return max(max(abs(y - x) for x, y in zip(ea, eb)), qangle_deg(self._exact(a), self._exact(b)))

    def _split(self, a, b, out):
        if b - a >= 2 and self._turn(a, b) > MAX_STEP_DEG:
            m = (a + b) // 2
            self._split(a, m, out)
            self._split(m, b, out)
        else:
            out.append((a, b, self._exact(a), self._exact(b)))

    def _chunks(self):
        last = self.keys[-1][0] if self.keys else 0
        pts = sorted(set([0] + [k[0] for k in self.keys] + [s[0] for s in self.stops if s[0] <= last]))
        out = []
        for a, b in zip(pts, pts[1:]):
            d = b - a
            n = (d + self.sample_ticks - 1) // self.sample_ticks
            prev = a
            for j in range(1, n + 1):
                cut = a + int(math.floor(j * d / n + 0.5))
                self._split(prev, cut, out)
                prev = cut
        return out

    def rotation_at(self, s):
        """Rotation the client shows at fractional rig tick s (VfxRigMotion.rotationAt)."""
        c = self.chunks
        if not c:
            return self._exact(0)
        if s <= c[0][0]:
            return c[0][2]
        if s >= c[-1][1]:
            return c[-1][3]
        a, b, qa, qb = next(ch for ch in c if s < ch[1])
        return qslerp(qa, qb, (s - a) / (b - a))

    def rig_tick(self, effect_tick):
        """Fractional rig tick reached at (fractional) rig-effect tick e: e - base minus the frozen time before e."""
        r = effect_tick - self.base
        for at, n in self.windows:
            if effect_tick >= at + n:
                r -= n
            elif effect_tick > at:
                r -= effect_tick - at
        return r

    def rig_effect_tick(self, l):
        """Rig-effect tick at which rig tick l has been reached (VfxRigMotion.effectTickEnd)."""
        return self.base + l + sum(n for a, n in self.stops if a < l)

    # ---- frames ----------------------------------------------------------------------------------------------
    def dash_offset(self, skill_tick):
        z = 0.0
        for t0, n, d in self.dashes:
            z += d * min(1.0, max(0.0, (skill_tick - t0 + 1) / n))  # DashStrikeRunner: first step IN the cast tick
        return z

    def skill_tick(self, fx_tick):
        return fx_tick + self.fx_delay

    def blade_point(self, fx_tick, frac=1.0, radius=None):
        """Point on the blade (frac 0 = guard top, 1 = tip; or `radius` blocks from the pivot) at fractional fx tick,
        in the fx file's anchor frame."""
        s = self.skill_tick(fx_tick)
        q = self.rotation_at(self.rig_tick(s - self.rig_delay))
        r = radius if radius is not None else self.root_local + (self.tip_local - self.root_local) * frac
        v = qrot(q, (0.0, r, 0.0))
        dz = (self.dash_offset(s) - self.dash_offset(self.fx_delay)) if self.follow else 0.0
        return (self.pivot[0] + v[0], self.pivot[1] + v[1], self.pivot[2] + v[2] + dz)

    def blade_dir(self, fx_tick):
        s = self.skill_tick(fx_tick)
        return qrot(self.rotation_at(self.rig_tick(s - self.rig_delay)), (0.0, 1.0, 0.0))

    def tip_speed(self, fx_tick, frac=1.0, h=0.25):
        a, b = self.blade_point(fx_tick - h, frac), self.blade_point(fx_tick + h, frac)
        return math.dist(a, b) / (2 * h)

    def contact_fx_ticks(self):
        """Freeze windows (= hit-stop contacts) as fx ticks of this fx file."""
        return [at + self.rig_delay - self.fx_delay for at, _ in self.windows]

    def frozen_fx(self, fx_tick):
        e = self.skill_tick(fx_tick) - self.rig_delay
        return any(at <= e < at + n for at, n in self.windows)

    def swing_windows(self, min_speed=1.0, frac=1.0, first=0, last=None):
        """(start, contact, resume, end) fx ticks per contact: the blade (at `frac`) moves >= min_speed blocks/tick on every
        whole-tick chord from start to contact and from resume (end of the hit-stop) to end. Clamped to [first, last]. A
        rig re-keyed by the animator moves these windows with it (the wind-up / coil is slow, so it is excluded)."""
        def chord(u):  # blade travel over fx tick u-1 -> u
            return math.dist(self.blade_point(u - 1, frac), self.blade_point(u, frac))
        last = self.fx_duration - 1 if last is None and self.fx_duration else last
        out = []
        for c in self.contact_fx_ticks():
            if last is not None and c > last or c < first:
                continue
            s = c
            while s - 1 >= first and chord(s) >= min_speed:
                s -= 1
            r = c
            while self.frozen_fx(r):
                r += 1
            e = r
            while (last is None or e + 1 <= last) and chord(e + 1) >= min_speed:
                e += 1
            out.append((s, c, r, e))
        return out

    def spin_axis(self, fx_tick, h=1.0 / 16):
        """Unit rotation axis of the blade at fractional fx tick (blade dir x its derivative); None while the blade holds."""
        a, b = self.blade_dir(fx_tick - h), self.blade_dir(fx_tick + h)
        w = (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        L = math.sqrt(sum(x * x for x in w))
        return tuple(x / L for x in w) if L > 1e-6 else None

    def strike_windows(self, frac=1.0, first=0, last=None, hold_speed=0.5, max_turn_deg=60.0, follow_speed=1.0,
                       fine=16):
        """(start, contact, resume, end) fx ticks per contact, like swing_windows, but the START is where the strike
        itself begins, not the first fast tick: walking back from the contact on a 1/`fine` tick grid, the window stops at
        the last anticipation hold (tip speed < hold_speed) or the last turn (the blade's rotation axis differs from the
        axis it has entering the contact by > max_turn_deg: a direction reversal, the drop into a coil, a wind-up lift).
        So wind-up motion never belongs to a window however fast it is (r24: the drop into the coil ran at 1.3-3.2
        blocks/tick and swing_windows counted it as swing). `start` is fractional (feed it to arc / blade_point; emit at
        ceil). resume / end: the hit-stop end and the follow-through while the tip moves >= follow_speed blocks/tick
        (whole-tick chords, as swing_windows)."""
        last = self.fx_duration - 1 if last is None and self.fx_duration else last
        cmax = math.cos(math.radians(max_turn_deg))
        h = 1.0 / fine
        out = []
        for c in self.contact_fx_ticks():
            if last is not None and c > last or c < first:
                continue
            ref = self.spin_axis(c - h / 2, h / 2)
            s = float(c)
            while s - h >= first - 1e-9:
                u = s - h / 2
                if self.tip_speed(u, frac, h / 2) < hold_speed:
                    break
                ax = self.spin_axis(u, h / 2)
                if ref is not None and (ax is None or sum(x * y for x, y in zip(ax, ref)) < cmax):
                    break
                s -= h
            s = max(float(first), round(s * fine) / fine)
            r = c
            while self.frozen_fx(r):
                r += 1
            e = r
            while (last is None or e + 1 <= last) and math.dist(self.blade_point(e, frac),
                                                                 self.blade_point(e + 1, frac)) >= follow_speed:
                e += 1
            out.append((s, c, r, e))
        return out

    def arc(self, t0, t1, frac=1.0, radius=None, step=0.3):
        """Points along the TRUE (slerped) blade path between fx ticks t0 and t1, spaced <= step blocks: list of
        (fractional fx tick, point, unit tangent). Never a straight chord (a 120 deg chunk's chord cuts through the body)."""
        n = 8 * max(1, int(math.ceil(t1 - t0)))
        fine = [t0 + (t1 - t0) * i / n for i in range(n + 1)]
        pts = [self.blade_point(t, frac, radius) for t in fine]
        out, acc = [], step  # first point at t0
        for i in range(len(fine)):
            if i:
                acc += math.dist(pts[i - 1], pts[i])
            if acc >= step:
                j0, j1 = max(i - 1, 0), min(i + 1, n)
                tg = [pts[j1][k] - pts[j0][k] for k in range(3)]
                L = math.sqrt(sum(x * x for x in tg)) or 1.0
                out.append((fine[i], pts[i], tuple(x / L for x in tg)))
                acc = 0.0
        return out

    def target_eye(self, fx_tick=0.0, eye_y=1.62):
        """Opponent eye at the capture distance, in this fx frame (target stands still)."""
        return (0.0, eye_y, self.distance - self.dash_offset(self.fx_delay))

    def caster_eye(self, fx_tick, eye_y=1.62):
        s = self.skill_tick(fx_tick)
        return (0.0, eye_y, self.dash_offset(s) - self.dash_offset(self.fx_delay))


def _phi_pitch(d):
    return (math.degrees(math.atan2(d[0], d[2])) % 360, math.degrees(math.asin(max(-1, min(1, d[1])))))


if __name__ == "__main__":
    skill, rig, fx = sys.argv[1], sys.argv[2], (sys.argv[3] if len(sys.argv) > 3 else None)
    rp = RigPath(skill, rig, fx_vfx=fx)
    print("%s / %s / fx %s: rig at skill %d, fx at skill %d, freezes %s, contacts (fx ticks) %s, dashes %s, blade %.2f..%.2f"
          % (skill, rig, fx, rp.rig_delay, rp.fx_delay, rp.windows, rp.contact_fx_ticks(), rp.dashes, rp.root_local, rp.tip_local))
    print("fx  skill rigT   tip(x, y, z)             phi   pitch  tip speed")
    for f in range(-3, 21):
        p = rp.blade_point(f)
        phi, pit = _phi_pitch(rp.blade_dir(f))
        print("%3d %4d %6.2f  (%6.2f, %5.2f, %5.2f)  %5.1f %6.1f  %5.2f" % (
            f, rp.skill_tick(f), rp.rig_tick(rp.skill_tick(f) - rp.rig_delay), p[0], p[1], p[2], phi, pit, rp.tip_speed(f)))
