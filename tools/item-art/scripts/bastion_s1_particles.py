#!/usr/bin/env python3
"""Bastion S1 particle layer (round 10 rebuild: moving streaks + sprite display particles, no dust / trail dots): particle
files + particle and sprite-emitter elements appended to the fx files.

Run AFTER tools/item-art/scripts/bastion_s1_fx.py (that script rewrites the fx files from scratch and drops the
marked particle block); this script removes its own previous block and re-appends it, so it is idempotent:

    python3 tools/item-art/scripts/bastion_s1_particles.py

Vanilla particle elements cost no display budget; the sprite emitters (ids *-p-glints-*, *-p-slivers*) spend one display per
particle. Everything is tick-exact relative to the fx clock (contact = effect tick 4).

r10 (reviewer on real client r7): vanilla `dust` / `dust_color_transition` render as pixel `#` splats at close range and
barely move (velocity x 0.1), `trail` dots are flat squares that sit still. The layer is now: electric_spark only (2-3 tick
streaks) + sprite display particles (smooth gold glint stars, dark slivers) that fly, spin and shrink to nothing in <= 6
ticks, spawned above knee height and moving up/out.
Frame: x = left, y = up, z = forward, origin at the caster's feet; the enemy is at z 2.4 (hit 1) / 1.7 (hit 2) and the
opponent camera sits ON the enemy: blade-path points keep EYE_KEEP (0.85) from that eye, contact sprays start >= 1.2 below
it with speeds that cannot reach it, and nothing is emitted within NEAR (1.55) of the caster eye (0, 1.62, 0).
"""
import math
import pathlib

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


GOLD = (255, 210, 58)
ORANGE = (255, 150, 40)


def defs():
    """r12: the layer has no vanilla particle files of its own any more. electric_spark rendered lavender-white (no colour
    control) and, within ~3 blocks of a camera, as 30-80 px blocky "+" crosses (real client r8, cmp-h1 t8-11, caster-fp
    t10-13): the square look the founder rejects. Every hit-1 / hit-2 emission point is within 3 blocks of the caster or the
    opponent eye, so they are all dropped; the generated defs are deleted."""
    for name in ("spark", "spark-up", "spark-burst", "trail-spark"):
        f = PART / ("bastion-s1-p-%s.yaml" % name)
        if f.exists():
            f.unlink()


GLINT_TINT = "'#FFC850'"
SLIVER_TINT = None  # r12: the sliver sprite is painted dark brown at alpha 0.8; the old '#FF2A2218' tint turned it black


def emitter(eid, sprite, tint, start, p, n, rate, speed, direction, spread, gravity, drag, life, size, sol, spin, aol=None):
    """Sprite display-particle element (one display per particle): flies, spins, shrinks to nothing."""
    keys = ", ".join("{ t: %s, v: %s%s }" % (t, v, ", ease: " + e if e else "") for t, v, e in sol)
    EMITS.append(dict(id=eid, start=start, p=p, n=n, rate=rate, speed=speed, direction=direction, spread=spread,
                      gravity=gravity, drag=drag, life=life, size=size, sprite=sprite))
    akeys = "" if not aol else ", alpha_over_life: [%s]" % ", ".join(
        "{ t: %s, v: %s%s }" % (t, v, ", ease: " + e if e else "") for t, v, e in aol)
    return "\n".join([
        "- id: " + eid,
        "  kind: {type: sprite, sprite: %s, frame_ticks: 1, loop: false, see_through: true%s}" % (sprite, ", color: " + tint if tint else ""),
        "  billboard: center",
        "  brightness: 15",
        "  start_tick: %d" % start,
        "  keyframes:",
        "  - t: 0",
        "    translate: [%s]" % ", ".join(str(round(v, 3)) for v in p),
        "  emit: { count: %d, rate_per_tick: %d, speed: [%s, %s], direction: '%s', spread_deg: %s, gravity: %s, drag: %s," % (
            n, rate, speed[0], speed[1], direction, spread, gravity, drag),
        "    life_ticks: [%d, %d], size: [%s, %s], size_over_life: [%s]," % (life[0], life[1], size[0], size[1], keys),
        "    spin_over_life: [{ t: 0, v: %s }]%s, start_frame: random, frame_ticks: [1, 2] }" % (spin, akeys)])


