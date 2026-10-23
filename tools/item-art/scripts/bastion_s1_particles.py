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
    pdef("spark", "electric_spark streaks (life 2-3 ticks, v = 0.25 * speed) fired forward in a cone: reads from compare/side",
         "electric_spark", 4, 3, color=(255, 240, 190), velocity="{ direction: from_caster_look, speed: 1.8, spread_degrees: 55 }")
    pdef("spark-up", "electric_spark streaks fired up in a wide cone (r7b speed 1.0: rise <= ~0.75, stays clear of the opponent eye above the contact)",
         "electric_spark", 3, 3, color=(255, 240, 190), velocity="{ direction: up, speed: 1.0, spread_degrees: 70 }")
    pdef("spark-burst", "r10: electric_spark burst flung in every direction from the contact (replaces vanilla crit, whose X read as a big glyph and fell)",
         "electric_spark", 4, 3, color=(255, 240, 190), velocity="{ direction: outward, speed: 1.4, spread_degrees: 0 }")
    pdef("trail-spark", "electric_spark flicks thrown along the swing tangent from the path (r10: the only blade-path particle; gold/dark path dust removed)",
         "electric_spark", 1, 3, color=(255, 240, 190), velocity="{ direction: along_tangent, speed: 1.6, spread_degrees: 25 }")


GLINT_TINT, SLIVER_TINT = "'#FFC850'", "'#FF2A2218'"


def emitter(eid, sprite, tint, start, p, n, rate, speed, direction, spread, gravity, drag, life, size, sol, spin):
    """Sprite display-particle element (one display per particle): flies, spins, shrinks to nothing."""
    keys = ", ".join("{ t: %s, v: %s%s }" % (t, v, ", ease: " + e if e else "") for t, v, e in sol)
    return "\n".join([
        "- id: " + eid,
        "  kind: {type: sprite, sprite: %s, frame_ticks: 1, loop: false, see_through: true, color: %s}" % (sprite, tint),
        "  billboard: center",
        "  brightness: 15",
        "  start_tick: %d" % start,
        "  keyframes:",
        "  - t: 0",
        "    translate: [%s]" % ", ".join(str(round(v, 3)) for v in p),
        "  emit: { count: %d, rate_per_tick: %d, speed: [%s, %s], direction: '%s', spread_deg: %s, gravity: %s, drag: %s," % (
            n, rate, speed[0], speed[1], direction, spread, gravity, drag),
        "    life_ticks: [%d, %d], size: [%s, %s], size_over_life: [%s]," % (life[0], life[1], size[0], size[1], keys),
        "    spin_over_life: [{ t: 0, v: %s }], start_frame: random, frame_ticks: [1, 2] }" % spin])


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


def hit1():
    f = Fx("s1h1")
    # contact (fx 4 = skill 8): blade phi 6, pitch -15, tip y 0.11 at z 2.74 (under/behind the target). Sparks sit on the
    # contact blade at r 1.85 = (-0.16, 0.29, 2.06), 1.38 from the opponent eye, 2.1 from the caster eye.
    c = on_blade(H1_KEYS, 8, 1.85)
    spark_path(f, H1_KEYS, H1_DELAY, 2, 6, H1_EYE)  # fx 2 (skill 6, chop from the top) -> contact -> 2 ticks of drag
    contact(f, c, (4, 5, 6))
    # display particles: gold glint stars flung up-left / up-right and back (away from the opponent eye), dark slivers
    # flung up and back. Spawned 0.25 above the contact (knee 0.5 reached on the first tick), life <= 6.
    s = off(c, 0, 0.25, -0.1)
    f.els.append(emitter("s1h1-p-glints-l", "bastion-s1-glint", GLINT_TINT, 4, s, 4, 2, (0.24, 0.34), "custom[1, 1.1, -0.3]", 30,
                         0.03, 0.12, (3, 5), (0.09, 0.13), GLINT_SOL, 25))
    f.els.append(emitter("s1h1-p-glints-r", "bastion-s1-glint", GLINT_TINT, 4, s, 4, 2, (0.24, 0.34), "custom[-1, 1.1, -0.3]", 30,
                         0.03, 0.12, (3, 5), (0.09, 0.13), GLINT_SOL, -25))
    f.els.append(emitter("s1h1-p-slivers", "bastion-s1-sliver", SLIVER_TINT, 4, off(s, 0, 0, -0.1), 5, 3, (0.2, 0.28),
                         "custom[0, 1, -0.8]", 50, 0.03, 0.08, (4, 6), (0.08, 0.12), SLIVER_SOL, 40))
    return f


def hit2():
    f = Fx("s1h2")
    # contact (fx 4 = skill 31): blade phi 18, pitch -15; sparks on the contact blade at r 1.2 = (0.02, 0.46, 1.40), in front
    # of the target body (dash stop 1.7): 1.2 from the opponent eye, 1.8 from the caster eye.
    c = on_blade(H2_KEYS, 31, 1.2)
    spark_path(f, H2_KEYS, H2_DELAY, 1, 6, H2_EYE)
    hi = [off(p, 0.0, 0.3, 0.0) for p in blade_path(H2_KEYS, H2_DELAY, 2, 6, 2.3, H2_EYE)]  # heavier hit: second, higher line
    hi = [q for i, q in enumerate(hi) if 2 + i <= 5 or q[1] >= KNEE]
    ride(f, "trail-spark", "trail-spark", 2, hi, 0.45, H2_EYE)
    contact(f, c, (4, 5, 6, 7))
    s = off(c, 0, 0.25, 0.0)
    # sideways only (the caster eye is 1.8 behind, the opponent eye 1.2 above-ahead): ends ~(+-0.8, 1.1, 1.45)
    f.els.append(emitter("s1h2-p-glints-l", "bastion-s1-glint", GLINT_TINT, 4, s, 5, 3, (0.26, 0.36), "custom[1, 1, 0.1]", 30,
                         0.03, 0.12, (3, 5), (0.1, 0.14), GLINT_SOL, 25))
    f.els.append(emitter("s1h2-p-glints-r", "bastion-s1-glint", GLINT_TINT, 4, s, 5, 3, (0.26, 0.36), "custom[-1, 1, 0.1]", 30,
                         0.03, 0.12, (3, 5), (0.1, 0.14), GLINT_SOL, -25))
    f.els.append(emitter("s1h2-p-slivers-l", "bastion-s1-sliver", SLIVER_TINT, 4, s, 3, 2, (0.2, 0.28), "custom[0.9, 1, -0.1]", 35,
                         0.03, 0.08, (4, 6), (0.08, 0.12), SLIVER_SOL, 40))
    f.els.append(emitter("s1h2-p-slivers-r", "bastion-s1-sliver", SLIVER_TINT, 4, s, 3, 2, (0.2, 0.28), "custom[-0.9, 1, -0.1]", 35,
                         0.03, 0.08, (4, 6), (0.08, 0.12), SLIVER_SOL, -40))
    return f


def patch(path, fx):
    txt = path.read_text()
    if MARK in txt:
        txt = txt[: txt.index(MARK)].rstrip("\n") + "\n"
    txt += MARK + "\n" + fx.text() + "\n"
    path.write_text(txt)


if __name__ == "__main__":
    defs()
    patch(VFX / "bastion-s1-fx-high.yaml", hit1())
    patch(VFX / "bastion-s1-fx-low.yaml", hit2())
