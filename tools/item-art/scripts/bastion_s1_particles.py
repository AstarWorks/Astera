#!/usr/bin/env python3
"""Bastion S1 particle layer (r28: plain vanilla particles riding the blade): particle files + particle elements appended
to the fx files.

Run AFTER tools/item-art/scripts/bastion_s1_fx.py (that script rewrites the fx files from scratch and drops the marked
particle block) and after `make skill-lint-plan SKILL=bastion-greatsword-s1` (rig_path reads the blade per tick from the
plan dump); this script removes its own previous block and re-appends it, so it is idempotent:

    python3 tools/item-art/scripts/bastion_s1_particles.py

Every position comes from the rig (rig_path.py): a sheath of vanilla particles on the blade root -> tip on every tick of a
held pose, the swept trail of every pose-to-pose cut, an impact burst on each damage tick. No display particles, no
ADR-0274 skinned types (firework / entity_effect / cloud), so everything reads as vanilla. See the r28 block below.
"""
import math
import pathlib

import numpy as _np

ROOT = pathlib.Path(__file__).resolve().parents[3]
PART = ROOT / "content/particles"
VFX = ROOT / "content/vfx"
MARK = "# --- particle layer (tools/item-art/scripts/bastion_s1_particles.py): do not edit by hand ---"


def rgb(c):
    return "{ r: %d, g: %d, b: %d }" % c


def pdef(name, comment, vanilla, count, lifetime, shape="{ kind: point }", color=(255, 255, 255), data=None, velocity=None, size=None):
    lines = ["# " + comment, "id: bastion-s1-p-" + name, "shape: " + shape, "color: " + rgb(color), "count: %d" % count,
             "lifetime_ticks: %d" % lifetime, "vanilla_particle: " + vanilla]
    if size is not None:
        lines.append("size: %s" % size)
    if data:
        lines.append("data: " + data)
    if velocity:
        lines.append("velocity: " + velocity)
    lines.append("force: true")
    (PART / ("bastion-s1-p-%s.yaml" % name)).write_text("\n".join(lines) + "\n")


class Fx:
    def __init__(self, tag):
        self.tag = tag
        self.els = []
        self.n = {}

    def add(self, name, pid, start, end, path, every=1, along=False, step=0.2, trail_to=None):
        """path: list of (x, y, z); one keyframe per tick from t 0."""
        key = name
        self.n[key] = self.n.get(key, 0) + 1
        eid = "%s-p-%s%s" % (self.tag, name, "" if self.n[key] == 1 else "-%d" % self.n[key])
        k = "{type: particle, particle_id: bastion-s1-p-%s, every_ticks: %d" % (pid, every)
        if along:
            k += ", along_path: true, step_blocks: %s" % step
        if trail_to:
            k += ", trail_to: [%s]" % ", ".join(str(round(v, 3)) for v in trail_to)
        k += "}"
        lines = ["- id: " + eid, "  kind: " + k, "  start_tick: %d" % start, "  end_tick: %d" % end, "  keyframes:"]
        for t, p in enumerate(path):
            lines.append("  - t: %d" % t)
            lines.append("    translate: [%s]" % ", ".join(str(round(v, 3)) for v in p))
        self.els.append("\n".join(lines))
        return eid

    def at(self, name, pid, tick, p, span=1):
        """Static emitter for `span` ticks from `tick`."""
        return self.add(name, pid, tick, tick + span, [p])

    def text(self):
        return "\n".join(self.els)