EMITS = []  # every sprite emitter written this run (for the keep-out / budget report)


GLINT_SOL = [(0, 0.5, None), (0.25, 1.0, "out_quad"), (1, 0.0, "in_quad")]
SLIVER_SOL = [(0, 1.0, None), (0.6, 0.85, None), (1, 0.0, "in_quad")]


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
# Round 7b: the emitters ride the r7 rig (animation-notes Round 7, evidence/animator/r7/per-key-tip-face.txt).
# Tip direction per skill tick (phi 0 = forward, + = left; pitch up), pivot = held-item fist. Between keys the
# direction is slerped (great circle), so the hit-1 points stay in the chop plane (tilted 22 deg) and the hit-2
# points on the low ring. Gold dust rides r 2.15 (inside the smear's outer edge), dark dust r 2.45 (the dark rim
# outside it), a half-tick lagging gold line fills the chord sag of the 50 deg/tick swing.
# ---------------------------------------------------------------------------------------------------------
PIVOT = (-0.348, 0.773, 0.293)
CASTER_EYE = (0.0, 1.62, 0.0)
NEAR = 1.55  # caster camera near field: nothing emitted closer (brief: < 1.5)
EYE_KEEP = 0.85  # opponent eye keep-out for path points
H1_KEYS = {4: (202.6, 43.6), 5: (194.7, 32.1), 6: (244.8, 65.9), 7: (340.7, 39.2), 8: (6.2, -15), 11: (43, -14.5),
           12: (73.1, -15), 13: (106, -15), 14: (138, -15), 15: (167, -15)}  # r9 rig (evidence/animator/r9/per-key-r9.txt): apex 5, contact 8 held 8-10
H2_KEYS = {26: (135, -12), 27: (163, -5), 28: (210, 4), 29: (265, 5), 30: (320, -6), 31: (18, -15), 34: (60, -15),
           35: (102, -15), 36: (134, -12), 37: (159, -9)}
H1_DELAY, H2_DELAY = 4, 27  # skill tick = fx tick + delay (r9: fx-high plays at skill 4, contact skill 8 = fx 4)
H1_EYE, H2_EYE = (0.0, 1.62, 2.4), (0.0, 1.62, 1.7)  # opponent camera = the target at the dash stop


def unit(phi, pitch):
    a, p = math.radians(phi), math.radians(pitch)
    return (math.sin(a) * math.cos(p), math.sin(p), math.cos(a) * math.cos(p))


def slerp(u, v, f):
    d = max(-1.0, min(1.0, sum(x * y for x, y in zip(u, v))))
    w = math.acos(d)
    if w < 1e-6:
        return u
    s = math.sin(w)
    a, b = math.sin((1 - f) * w) / s, math.sin(f * w) / s
    return tuple(a * x + b * y for x, y in zip(u, v))


def blade_dir(keys, tick):
    ks = sorted(keys)
    if tick <= ks[0]:
        return unit(*keys[ks[0]])
    for a, b in zip(ks, ks[1:]):
        if a <= tick <= b:
            return slerp(unit(*keys[a]), unit(*keys[b]), (tick - a) / (b - a))
    return unit(*keys[ks[-1]])


def on_blade(keys, tick, r, eye=None):
    """Point at radius r along the blade at (fractional) skill tick; pulled in toward the pivot until it is EYE_KEEP
    clear of the opponent eye and NEAR clear of the caster eye (pushed out for the latter)."""
    d = blade_dir(keys, tick)
    for _ in range(40):
        p = tuple(PIVOT[i] + r * d[i] for i in range(3))
        if eye is not None and math.dist(p, eye) < EYE_KEEP:
            r -= 0.05
            continue
        if math.dist(p, CASTER_EYE) < NEAR:
            r += 0.05
            continue
        break
    return (p[0], max(p[1], 0.14), p[2])  # dust on the floor line, never under it


