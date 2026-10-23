#!/usr/bin/env python3
"""Bastion S1 vanilla particle layer (round 5, re-ridden on the r7 rig in round 7b): particle files + particle elements appended to the fx files.

Run AFTER tools/item-art/scripts/bastion_s1_fx.py (that script rewrites the fx files from scratch and drops the
marked particle block); this script removes its own previous block and re-appends it, so it is idempotent:

    python3 tools/item-art/scripts/bastion_s1_particles.py

Particle elements cost no display budget. Everything is tick-exact relative to the fx clock (contact = effect tick 4).
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
    pdef("crit", "white-gold crit sparks flung in every direction from the contact (crit: v = 0.4 * speed, friction 0.7; r7b speed 0.7: the floor contact sits 1.2-1.4 under the opponent eye)",
         "crit", 5, 8, color=(255, 240, 190), velocity="{ direction: outward, speed: 0.7, spread_degrees: 0 }")
    pdef("spark", "electric_spark streaks (life 2-3 ticks, v = 0.25 * speed) fired forward in a cone: reads from compare/side",
         "electric_spark", 4, 3, color=(255, 240, 190), velocity="{ direction: from_caster_look, speed: 1.8, spread_degrees: 55 }")
    pdef("spark-up", "electric_spark streaks fired up in a wide cone (r7b speed 1.0: rise <= ~0.75, stays clear of the opponent eye above the contact)",
         "electric_spark", 3, 3, color=(255, 240, 190), velocity="{ direction: up, speed: 1.0, spread_degrees: 70 }")
    pdef("nugget", "bright gold chips (item particle: v = speed, gravity) flung outward (r7b 0.22: straight-up rise <= ~0.6)",
         "item", 3, 12, color=GOLD, data="{ kind: item, item: gold_nugget }", velocity="{ direction: outward, speed: 0.22, spread_degrees: 0 }")
    pdef("coal", "dark chips mixed with the gold (item particle charcoal)",
         "item", 3, 12, color=(30, 24, 20), data="{ kind: item, item: charcoal }", velocity="{ direction: outward, speed: 0.18, spread_degrees: 0 }")
    # r5 founder: particles must MOVE and change every tick. The gold glints are `trail` particles: the client moves each
    # one from its spawn point to the element's trail_to over exactly duration_ticks and removes it on arrival (the only
    # vanilla type with an exact, server-set life and a server-set colour that also travels). They replace wax_on (hung
    # 30-40 ticks near-static as a big orange `+`) and firework (white `+`/`x` flecks hanging ~15-20+ ticks).
    pdef("glint", "gold glint shed off the blade edge: travels ~0.6-1.5 blocks ahead/outward along the swing in 6 ticks, then dies",
         "trail", 1, 6, color=(255, 214, 70), data="{ kind: trail, toward: true, color: { r: 255, g: 214, b: 70 }, duration_ticks: 6 }")
    pdef("glint-hot", "white-gold glint, shorter (4 ticks): the hot head of the shed sparks",
         "trail", 1, 4, color=(255, 244, 200), data="{ kind: trail, toward: true, color: { r: 255, g: 244, b: 200 }, duration_ticks: 4 }")
    pdef("glint-rise", "gold-orange glint flung up and out of the contact over 9 ticks (replaces wax_on motes and firework flecks)",
         "trail", 1, 9, color=(255, 170, 50), data="{ kind: trail, toward: true, color: { r: 255, g: 170, b: 50 }, duration_ticks: 9 }")
    pdef("ember", "orange to dark-red ember dust that hangs and drifts up (dust life = 8/U * scale, ~2-22 ticks at 0.55)",
         "dust_color_transition", 3, 14, color=(255, 150, 40),
         data="{ kind: dust_color_transition, from: { r: 255, g: 175, b: 55 }, to: { r: 150, g: 40, b: 10 }, scale: 0.55 }",
         velocity="{ direction: up, speed: 0.05, spread_degrees: 90 }")
    pdef("trail-gold", "gold dust left along the blade path (one per path point)",
         "dust", 1, 12, color=GOLD, size=0.5, velocity="{ direction: outward, speed: 0.05, spread_degrees: 90 }")
    pdef("trail-dark", "near-black dust mixed into the gold path (reads on bright backdrops)",
         "dust", 1, 12, color=(26, 18, 6), size=0.55, velocity="{ direction: outward, speed: 0.05, spread_degrees: 90 }")
    pdef("trail-spark", "crit specks thrown along the swing tangent from the path (chase the blade)",
         "crit", 1, 8, color=(255, 240, 190), velocity="{ direction: along_tangent, speed: 0.8, spread_degrees: 25 }")
    pdef("debris", "grey stone chips kicked up at the contact's feet (block_crumble)",
         "block_crumble", 4, 18, shape="{ kind: burst, spread_blocks: 0.15 }", color=(128, 128, 128),
         data="{ kind: block, block: stone }", velocity="{ direction: up, speed: 0.3, spread_degrees: 55 }")
    pdef("debris-dark", "dark deepslate chips mixed with the grey ones",
         "block_crumble", 3, 18, shape="{ kind: burst, spread_blocks: 0.15 }", color=(60, 60, 66),
         data="{ kind: block, block: deepslate }", velocity="{ direction: up, speed: 0.25, spread_degrees: 60 }")
    pdef("dust-ground", "warm grey dust puffs at the feet of the contact (small, short)",
         "dust_color_transition", 3, 12, shape="{ kind: burst, spread_blocks: 0.25 }", color=(190, 180, 164),
         data="{ kind: dust_color_transition, from: { r: 200, g: 190, b: 172 }, to: { r: 90, g: 84, b: 76 }, scale: 0.7 }",
         velocity="{ direction: outward, speed: 0.04, spread_degrees: 80 }")
    pdef("dust-dark", "near-black dust at the feet, dark mixed into the grey",
         "dust", 2, 12, shape="{ kind: burst, spread_blocks: 0.25 }", color=(40, 34, 30), size=0.7,
         velocity="{ direction: outward, speed: 0.04, spread_degrees: 80 }")
    for i, r in enumerate((0.55, 0.95, 1.35), 1):
        pdef("wave-%d" % i, "shockwave ring %d of 3 (radius %.2f, flat on the floor, dust spreading outward); hit 2 slam, one ring per tick" % (i, r),
             "dust_color_transition", 1, 10, shape="{ kind: ring, radius_blocks: %s, points: %d }" % (r, 10 + 3 * i),
             color=(200, 190, 170),
             data="{ kind: dust_color_transition, from: { r: 215, g: 205, b: 185 }, to: { r: 110, g: 100, b: 90 }, scale: 0.6 }",
             velocity="{ direction: outward, speed: 0.06, spread_degrees: 25 }")
    pdef("wave-chip", "stone chips thrown outward on the slam ring (block_crumble, outward over the floor)",
         "block_crumble", 1, 14, shape="{ kind: ring, radius_blocks: 0.8, points: 10 }", color=(128, 128, 128),
         data="{ kind: block, block: gravel }", velocity="{ direction: outward, speed: 0.35, spread_degrees: 30 }")
    pdef("converge", "gold trail specks gathering onto the contact (trail flies to the element's trail_to over 4 ticks): 6 points on the front half arc (r7b: the back half reached 1.0 from the caster eye in hit 2)",
         "trail", 1, 6, shape="{ kind: arc, radius_blocks: 0.55, from_deg: -100, to_deg: 100, points: 6 }", color=(255, 226, 140),
         data="{ kind: trail, toward: true, color: { r: 255, g: 226, b: 140 }, duration_ticks: 4 }")


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
H1_KEYS = {3: (185, 25), 4: (196, 40), 5: (278, 68), 6: (345, 33), 7: (6, -15), 10: (43, -15), 11: (73, -15),
           12: (106, -15), 13: (138, -15), 14: (167, -15)}
H2_KEYS = {26: (135, -12), 27: (163, -5), 28: (210, 4), 29: (265, 5), 30: (320, -6), 31: (18, -15), 34: (60, -15),
           35: (102, -15), 36: (134, -12), 37: (159, -9)}
H1_DELAY, H2_DELAY = 3, 27  # skill tick = fx tick + delay
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


def shed(f, keys, delay, fx0, fx1, r0, r1, ahead, eye, pid, dy=0.0):
    """Gold `trail` glints shed off the blade: one per fx tick, spawned on the blade at radius r0 and travelling to the
    blade point `ahead` skill ticks later at radius r1 (= flung forward along the swing and outward), dropped when the
    straight flight (the client lerps linearly to the target) would cut a camera keep-out."""
    for t in range(fx0, fx1 + 1):
        a = on_blade(keys, t + delay, r0, eye)
        b = off(on_blade(keys, t + delay + ahead, r1, eye), 0.0, dy, 0.0)
        if math.dist(b, eye) < EYE_KEEP or math.dist(b, CASTER_EYE) < NEAR or not chord_clear(a, b, eye):
            continue
        f.add("glint", pid, t, t + 1, [a], trail_to=b)


def burst_glints(f, c, eye, shots):
    """Contact glints: (fx tick, start offset, end offset, particle) flown from the contact, keep-out checked."""
    for tick, d0, d1, pid in shots:
        a, b = off(c, *d0), off(c, *d1)
        if math.dist(b, eye) < EYE_KEEP or math.dist(b, CASTER_EYE) < NEAR or not chord_clear(a, b, eye):
            continue
        f.add("glint", pid, tick, tick + 1, [a], trail_to=b)


def hit1():
    f = Fx("s1h1")
    # contact (fx 4 = skill 7): blade phi 6, pitch -15, tip y 0.11 at z 2.74 (under/behind the target). Sparks sit on the
    # contact blade at r 1.85 (just in front of the target's body, 1.38 from the opponent eye, 2.1 from the caster eye);
    # ground grain on the floor below it.
    c = on_blade(H1_KEYS, 7, 1.85)
    g = (c[0], 0.1, c[2])
    # (c) blade path: fx 1 (skill 4, blade behind-up) -> fx 4 contact (chop plane) -> fx 9 floor drag to phi 106.
    # The fx 1 -> 2 chord (behind-up -> overhead) would cut the caster near field and the fx 3 -> 4 chord passes the
    # opponent eye (the chop plane itself is only 0.64 from it): ride() drops such chords, the smear covers that span.
    gold = blade_path(H1_KEYS, H1_DELAY, 1, 9, 2.15, H1_EYE)
    ride(f, "trail-gold", "trail-gold", 1, gold, 0.2, H1_EYE)
    ride(f, "trail-dark", "trail-dark", 1, blade_path(H1_KEYS, H1_DELAY, 1, 9, 2.45, H1_EYE), 0.25, H1_EYE)
    ride(f, "trail-gold", "trail-gold", 2, blade_path(H1_KEYS, H1_DELAY, 2, 6, 2.0, H1_EYE, lag=0.5), 0.3, H1_EYE)
    shed(f, H1_KEYS, H1_DELAY, 1, 8, 2.15, 2.55, 0.7, H1_EYE, "glint")
    shed(f, H1_KEYS, H1_DELAY, 2, 7, 1.85, 2.2, 0.45, H1_EYE, "glint-hot", dy=0.08)
    ride(f, "trail-spark", "trail-spark", 2, blade_path(H1_KEYS, H1_DELAY, 2, 7, 2.3, H1_EYE), 0.45, H1_EYE)
    # inner line (r 1.5) carries the last 50 deg of the chop into the contact, fx 3 -> 4 (-> 6 drag), where the outer
    # chords were dropped: 1.1 from the opponent eye, 1.65 from the caster eye
    inner = blade_path(H1_KEYS, H1_DELAY, 3, 6, 1.5, H1_EYE)
    ride(f, "trail-gold", "trail-gold", 3, inner, 0.12, H1_EYE)
    ride(f, "trail-dark", "trail-dark", 3, [off(q, 0.0, -0.06, 0.0) for q in inner], 0.16, H1_EYE)
    # convergence: 6 specks from above-behind the contact dive onto it (fx 2, 3)
    top = off(c, 0, 0.45, 0)
    f.add("converge", "converge", 2, 4, [top, top], trail_to=c)
    # (a) sparks at the contact, staggered fx 4 / 5 / 6, positions never equal
    f.at("crit", "crit", 4, c)
    f.at("crit", "crit", 5, off(c, 0.12, 0.08, -0.08))
    f.at("crit", "crit", 6, off(c, -0.12, -0.05, -0.12))
    f.at("spark", "spark", 4, off(c, 0, 0, -0.1))
    f.at("spark", "spark", 5, off(c, -0.1, 0.06, -0.15))
    f.at("spark", "spark", 6, off(c, 0.1, -0.05, -0.1))
    f.at("spark-up", "spark-up", 4, off(c, 0.05, 0.05, -0.05))
    f.at("spark-up", "spark-up", 5, off(c, -0.12, 0.08, -0.1))
    f.at("nugget", "nugget", 4, off(c, 0, 0, -0.15))
    f.at("nugget", "nugget", 5, off(c, 0.1, 0.05, -0.2))
    f.at("coal", "coal", 4, off(c, -0.1, 0, -0.2))
    f.at("coal", "coal", 5, off(c, 0.12, -0.05, -0.15))
    f.at("coal", "coal", 6, off(c, 0, 0.1, -0.15))
    # glints flung up and out of the contact, staggered fx 4-7, each flies its own line and dies (4-9 ticks)
    burst_glints(f, c, H1_EYE, (
        (4, (0.05, 0.05, -0.05), (0.75, 0.55, -0.35), "glint-rise"),
        (4, (-0.05, 0.0, -0.1), (-0.8, 0.45, -0.3), "glint-rise"),
        (4, (0.0, 0.05, -0.1), (0.35, 0.3, -0.55), "glint-hot"),
        (5, (0.1, 0.05, -0.1), (1.0, 0.35, -0.1), "glint-rise"),
        (5, (-0.1, 0.05, -0.15), (-0.55, 0.7, -0.45), "glint-rise"),
        (6, (0.05, 0.1, -0.1), (0.5, 0.8, -0.6), "glint"),
        (6, (-0.05, 0.0, -0.1), (-1.0, 0.25, -0.2), "glint"),
        (7, (0.0, 0.1, -0.15), (-0.3, 0.75, -0.7), "glint"),
    ))
    # (b) embers drifting up from the contact for 7 ticks (the emitter rises 0.07/tick, back 0.03/tick)
    rise = [(c[0], c[1] + 0.07 * i, c[2] - 0.03 * i) for i in range(7)]
    f.add("ember", "ember", 4, 11, rise)
    # (d) ground debris kicked up where the blade bites the floor
    f.at("debris", "debris", 4, g)
    f.at("debris", "debris", 5, off(g, -0.2, 0, -0.1))
    f.at("debris-dark", "debris-dark", 4, off(g, 0.2, 0, -0.05))
    f.at("debris-dark", "debris-dark", 6, off(g, 0.0, 0, -0.2))
    f.at("dust-ground", "dust-ground", 4, g)
    f.at("dust-ground", "dust-ground", 5, off(g, 0.3, 0, -0.15))
    f.at("dust-ground", "dust-ground", 6, off(g, -0.3, 0, -0.15))
    f.at("dust-dark", "dust-dark", 5, off(g, -0.1, 0, -0.05))
    f.at("dust-dark", "dust-dark", 6, off(g, 0.2, 0, -0.05))
    f.at("dust-dark", "dust-dark", 7, off(g, 0.0, 0, -0.3))
    return f


def hit2():
    f = Fx("s1h2")
    # contact (fx 4 = skill 31): blade phi 18, pitch -15, tip (0.41, 0.11, 2.63) passes under/behind the target (dash
    # stop 1.7). Sparks sit on the contact blade at r 1.2, in front of the target's body (1.2 from the opponent eye,
    # 1.8 from the caster eye); ground grain on the floor below it.
    c = on_blade(H2_KEYS, 31, 1.2)
    g = (c[0] + 0.05, 0.1, c[2] - 0.05)
    # (c) blade path on the low ring: fx 1 (skill 28, phi 210 behind-right) -> fx 4 contact phi 18 -> fx 8 phi 102.
    gold = blade_path(H2_KEYS, H2_DELAY, 1, 8, 2.15, H2_EYE)
    ride(f, "trail-gold", "trail-gold", 1, gold, 0.3, H2_EYE)
    ride(f, "trail-dark", "trail-dark", 1, blade_path(H2_KEYS, H2_DELAY, 1, 8, 2.45, H2_EYE), 0.32, H2_EYE)
    ride(f, "trail-gold", "trail-gold", 2, blade_path(H2_KEYS, H2_DELAY, 2, 5, 2.0, H2_EYE, lag=0.5), 0.35, H2_EYE)
    shed(f, H2_KEYS, H2_DELAY, 1, 8, 2.15, 2.6, 0.7, H2_EYE, "glint")
    shed(f, H2_KEYS, H2_DELAY, 2, 7, 1.9, 2.3, 0.45, H2_EYE, "glint-hot", dy=0.1)
    ride(f, "trail-spark", "trail-spark", 2, blade_path(H2_KEYS, H2_DELAY, 2, 7, 2.3, H2_EYE), 0.45, H2_EYE)
    # second, higher blade line (hit 2 is the heavy one): gold + dark 0.3 higher, one tick later, plus a glint shed
    hi = [off(p, 0.0, 0.3, 0.0) for p in blade_path(H2_KEYS, H2_DELAY, 1, 8, 2.3, H2_EYE)]
    for pid, st in (("trail-gold", 0.36), ("trail-dark", 0.4)):
        ride(f, pid, pid, 2, hi, st, H2_EYE)
    shed(f, H2_KEYS, H2_DELAY, 2, 8, 2.3, 2.75, 0.6, H2_EYE, "glint", dy=0.35)
    # convergence (front half arc, r 0.55: the caster is only 1.4 behind the contact, so no specks on the caster side)
    f.add("converge", "converge", 2, 4, [off(c, 0, 0.3, 0)] * 2, trail_to=c)
    f.add("converge", "converge", 3, 5, [off(c, 0, 0.35, 0)] * 2, trail_to=off(c, 0, 0.1, 0))
    for tick, d in ((4, (0, 0, -0.05)), (4, (0.12, 0.08, -0.1)), (5, (-0.1, -0.05, -0.1)), (5, (0.05, 0.05, -0.15))):
        f.at("crit", "crit", tick, off(c, *d))
    f.at("crit", "crit", 6, off(c, -0.15, 0.1, -0.1))
    f.at("crit", "crit", 7, off(c, 0.15, -0.05, -0.1))
    for tick, d in ((4, (0, 0, -0.1)), (4, (0.15, 0.08, -0.2)), (5, (-0.1, 0.05, -0.1)), (5, (0.1, -0.05, -0.2)), (6, (0.1, -0.05, -0.1)), (7, (-0.1, 0.05, -0.1))):
        f.at("spark", "spark", tick, off(c, *d))
    for tick, d in ((4, (0.05, 0.05, 0)), (5, (-0.1, 0.1, -0.05)), (6, (0.1, 0.08, -0.1))):
        f.at("spark-up", "spark-up", tick, off(c, *d))
    for tick, d in ((4, (0, 0, -0.15)), (4, (-0.15, 0.05, -0.2)), (5, (0.1, 0.05, -0.2)), (6, (0.0, -0.05, -0.2))):
        f.at("nugget", "nugget", tick, off(c, *d))
    for tick, d in ((4, (-0.1, 0, -0.2)), (4, (0.15, 0.05, -0.1)), (5, (0.12, -0.05, -0.15)), (6, (0, 0.1, -0.15)), (7, (-0.1, 0.0, -0.1))):
        f.at("coal", "coal", tick, off(c, *d))
    # glints flung up and sideways out of the contact (never back toward the caster, 1.8 behind), fx 4-8
    burst_glints(f, c, H2_EYE, (
        (4, (0.05, 0.05, 0.0), (0.9, 0.5, 0.2), "glint-rise"),
        (4, (-0.05, 0.05, 0.0), (-0.9, 0.55, 0.15), "glint-rise"),
        (4, (0.0, 0.0, 0.05), (0.4, 0.25, 0.55), "glint-hot"),
        (5, (0.1, 0.05, 0.0), (1.2, 0.3, 0.4), "glint-rise"),
        (5, (-0.1, 0.05, 0.05), (-0.6, 0.75, 0.3), "glint-rise"),
        (5, (0.0, 0.05, 0.05), (-0.35, 0.2, 0.6), "glint-hot"),
        (6, (0.05, 0.1, 0.05), (0.55, 0.85, 0.35), "glint"),
        (6, (-0.05, 0.05, 0.0), (-1.15, 0.3, 0.25), "glint"),
        (7, (0.1, 0.05, 0.05), (0.8, 0.6, 0.6), "glint-rise"),
        (8, (-0.1, 0.05, 0.05), (-0.5, 0.7, 0.55), "glint"),
    ))
    # embers rise straight up (no drift toward the caster: the contact is only 1.8 from the caster eye)
    rise = [(c[0], c[1] + 0.045 * i, c[2]) for i in range(8)]
    f.add("ember", "ember", 4, 12, rise)
    f.add("ember", "ember", 5, 13, [off(p, 0.25, 0.0, -0.05) for p in rise])
    # (d) ground debris below the contact + (e) shockwave rings centred under the ring smear (the pivot's floor point)
    f.at("debris", "debris", 4, g)
    f.at("debris", "debris", 5, off(g, -0.25, 0, 0.05))
    f.at("debris", "debris", 6, off(g, 0.25, 0, 0.0))
    f.at("debris-dark", "debris-dark", 4, off(g, 0.2, 0, 0.0))
    f.at("debris-dark", "debris-dark", 5, off(g, -0.1, 0, -0.1))
    f.at("debris-dark", "debris-dark", 6, off(g, 0.0, 0, 0.1))
    f.at("dust-ground", "dust-ground", 4, g)
    f.at("dust-ground", "dust-ground", 5, off(g, 0.35, 0, -0.2))
    f.at("dust-ground", "dust-ground", 6, off(g, -0.35, 0, -0.2))
    f.at("dust-ground", "dust-ground", 7, off(g, 0.0, 0, -0.3))
    for tick, d in ((5, (-0.1, 0, 0)), (6, (0.2, 0, 0)), (7, (0.0, 0, -0.2)), (8, (-0.2, 0, -0.1))):
        f.at("dust-dark", "dust-dark", tick, off(g, *d))
    ring_c = (PIVOT[0], 0.08, PIVOT[2])
    f.at("wave-1", "wave-1", 4, ring_c)
    f.at("wave-2", "wave-2", 5, ring_c)
    f.at("wave-3", "wave-3", 6, ring_c)
    f.at("wave-chip", "wave-chip", 5, ring_c)
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