# ---------------------------------------------------------------------------------------------------------
# r12 particle sprite: bastion-s1-spark (64 px, 4 frames), a smooth gold streak-glint: white-hot round core, gold lens body
# tapering to needle tails along image +X, faint orange halo. Frames vary length / core size (flicker when the emitter
# draws a random start frame and 1-2 ticks per frame). Size 1 = 1.6 blocks; emit size 0.14-0.2 = 0.2-0.3 blocks long.
# ---------------------------------------------------------------------------------------------------------
def spark_frame(size, tail, core, thick, glow):
    from bastion_s1_sprites import SS, blank, grid, hexc, over, soft
    X, Y = grid(size)
    c = size / 2
    dx, dy = X - c, Y - c
    ax = _np.abs(dx)
    r = _np.hypot(dx, dy)
    white, gold, amber, orange = hexc("#FFFFFF"), hexc("#FFD23A"), hexc("#FFB030"), hexc("#FF8A1E")
    img = blank(size * SS)
    img = over(img, orange[None, None] * _np.ones((1, 1, 3)), _np.exp(-(r / (core * 2.6)) ** 2) * 0.45 * glow)
    hw = thick * _np.clip(1 - ax / tail, 0, 1) ** 1.5
    img = over(img, gold[None, None] * _np.ones((1, 1, 3)), soft(hw - _np.abs(dy), 0.8) * (ax < tail))
    hw2 = thick * 0.45 * _np.clip(1 - ax / (tail * 0.7), 0, 1) ** 1.5
    hotc = (amber * 0.3 + white * 0.7)[None, None] * _np.ones((1, 1, 3))
    img = over(img, hotc, soft(hw2 - _np.abs(dy), 0.7) * (ax < tail * 0.7))
    img = over(img, gold[None, None] * _np.ones((1, 1, 3)), soft(core * 1.35 - r, 1.0))
    img = over(img, white[None, None] * _np.ones((1, 1, 3)), soft(core - r, 0.8))
    return img


def make_spark():
    from bastion_s1_sprites import downsample, save_strip
    size = 64
    frames = [downsample(spark_frame(size, *v), size) for v in
              ((27, 3.4, 3.2, 1.0), (20, 3.9, 3.6, 1.2), (30, 2.8, 2.6, 0.8), (14, 3.4, 3.0, 1.1))]
    save_strip("spark", frames, size)


def patch(path, fx):
    txt = path.read_text()
    if MARK in txt:
        txt = txt[: txt.index(MARK)].rstrip("\n") + "\n"
    txt += MARK + "\n" + fx.text() + "\n"
    path.write_text(txt)


# ---------------------------------------------------------------------------------------------------------
# r28 (founder 2026-10-26: "not around the sword; don't we use vanilla particles at all?" + "read from every angle, not a
# plane facing the side camera"): the layer is now PLAIN vanilla particles (no ADR-0274 skinned type: firework /
# entity_effect / cloud are avoided, so nothing reads as a custom sprite) emitted EVERY tick ON the blade, root to tip, while
# a pose is held, plus the swept trail of every cut and an impact burst on each damage tick. Every position is read from the
# rig through rig_path.py (plan dump = the runtime's own blade per tick), nothing is hand-placed, so a re-keyed motion moves
# the whole layer with it: re-run this script after any rig / skill timing change (and `make skill-lint-plan` first).
#
# Volume: every emitter is a vanilla scatter (`shape: burst` = gaussian position spread in all three axes, `speed` = random
# velocity in all three axes) or a random-direction `outward` spray, so the particles form a sheath AROUND the blade, not a
# plane: they read the same from the front, back, both sides, above and from a player standing next to the caster.
#
# Frame: x = left, y = up, z = forward, origin = the caster's feet when the fx file spawned (rig_path's fx frame).
# ---------------------------------------------------------------------------------------------------------
import sys  # noqa: E402
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import yaml  # noqa: E402
from rig_path import RigPath  # noqa: E402

SKILL, RIG = "bastion-greatsword-s1", "bastion-s1-rig"
FX = {"s1h1": "bastion-s1-fx-high", "s1h2": "bastion-s1-fx-low"}
TARGETS = ROOT / "content/skill-lint" / (SKILL + ".targets.yaml")

# near-field: no particle spawns closer than this to the caster's eye (a dust/crit there fills the first-person view);
# the big sprites (sweep_attack, poof, smoke) keep BIG_NEAR. Distances are to the spawn point; the scatter adds <= 0.2.
NEAR, BIG_NEAR = 0.6, 1.5

GOLD_HOT = (255, 238, 150)
GOLD = (255, 200, 70)
ORANGE = (255, 120, 24)
EMBER_RED = (196, 52, 12)
WHITE_HOT = (255, 252, 230)
CHAR = (60, 34, 20)

DEFS = {}


def vdef(name, comment, vanilla, count, life, shape="{ kind: point }", color=(255, 255, 255), data=None, velocity=None,
         speed=None, size=None):
    pdef(name, comment, vanilla, count, life, shape=shape, color=color, data=data, velocity=velocity, size=size)
    if speed is not None:  # pdef has no `speed:` line (vanilla random-velocity scale for count > 0 scatter)
        f = PART / ("bastion-s1-p-%s.yaml" % name)
        f.write_text(f.read_text().replace("force: true\n", "speed: %s\nforce: true\n" % speed))
    DEFS[name] = dict(vanilla=vanilla, count=count)