def blade_path(keys, delay, fx0, fx1, r, eye, lag=0.0):
    return [on_blade(keys, t + delay - lag, r, eye) for t in range(fx0, fx1 + 1)]


def chord_clear(a, b, eye):
    """True if the straight chord a -> b (what along_path fills between two ticks) stays EYE_KEEP from the opponent eye
    and NEAR from the caster eye."""
    for i in range(11):
        q = tuple(a[k] + (b[k] - a[k]) * i / 10 for k in range(3))
        if math.dist(q, eye) < EYE_KEEP or math.dist(q, CASTER_EYE) < NEAR:
            return False
    return True


def ride(f, name, pid, start, path, step, eye):
    """One along_path emitter per run of clear chords: a chord that would cut the camera keep-outs is dropped by
    starting a new element at the next point (its first emission is that single point, so nothing doubles)."""
    run, t0 = [path[0]], start
    for i in range(1, len(path)):
        if chord_clear(path[i - 1], path[i], eye):
            run.append(path[i])
            continue
        f.add(name, pid, t0, t0 + len(run), run, along=True, step=step)
        run, t0 = [path[i]], start + i
    f.add(name, pid, t0, t0 + len(run), run, along=True, step=step)


def off(p, dx=0.0, dy=0.0, dz=0.0):
    return (p[0] + dx, p[1] + dy, p[2] + dz)


KNEE = 0.5  # nothing may sit below knee height after contact + 3


def spark_path(f, keys, delay, fx0, fx1, eye):
    """electric_spark flicks along the swing tangent at r 2.3; points below KNEE only up to contact + 1 (fx 5), so the
    2-3 tick streaks are gone by contact + 3."""
    path = blade_path(keys, delay, fx0, fx1, 2.3, eye)
    keep = [q for i, q in enumerate(path) if fx0 + i <= 5 or q[1] >= KNEE]
    ride(f, "trail-spark", "trail-spark", fx0, keep, 0.45, eye)


def contact(f, c, ticks):
    """Spark calls at the floor contact; calls after contact + 1 start 0.35 higher (above KNEE), so no 2-3 tick streak is
    still below the knee after contact + 3."""
    for i, tick in enumerate(ticks):
        dx, dy = (0.1, -0.1, 0.0)[i % 3], 0.05 * i + (0.35 if tick > 5 else 0.0)
        f.at("spark", "spark", tick, off(c, dx, dy, -0.1))
        f.at("spark-burst", "spark-burst", tick, off(c, -dx, 0.1 + dy, -0.12))
        if i < 2:
            f.at("spark-up", "spark-up", tick, off(c, 0.05 - 0.15 * i, 0.05, -0.05))


# ---------------------------------------------------------------------------------------------------------
# r11: hit 1 rides the r10 rig (animation-notes Round 10): a tilted HORIZONTAL sweep whose tip stays on the plane
# y = 1.15 + tan(20) z. Tip position = the tip circle about (-0.35, 1.15, 0) at horizontal angle alpha (0 = left, 90 =
# forward, 180 = right; alpha = 90 - phi): skill 6 181.2, 7 134.8, 8 93.5 (contact, frozen 8-10), 11 45.2, 12 -3.3. The root
# moves, so the old fixed-pivot slerp (on_blade) no longer applies to hit 1 (H1_KEYS stays only as the r9 record). Same alpha
# as the display core's ring smear (bastion_s1_sprites.SWEEPS["hring"]); h1_point can clamp to its edge (inset) or ride the
# blade radius (inset None).
# ---------------------------------------------------------------------------------------------------------
import sys  # noqa: E402
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import numpy as _np  # noqa: E402
from bastion_s1_sprites import ring_radius as _ring_radius  # noqa: E402

H1_ALPHA = {6: 181.2, 7: 134.8, 8: 93.5, 9: 93.5, 10: 93.5, 11: 45.2, 12: -3.3}
H1_C = (-0.35, 1.15, 0.0)
_T20 = math.tan(math.radians(20.0))
_RA, _RR = _ring_radius("hring")