def dct(a, b, scale):
    return "{ kind: dust_color_transition, from: %s, to: %s, scale: %s }" % (rgb(a), rgb(b), scale)


def defs():
    """Remove every earlier bastion-s1-p-* file and write this round's set (all plain vanilla types)."""
    for old in PART.glob("bastion-s1-p-*.yaml"):
        old.unlink()
    # dust life (DustParticleBase, 26.3 jar): 8 / (0.2 + 0.8 U) ticks x scale, so scale 0.45 lives 4-18 (median ~7): the
    # sheath re-rolls every tick instead of piling up; scale 1.0 hung a full ring of trail dust for 20+ ticks (r28 sim).
    burst = lambda s: "{ kind: burst, spread_blocks: %s }" % s  # noqa: E731
    # blade sheath (held poses, every tick, every 0.2 blocks root -> tip)
    vdef("sheath", "r28 blade sheath: gold -> orange dust, 2 per point scattered round the blade (3D), re-rolled every tick.",
         "dust_color_transition", 1, 10, burst(0.04), GOLD, dct(GOLD_HOT, ORANGE, 0.28), speed=0.0)
    vdef("sheath-hot", "r28 white-hot edge glints on the outer half of the blade.",
         "dust_color_transition", 1, 8, burst(0.035), WHITE_HOT, dct(WHITE_HOT, GOLD, 0.35), speed=0.0)
    vdef("fleck", "r28 crit flecks thrown off the blade in random directions (vanilla scatter: gaussian velocity x speed).",
         "crit", 3, 8, burst(0.04), speed=0.16)
    vdef("lick", "r28 a flame licking off the blade (rises slowly, dies in ~8-20 ticks).",
         "flame", 1, 14, burst(0.03), speed=0.012)
    # cut trail (the swept volume between two held poses, emitted on the cut tick)
    vdef("trail", "r28 cut trail: big gold -> ember dust along the swept arc.",
         "dust_color_transition", 1, 8, burst(0.04), GOLD, dct(GOLD_HOT, EMBER_RED, 0.3), speed=0.0)
    vdef("trail-crit", "r28 cut trail: crit sparks sprayed off the swept tip arc in every direction.",
         "crit", 2, 8, burst(0.06), velocity="{ direction: along_tangent, speed: 0.32, spread_degrees: 12, speed_jitter: 0.5 }")
    vdef("slash", "r28 vanilla sweep_attack slash on the swept arc (camera-facing, so it reads from every side).",
         "sweep_attack", 1, 4, speed=0.0)
    vdef("ash", "r28 dark ash in the trail (dark mixed with bright): near-black dust.",
         "dust_color_transition", 1, 12, burst(0.09), CHAR, dct((92, 50, 26), (28, 18, 12), 0.5), speed=0.0)
    # impact (damage ticks, at the blade point nearest the target)
    vdef("impact-crit", "r28 impact: crit burst, all directions.",
         "crit", 6, 5, burst(0.12), velocity="{ direction: outward, speed: 0.55, spread_degrees: 0, speed_jitter: 0.45 }")
    vdef("impact-dust", "r3b impact: small short gold dust (no big puffs).",
         "dust_color_transition", 5, 4, burst(0.12), GOLD, dct(WHITE_HOT, ORANGE, 0.4), speed=0.0)
    vdef("impact-smoke", "r3b impact: unused (puffs removed).",
         "smoke", 1, 2, burst(0.05), speed=0.0)
    # staged tiers (principles: convergence = charge / spread = release; phase-shifted rings; rare detail; afterglow)
    vdef("gather", "gather: dust flowing INTO the blade tip before a cut (negative outward speed = convergence).",
         "dust_color_transition", 1, 6, burst(0.3), GOLD, dct(WHITE_HOT, GOLD, 0.22),
         velocity="{ direction: outward, speed: -0.09, spread_degrees: 0, speed_jitter: 0.3 }")
    vdef("ring-crit", "release ring: crit sparks thrown radially from points on a ring round the impact (ring-by-ring phase shift).",
         "dust_color_transition", 1, 6, burst(0.02), GOLD, dct(WHITE_HOT, GOLD, 0.25),
         velocity="{ direction: outward, speed: 0.3, spread_degrees: 0, speed_jitter: 0.3 }")
    vdef("afterglow", "afterglow: embers rising off the impact point after the release.",
         "flame", 2, 12, burst(0.15), velocity="{ direction: up, speed: 0.04, spread_degrees: 25, speed_jitter: 0.5 }")
    vdef("impact-flame", "r28 impact: flames flung out.",
         "flame", 6, 12, burst(0.1), velocity="{ direction: outward, speed: 0.12, spread_degrees: 0, speed_jitter: 0.5 }")


# ---- rig-derived geometry ---------------------------------------------------------------------------------------
def unit(v):
    L = math.sqrt(sum(x * x for x in v)) or 1.0
    return tuple(x / L for x in v)


def pose(rp, ft):
    """(grip, unit blade direction, length) at whole fx tick ft, from the rig (plan dump)."""
    g, t = rp.blade_point(ft, 0.0), rp.blade_point(ft, 1.0)
    d = tuple(t[k] - g[k] for k in range(3))
    L = math.sqrt(sum(x * x for x in d))
    return g, unit(d), L


def phi_of(d):
    return math.degrees(math.atan2(d[0], d[2]))


def pitch_of(d):
    return math.degrees(math.asin(max(-1.0, min(1.0, d[1]))))


def dir_of(phi, pitch):
    cp = math.cos(math.radians(pitch))
    return (cp * math.sin(math.radians(phi)), math.sin(math.radians(pitch)), cp * math.cos(math.radians(phi)))


def skill_hits():
    """(skill tick, cone half angle) of every damage step of the skill."""
    sk = yaml.safe_load((ROOT / "content/skills" / (SKILL + ".yaml")).read_text())
    out = []
    for s in sk["phases"]["cast"]:
        if s.get("type") == "damage":
            ang = s.get("targets", {}).get("angle_degrees", {})
            ang = ang.get("value", 180) if isinstance(ang, dict) else (ang or 180)
            d = s.get("delay_ticks", 0)
            out.append((int(d.get("value", 0) if isinstance(d, dict) else d), float(ang) / 2))
    return out


def none_ticks():
    """Skill ticks of the `fx: none` beats (hold / coil): nothing may be emitted there (skill-lint check 3)."""
    t = yaml.safe_load(TARGETS.read_text())
    out = set()
    for b in t.get("beats", []):
        if b.get("fx") == "none":
            out.update(range(b["ticks"][0], b["ticks"][1] + 1))
    return out


def cuts(rp, ticks):
    """Fx ticks whose pose differs from the previous tick's (a pose-to-pose CUT): [(ft, angle turned)]."""
    out = []
    for ft in ticks:
        g0, d0, _ = pose(rp, ft - 1)
        g1, d1, _ = pose(rp, ft)
        ang = math.degrees(math.acos(max(-1.0, min(1.0, sum(a * b for a, b in zip(d0, d1))))))
        if ang > 8 or math.dist(g0, g1) > 0.35:  # a dash step alone moves the grip ~0.23 per tick
            out.append(ft)
    return out


def sweep(rp, ft, hit_half):
    """The swept blade between the pose before the cut (ft - 1) and the pose after it (ft), as a list of (grip, dir, length,
    phi) samples every ~15 deg of blade turn. r28 (ADR-0278 per-tick swing keys): the samples ride the TRUE interpolated arc
    (rig_path slerps the plan dump between whole ticks, <= 91 deg per tick), so the trail turns the way the blade does --
    cut 1 counter-clockwise from above, cut 2 clockwise -- instead of a forced forehand / "go round once more" rule.
    `hit_half` is kept for the caller's signature (the impact picks the sample nearest the target bearing)."""
    _, dA, _ = pose(rp, ft - 1)
    _, dB, _ = pose(rp, ft)
    ang = math.degrees(math.acos(max(-1.0, min(1.0, sum(a * b for a, b in zip(dA, dB))))))
    n = max(3, int(ang / 15) + 1)
    out = []
    for i in range(n + 1):
        u = ft - 1 + i / n
        g, t = rp.blade_point(u, 0.0), rp.blade_point(u, 1.0)
        d = tuple(t[k] - g[k] for k in range(3))
        L = math.sqrt(sum(x * x for x in d)) or 1.0
        d = tuple(x / L for x in d)
        out.append((g, d, L, phi_of(d)))
    return out


def on_blade(g, d, L, frac):
    return tuple(g[k] + d[k] * L * frac for k in range(3))