def h1_alpha(tick):
    ks = sorted(H1_ALPHA)
    tick = min(max(tick, ks[0]), ks[-1])
    for a, b in zip(ks, ks[1:]):
        if a <= tick <= b:
            return H1_ALPHA[a] + (H1_ALPHA[b] - H1_ALPHA[a]) * (tick - a) / (b - a)
    return H1_ALPHA[ks[-1]]


def clear_runs(f, name, pid, fx0, pts, step, eye):
    """ride() over each run of points that are themselves clear of both camera keep-outs."""
    run, t0 = [], fx0
    for i, q in enumerate(pts + [None]):
        ok = q is not None and math.dist(q, eye) >= EYE_KEEP and math.dist(q, CASTER_EYE) >= NEAR
        if ok:
            if not run:
                t0 = fx0 + i
            run.append(q)
        elif run:
            ride(f, name, pid, t0, run, step, eye)
            run = []


def h1_point(tick, r, inset):
    """Point on the r10 tip plane at horizontal radius min(r, ring outer edge - inset) for (fractional) skill tick."""
    al = h1_alpha(tick)
    rr = r if inset is None else min(r, float(_np.interp(al, _RA, _RR)) - inset)  # None: on the blade, not the smear
    x, z = H1_C[0] + rr * math.cos(math.radians(al)), rr * math.sin(math.radians(al))
    return (x, H1_C[1] + _T20 * z, z)


SPARK, STREAK_TINT = "bastion-s1-spark", "'#FF8A1E'"
SPARK_SOL = [(0, 0.6, None), (0.2, 1.0, "out_quad"), (1, 0.0, "in_quad")]
STREAK_SOL = [(0, 0.5, None), (0.15, 1.0, "out_expo"), (1, 0.0, "in_quad")]


def h1_shed(f, eid, tick, al_tick, n, out_sign):
    """Gold sparks shed off the blade tip where the ring head passes (skill tick al_tick), flung outward from the ring centre
    (+ up a little)."""
    p = h1_point(al_tick, 2.45, 0.2)
    al = math.radians(h1_alpha(al_tick))
    d = (math.cos(al) * out_sign, 0.6, math.sin(al) * out_sign)
    f.els.append(emitter(eid, SPARK, None, tick, p, n, 2, (0.24, 0.34), "custom[%.2f, %.2f, %.2f]" % d, 35, 0.02, 0.15,
                         (3, 4), (0.12, 0.17), SPARK_SOL, 0))