EMS = []  # (fx tag, fx tick, pid, point) for the report


class Layer:
    def __init__(self, tag):
        self.tag = tag
        self.rp = RigPath(SKILL, RIG, fx_vfx=FX[tag])
        self.fx = Fx(tag)
        self.none = none_ticks()

    def ok(self, ft, p, big=False):
        eye = self.rp.caster_eye(ft)
        return math.dist(p, eye) >= (BIG_NEAR if big else NEAR) and p[1] >= 0.05 and ft + self.rp.fx_delay not in self.none

    def track(self, name, pid, start, pts):
        """One element riding a run of per-tick points (one keyframe per tick); emits every tick."""
        for i, p in enumerate(pts):
            EMS.append((self.tag, start + i, pid, p))
        self.fx.add(name, pid, start, start + len(pts), pts)

    def once(self, name, pid, ft, p, big=False):
        if self.ok(ft, p, big):
            EMS.append((self.tag, ft, pid, p))
            self.fx.add(name, pid, ft, ft + 1, [p])

    def held(self, ticks):
        """Every tick of `ticks` (fx), along the blade: sheath dust root -> tip, white-hot glints on the outer half, crit
        flecks, a flame lick. Runs of consecutive ticks share one element per blade station (keyframes follow the blade)."""
        stations = [0.12 + 0.88 * i / 4 for i in range(5)]  # 9 stations, ~0.19 blocks apart on the 1.75 blade
        for si, fr in enumerate(stations):
            self._runs("sheath-%d" % si, "sheath", ticks, lambda ft, fr=fr: on_blade(*pose(self.rp, ft), fr))
        for si, fr in enumerate((0.8,)):
            self._runs("hot-%d" % si, "sheath-hot", ticks, lambda ft, fr=fr: on_blade(*pose(self.rp, ft), fr))
        # envelope: the sheath is thickest mid-blade (extra stations), plus a rare slash detail (~1 tick in 7)
        for si, fr in enumerate((0.5,)):
            self._runs("mid-%d" % si, "sheath", ticks, lambda ft, fr=fr: on_blade(*pose(self.rp, ft), fr))
        # r3: no flame / ember on held poses (rest beats stay a faint sheath only)

    def _runs(self, name, pid, ticks, at):
        run = []
        for ft in sorted(ticks) + [None]:
            p = at(ft) if ft is not None else None
            if ft is not None and self.ok(ft, p) and (not run or run[-1][0] == ft - 1):
                run.append((ft, p))
                continue
            if run:
                self.track(name, pid, run[0][0], [q for _, q in run])
            run = [(ft, p)] if ft is not None and self.ok(ft, p) else []

    def cut(self, ft, hit_half, heavy):
        """The swept trail of the cut that lands on fx tick ft: dust + ash on two / three radii (alternating), crit spray off the tip arc,
        sweep_attack slashes on the outer third; a thinner second pass on ft + 1 (the trail lingers one tick)."""
        sw = sweep(self.rp, ft, hit_half)
        slash_every = 3 if heavy else 5
        for i, (g, d, L, _) in enumerate(sw):
            if not heavy and i % 2:
                continue  # ~50% of the steps; the emphasised cut keeps them all
            # r4: only small bright dust along the edge; no crit (X sprites), no slash, no ash (all read as chunky puffs close up)
            for fr in ((0.85, 1.0) if i % 2 else (1.0,)):
                self.once("trail", "trail", ft, on_blade(g, d, L, fr))
        return sw

    def gather(self, c):
        """Charge: dust converging on the blade tip for the 3 ticks before the cut at fx tick c."""
        for ft in range(max(0, c - 3), c):
            g, d, L = pose(self.rp, ft)
            self.once("gather", "gather", ft, on_blade(g, d, L, 1.0))
            self.once("gather", "gather", ft, on_blade(g, d, L, 0.7))

    def release(self, ft, p, c, sense):
        """Release: three expanding rings of outward sparks round the impact point, phase-shifted ring to ring in the cut's
        own sense (cut 1 counter-clockwise from above = +1, cut 2 clockwise = -1), then an afterglow."""
        g, d, L = pose(self.rp, c)
        a = unit((d[2], 0.0, -d[0])) if abs(d[1]) < 0.95 else (1.0, 0.0, 0.0)
        b = (d[1] * a[2] - d[2] * a[1], d[2] * a[0] - d[0] * a[2], d[0] * a[1] - d[1] * a[0])
        for r, (rad, dt) in enumerate(((0.25, 0), (0.5, 1), (0.8, 2))):
            n = 10 if self.tag == "s1h2" else 6  # the emphasised hit (cut 2) scales the same ring up
            for k in range(n):
                th = math.radians(360.0 / n * k + sense * 20 * r)
                q = tuple(p[i] + rad * (math.cos(th) * a[i] + math.sin(th) * b[i]) for i in range(3))
                self.once("ring", "ring-crit", ft + dt, q)

    def impact(self, ft, sw, hit_half, heavy):
        """Damage tick: burst at the swept blade point nearest the target bearing (phi 0), outer quarter of the blade."""
        g, d, L, _ = min(sw, key=lambda s: abs(((s[3] + 180) % 360) - 180))
        p = on_blade(g, d, L, 1.05)  # just past the tip: where the edge meets the target
        k = 3 if self.tag == "s1h2" else (2 if heavy else 1)
        for j in range(k):
            q = (p[0] + 0.25 * j, p[1], p[2] - 0.15 * j)
            self.once("impact-crit", "impact-crit", ft, q)
            self.once("impact-dust", "impact-dust", ft, q)
            self.once("impact-crit", "impact-crit", ft + 1, q)
        return p

    def build(self):
        rp = self.rp
        last = rp.fx_duration - 1
        # r28: a tick on which the blade sweeps is never a hold, whatever the reference beat says (the r28 keys draw the
        # anticipation / first half of cut 1 on skill 4-5, inside the reference's still coil B0 0-5): drop it from `none`.
        moving = cuts(rp, range(1, last + 1))
        self.none = self.none - {ft + rp.fx_delay for ft in moving}
        active = [ft for ft in range(0, last + 1) if ft + rp.fx_delay not in self.none]
        hits = {t - rp.fx_delay: h for t, h in skill_hits() if 0 <= t - rp.fx_delay <= last}
        cut_ticks = cuts(rp, active)
        self.held(active)
        for c in cut_ticks:
            # the hit this cut delivers: the first damage tick at or after the cut, before the next cut
            nxt = min([x for x in cut_ticks if x > c] + [last + 1])
            h = [t for t in hits if c <= t < nxt]
            half = hits[h[0]] if h else None
            self.gather(c)
            sw = self.cut(c, half, heavy=self.tag == "s1h2" or half is not None)
            if h:
                p = self.impact(h[0], sw, half, heavy=self.tag == "s1h2")
                _, dA, _ = pose(rp, c - 1)
                _, dB, _ = pose(rp, c)
                self.release(h[0], p, c, 1 if (dA[2] * dB[0] - dA[0] * dB[2]) > 0 else -1)
        return self.fx


def layer_report():
    """Per fx file: particles per skill tick (calls x count), min distance of each spawn to the blade segment of its own
    tick, min distance to the caster eye."""
    import collections
    for tag in FX:
        rp = RigPath(SKILL, RIG, fx_vfx=FX[tag])
        per, dmax, near = collections.Counter(), collections.defaultdict(float), 9.0
        for t, ft, pid, p in EMS:
            if t != tag:
                continue
            per[ft + rp.fx_delay] += DEFS[pid]["count"]
            g, d, L = pose(rp, ft)
            ap = [p[k] - g[k] for k in range(3)]
            u = max(0.0, min(L, sum(ap[k] * d[k] for k in range(3))))
            if not pid.startswith(("trail", "ash", "slash", "impact")):
                dmax[ft + rp.fx_delay] = max(dmax[ft + rp.fx_delay], math.dist(p, on_blade(g, d, L, u / L)))
            near = min(near, math.dist(p, rp.caster_eye(ft)))
        print("%s (%s at skill %d): particles per skill tick %s" % (tag, FX[tag], rp.fx_delay, dict(sorted(per.items()))))
        print("   held-pose emitters max distance from the blade per tick: %s" % {k: round(v, 3) for k, v in sorted(dmax.items())})
        print("   min spawn distance to the caster eye %.2f; types %s" % (near, sorted({DEFS[pid]["vanilla"] for t, _, pid, _ in EMS if t == tag})))


if __name__ == "__main__":
    defs()
    make_spark()
    patch(VFX / "bastion-s1-fx-high.yaml", Layer("s1h1").build())
    patch(VFX / "bastion-s1-fx-low.yaml", Layer("s1h2").build())
    layer_report()