def hit1():
    """r12: sprite display particles only (no electric_spark). Contact fx 4 = skill 8, cluster on the contact blade at 45%
    c = (-0.44, 1.54, 1.59) (= the display core's flash). Gold = `bastion-s1-spark` (smooth AA gold streak-glints, 4 frames,
    random start frame, 1-2 ticks per frame = flicker), orange = `bastion-s1-streak` tinted, stars = `bastion-s1-glint`, dark =
    `bastion-s1-sliver` (brown needle). No spin on streak-shaped sprites (roll 0 = horizontal = the cut direction on screen).
    Everything flies sideways (mostly to the caster's right, -x, away from both eye columns) and up; nothing toward the
    caster; keep-outs are checked by report() with the planner's own trajectory maths."""
    f = Fx("s1h1")
    c = (-0.44, 1.54, 1.59)
    s = off(c, 0, 0.15, -0.1)
    # blade sheds: right side as the ring head comes round (skill 6-7 = fx 2-3), left side on the follow-through (skill 11-12)
    h1_shed(f, "s1h1-p-shed-r", 2, 6, 3, 1)
    h1_shed(f, "s1h1-p-shed-r2", 3, 7, 2, 1)
    h1_shed(f, "s1h1-p-shed-l", 7, 11, 3, 1)
    h1_shed(f, "s1h1-p-shed-l2", 8, 12, 2, 1)
    # contact: gold sparks thrown along the cut to the caster's right (-x, away from both eye columns) and up, orange streaks,
    # gold stars, brown slivers. +x (the caster's left) runs between the two eyes (0.7-0.9 from each): nothing goes there.
    f.els.append(emitter("s1h1-p-sparks-r", SPARK, None, 4, s, 7, 3, (0.3, 0.45), "custom[-1, 0.45, 0.15]", 35, 0.02, 0.15,
                         (3, 4), (0.14, 0.2), SPARK_SOL, 0))
    f.els.append(emitter("s1h1-p-sparks-up", SPARK, None, 4, off(s, -0.1, 0, -0.05), 5, 2, (0.2, 0.3), "custom[-0.35, 1, 0.15]", 25,
                         0.03, 0.15, (3, 4), (0.12, 0.18), SPARK_SOL, 0))
    f.els.append(emitter("s1h1-p-streaks", "bastion-s1-streak", STREAK_TINT, 4, off(c, 0, -0.1, -0.15), 5, 2, (0.35, 0.5),
                         "custom[-1, 0.2, 0.25]", 25, 0.0, 0.2, (3, 4), (0.07, 0.1), STREAK_SOL, 0))
    f.els.append(emitter("s1h1-p-glints-up", "bastion-s1-glint", GLINT_TINT, 4, s, 2, 2, (0.24, 0.34), "custom[-0.3, 1.1, 0.1]", 25,
                         0.03, 0.12, (3, 4), (0.09, 0.13), GLINT_SOL, 25))
    f.els.append(emitter("s1h1-p-glints-r", "bastion-s1-glint", GLINT_TINT, 5, s, 2, 2, (0.24, 0.34), "custom[-1, 1.1, -0.1]", 30,
                         0.03, 0.12, (3, 4), (0.09, 0.13), GLINT_SOL, -25))
    f.els.append(emitter("s1h1-p-slivers", "bastion-s1-sliver", SLIVER_TINT, 4, off(s, 0, 0, -0.1), 5, 2, (0.18, 0.26),
                         "custom[-1, 0.8, 0.1]", 40, 0.03, 0.08, (4, 5), (0.04, 0.06), SLIVER_SOL, 0))
    return f


def hit2():
    """r12: sprite display particles only (no electric_spark). Contact fx 4 = skill 31 on the contact blade at r 1.2 =
    (0.02, 0.46, 1.40), in front of the target body; the opponent eye (0, 1.62, 1.7) is 1.2 above it, so the sprays go
    sideways (+-x) with little rise; the caster eye is 1.8 behind."""
    f = Fx("s1h2")
    c = on_blade(H2_KEYS, 31, 1.2)
    s = off(c, 0, 0.38, 0.0)  # y 0.84: the downward half of the cones stays above the knee (0.5)
    # blade sheds on the ring sweep before the contact (skill 29 / 30 = fx 2 / 3), flung outward from the pivot axis
    for eid, tick in (("s1h2-p-shed", 2), ("s1h2-p-shed-2", 3)):
        d = blade_dir(H2_KEYS, tick + H2_DELAY)
        p = on_blade(H2_KEYS, tick + H2_DELAY, 2.3, H2_EYE)
        f.els.append(emitter(eid, SPARK, None, tick, (p[0], max(p[1], 0.6), p[2]), 3 if tick == 2 else 2, 2, (0.24, 0.34),
                             "custom[%.2f, 0.5, %.2f]" % (d[0], d[2]), 35, 0.02, 0.15, (3, 4), (0.12, 0.17), SPARK_SOL, 0))
    for side, sg in (("l", 1), ("r", -1)):
        f.els.append(emitter("s1h2-p-sparks-" + side, SPARK, None, 4, s, 6, 3, (0.3, 0.45), "custom[%d, 0.15, 0.0]" % sg, 20,
                             0.02, 0.15, (3, 4), (0.14, 0.2), SPARK_SOL, 0))
        f.els.append(emitter("s1h2-p-streaks-" + side, "bastion-s1-streak", STREAK_TINT, 4, off(c, 0, 0.3, -0.1), 2, 2,
                             (0.35, 0.5), "custom[%d, 0.1, 0.15]" % sg, 20, 0.0, 0.2, (3, 4), (0.07, 0.1), STREAK_SOL, 0))
        f.els.append(emitter("s1h2-p-glints-" + side, "bastion-s1-glint", GLINT_TINT, 5, s, 2, 2, (0.26, 0.36),
                             "custom[%d, 0.2, 0.0]" % sg, 20, 0.03, 0.12, (3, 4), (0.1, 0.14), GLINT_SOL, 25 * sg))
        f.els.append(emitter("s1h2-p-slivers-" + side, "bastion-s1-sliver", SLIVER_TINT, 4, s, 2, 2, (0.2, 0.28),
                             "custom[%.1f, 0.3, 0.1]" % (0.9 * sg), 20, 0.015, 0.08, (4, 5), (0.04, 0.06), SLIVER_SOL, 0))
    return f


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


# ---------------------------------------------------------------------------------------------------------
# report: keep-out distances (the planner's own trajectory maths, 400 sampled cone directions x speed extremes) and the
# display particles alive per fx tick (conservative = longest life; expected = mean life).
# ---------------------------------------------------------------------------------------------------------
def _dirs(e, n=400):
    import random
    rnd = random.Random(7)
    v = [float(x) for x in e["direction"][len("custom["):-1].split(",")]
    L = math.sqrt(sum(x * x for x in v))
    base = [x / L for x in v]
    helper = (0.0, 1.0, 0.0) if abs(base[1]) < 0.99 else (1.0, 0.0, 0.0)

    def cr(a, b):
        return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    u = cr(base, helper)
    lu = math.sqrt(sum(x * x for x in u))
    u = tuple(x / lu for x in u)
    w = cr(base, u)
    cm = math.cos(math.radians(e["spread"]))
    out = []
    for _ in range(n):
        ct = rnd.uniform(cm, 1.0)
        st = math.sqrt(max(0.0, 1 - ct * ct))
        ph = rnd.uniform(0, 2 * math.pi)
        out.append(tuple(base[k] * ct + u[k] * st * math.cos(ph) + w[k] * st * math.sin(ph) for k in range(3)))
    return out


def report(eye_opp, label, emits):
    print(label, "keep-out (id, min to caster eye, min to opponent eye, min y):")
    for e in emits:
        dmin_c = dmin_o = ymin = 9.0
        for d in _dirs(e):
            for sp in e["speed"]:
                p = list(e["p"])
                v = [d[k] * sp for k in range(3)]
                for _ in range(e["life"][1]):
                    v = [v[0] * (1 - e["drag"]), (v[1] - e["gravity"]) * (1 - e["drag"]), v[2] * (1 - e["drag"])]
                    p = [p[k] + v[k] for k in range(3)]
                    dmin_c = min(dmin_c, math.dist(p, CASTER_EYE))
                    dmin_o = min(dmin_o, math.dist(p, eye_opp))
                    ymin = min(ymin, p[1])
        print("   %-20s caster %.2f  opponent %.2f  y_min %.2f" % (e["id"], dmin_c, dmin_o, ymin))
    cons, exp = [], []
    for t in range(16):
        a = b = 0
        for e in emits:
            for slot in range(e["n"]):
                sp = e["start"] + slot // e["rate"]
                a += sp <= t <= sp + e["life"][1] - 1
                b += sp <= t <= sp + (e["life"][0] + e["life"][1]) / 2 - 1
        cons.append(a)
        exp.append(b)
    print(label, "display particles alive per fx tick 0-15  conservative:", cons)
    print(label, "                                          expected:    ", exp, " total emitted:", sum(e["n"] for e in emits))


def patch(path, fx):
    txt = path.read_text()
    if MARK in txt:
        txt = txt[: txt.index(MARK)].rstrip("\n") + "\n"
    txt += MARK + "\n" + fx.text() + "\n"
    path.write_text(txt)


if __name__ == "__main__":
    defs()
    make_spark()
    patch(VFX / "bastion-s1-fx-high.yaml", hit1())
    report(H1_EYE, "hit 1", list(EMITS))
    n1 = len(EMITS)
    patch(VFX / "bastion-s1-fx-low.yaml", hit2())
    report(H2_EYE, "hit 2", EMITS[n1:])
