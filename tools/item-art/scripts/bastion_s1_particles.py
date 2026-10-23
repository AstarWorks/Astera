#!/usr/bin/env python3
"""Bastion S1 vanilla particle layer (round 5): particle files + particle elements appended to the fx files.

Run AFTER tools/item-art/scripts/bastion_s1_fx.py (that script rewrites the fx files from scratch and drops the
marked particle block); this script removes its own previous block and re-appends it, so it is idempotent:

    python3 tools/item-art/scripts/bastion_s1_particles.py

Particle elements cost no display budget. Everything is tick-exact relative to the fx clock (contact = effect tick 4).
Frame: x = left, y = up, z = forward, origin at the caster's feet; the enemy is at z 2.4 (hit 1) / 1.7 (hit 2) and the
opponent camera sits ON the enemy, so nothing is emitted closer than ~0.6 blocks to that point.
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
    pdef("crit", "white-gold crit sparks flung in every direction from the contact (crit: v = 0.4 * speed, friction 0.7)",
         "crit", 5, 8, color=(255, 240, 190), velocity="{ direction: outward, speed: 1.0, spread_degrees: 0 }")
    pdef("spark", "electric_spark streaks (life 2-3 ticks, v = 0.25 * speed) fired forward in a cone: reads from compare/side",
         "electric_spark", 4, 3, color=(255, 240, 190), velocity="{ direction: from_caster_look, speed: 1.8, spread_degrees: 55 }")
    pdef("spark-up", "electric_spark streaks fired up in a wide cone: reads from the opponent and caster views",
         "electric_spark", 3, 3, color=(255, 240, 190), velocity="{ direction: up, speed: 1.6, spread_degrees: 70 }")
    pdef("nugget", "bright gold chips (item particle: v = speed, gravity) flung outward",
         "item", 3, 12, color=GOLD, data="{ kind: item, item: gold_nugget }", velocity="{ direction: outward, speed: 0.28, spread_degrees: 0 }")
    pdef("coal", "dark chips mixed with the gold (item particle charcoal)",
         "item", 3, 12, color=(30, 24, 20), data="{ kind: item, item: charcoal }", velocity="{ direction: outward, speed: 0.22, spread_degrees: 0 }")
    pdef("firework", "a few long-lived white-gold spark flecks that arc up and fall (firework: v = speed, gravity 0.1, friction 0.91; lives ~2 s, keep count low)",
         "firework", 2, 20, color=(255, 236, 170), velocity="{ direction: up, speed: 0.2, spread_degrees: 65 }")
    pdef("ember", "orange to dark-red ember dust that hangs and drifts up (dust life = 8/U * scale, ~2-22 ticks at 0.55)",
         "dust_color_transition", 3, 14, color=(255, 150, 40),
         data="{ kind: dust_color_transition, from: { r: 255, g: 175, b: 55 }, to: { r: 150, g: 40, b: 10 }, scale: 0.55 }",
         velocity="{ direction: up, speed: 0.05, spread_degrees: 90 }")
    pdef("glow", "wax_on gold motes: flicker, 10-40 ticks, drift up slowly (wax_on scales given velocity by 0.005, hence speed 4)",
         "wax_on", 2, 20, color=GOLD, velocity="{ direction: up, speed: 4.0, spread_degrees: 80 }")
    pdef("trail-gold", "gold dust left along the blade path (one per path point)",
         "dust", 1, 12, color=GOLD, size=0.5, velocity="{ direction: outward, speed: 0.05, spread_degrees: 90 }")
    pdef("trail-dark", "near-black dust mixed into the gold path (reads on bright backdrops)",
         "dust", 1, 12, color=(26, 18, 6), size=0.55, velocity="{ direction: outward, speed: 0.05, spread_degrees: 90 }")
    pdef("trail-glint", "wax_on glint along the blade path",
         "wax_on", 1, 20, color=GOLD, velocity="{ direction: outward, speed: 3.0, spread_degrees: 0 }")
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
    pdef("converge", "gold trail specks gathering onto the contact (trail flies to the element's trail_to over 4 ticks): 6 points on the back half arc",
         "trail", 1, 6, shape="{ kind: arc, radius_blocks: 0.75, from_deg: 100, to_deg: 260, points: 6 }", color=(255, 226, 140),
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


def arc_path(phis, ys, radius, cz):
    out = []
    for phi, y in zip(phis, ys):
        a = math.radians(phi)
        out.append((radius * math.sin(a), y, cz + radius * math.cos(a)))
    return out


def off(p, dx=0.0, dy=0.0, dz=0.0):
    return (p[0] + dx, p[1] + dy, p[2] + dz)


def hit1():
    f = Fx("s1h1")
    c = (0.0, 1.2, 1.5)  # contact: 0.9 in front of the opponent camera
    # arc layer (c) + glint motes (f): emitters ride the blade path (animator tip phi, mid-blade radius 1.4), e2..e8,
    # along_path fills every 0.2 blocks between ticks so each tick leaves a streak of points.
    path = arc_path([248, 322, 360, 410, 455, 492, 525], [0.95, 1.05, 1.15, 1.0, 0.8, 0.6, 0.45], 1.4, 0.25)
    for pid in ("trail-gold", "trail-dark", "trail-glint", "trail-spark"):
        f.add(pid, pid, 2, 9, path, along=True, step=0.2)
    # convergence onto the contact before the flash (e2, e3)
    f.add("converge", "converge", 2, 4, [c, c], trail_to=c)
    # (a) sparks at contact, staggered e4 / e5 / e6, positions never equal
    f.at("crit", "crit", 4, c)
    f.at("crit", "crit", 5, off(c, 0.12, 0.14, -0.05))
    f.at("crit", "crit", 6, off(c, -0.1, -0.1, -0.1))
    f.at("spark", "spark", 4, off(c, 0, 0, -0.1))
    f.at("spark", "spark", 5, off(c, -0.1, 0.1, -0.1))
    f.at("spark", "spark", 6, off(c, 0.1, -0.05, -0.1))
    f.at("spark-up", "spark-up", 4, off(c, 0.05, 0.1))
    f.at("spark-up", "spark-up", 5, off(c, -0.1, 0.2))
    f.at("nugget", "nugget", 4, off(c, 0, 0, -0.15))
    f.at("nugget", "nugget", 5, off(c, 0.1, 0.1, -0.2))
    f.at("coal", "coal", 4, off(c, -0.1, 0, -0.2))
    f.at("coal", "coal", 5, off(c, 0.12, -0.1, -0.15))
    f.at("coal", "coal", 6, off(c, 0, 0.15, -0.15))
    f.at("firework", "firework", 5, off(c, 0, 0.1, -0.1))
    f.at("firework", "firework", 6, off(c, 0.1, 0.0, -0.2))
    # (b) embers + glow motes drifting up from the contact for 7 ticks (the emitter rises)
    rise = [(0.0, 1.2 + 0.07 * i, 1.4 - 0.03 * i) for i in range(7)]
    f.add("ember", "ember", 4, 11, rise)
    f.add("glow", "glow", 4, 11, [off(p, 0.12, 0.0, 0.0) for p in rise])
    # (d) ground debris kicked at the feet of the contact
    g = (0.0, 0.1, 1.3)
    f.at("debris", "debris", 4, g)
    f.at("debris", "debris", 5, off(g, -0.2, 0, 0.1))
    f.at("debris-dark", "debris-dark", 4, off(g, 0.2, 0, 0.05))
    f.at("debris-dark", "debris-dark", 6, off(g, 0.0, 0, -0.1))
    f.at("dust-ground", "dust-ground", 4, g)
    f.at("dust-ground", "dust-ground", 5, off(g, 0.3, 0, -0.1))
    f.at("dust-ground", "dust-ground", 6, off(g, -0.3, 0, -0.1))
    f.at("dust-dark", "dust-dark", 5, off(g, -0.1, 0, 0.05))
    f.at("dust-dark", "dust-dark", 6, off(g, 0.2, 0, 0.05))
    f.at("dust-dark", "dust-dark", 7, off(g, 0.0, 0, -0.2))
    return f


def hit2():
    f = Fx("s1h2")
    c = (0.0, 0.95, 1.1)  # contact: 0.6 in front of the opponent camera (enemy at z 1.7)
    path = arc_path([235, 315, 360, 410, 455, 495, 530], [0.3, 0.4, 0.55, 0.45, 0.3, 0.3, 0.15], 1.2, 0.2)
    for pid in ("trail-gold", "trail-dark", "trail-glint", "trail-spark"):
        f.add(pid, pid, 2, 9, path, along=True, step=0.2)
    # second, higher blade line (hit 2 is the heavy one): dark + gold again 0.35 higher, one tick later
    hi = [off(p, 0.0, 0.35, 0.0) for p in path]
    for pid in ("trail-gold", "trail-dark", "trail-glint"):
        f.add(pid, pid, 3, 10, hi, along=True, step=0.25)
    f.add("converge", "converge", 2, 4, [c, c], trail_to=c)
    f.add("converge", "converge", 3, 5, [off(c, 0, 0.3, 0), off(c, 0, 0.3, 0)], trail_to=off(c, 0, 0.1, 0))
    for tick, d in ((4, (0, 0, -0.05)), (4, (0.12, 0.14, -0.1)), (5, (-0.1, -0.1, -0.1)), (5, (0.05, 0.05, -0.15))):
        f.at("crit", "crit", tick, off(c, *d))
    f.at("crit", "crit", 6, off(c, -0.15, 0.2, -0.1))
    f.at("crit", "crit", 7, off(c, 0.15, -0.1, -0.1))
    for tick, d in ((4, (0, 0, -0.1)), (4, (0.15, 0.15, -0.2)), (5, (-0.1, 0.1, -0.1)), (5, (0.1, -0.1, -0.2)), (6, (0.1, -0.05, -0.1)), (7, (-0.1, 0.1, -0.1))):
        f.at("spark", "spark", tick, off(c, *d))
    for tick, d in ((4, (0.05, 0.1, 0)), (5, (-0.1, 0.2, 0)), (6, (0.1, 0.15, -0.1))):
        f.at("spark-up", "spark-up", tick, off(c, *d))
    for tick, d in ((4, (0, 0, -0.15)), (4, (-0.15, 0.1, -0.2)), (5, (0.1, 0.1, -0.2)), (6, (0.0, -0.1, -0.2))):
        f.at("nugget", "nugget", tick, off(c, *d))
    for tick, d in ((4, (-0.1, 0, -0.2)), (4, (0.15, 0.1, -0.1)), (5, (0.12, -0.1, -0.15)), (6, (0, 0.15, -0.15)), (7, (-0.1, 0.0, -0.1))):
        f.at("coal", "coal", tick, off(c, *d))
    f.at("firework", "firework", 5, off(c, 0, 0.1, -0.1))
    f.at("firework", "firework", 6, off(c, 0.1, 0.0, -0.2))
    f.at("firework", "firework", 7, off(c, -0.1, 0.1, -0.1))
    rise = [(0.0, 0.95 + 0.08 * i, 1.0 - 0.03 * i) for i in range(8)]
    f.add("ember", "ember", 4, 12, rise)
    f.add("ember", "ember", 5, 13, [off(p, 0.2, 0.0, -0.1) for p in rise])
    f.add("glow", "glow", 4, 12, [off(p, -0.15, 0.0, 0.0) for p in rise])
    f.add("glow", "glow", 6, 12, [off(p, 0.2, 0.05, -0.1) for p in rise][:6])
    # (d) ground debris + (e) shockwave rings (one ring radius per tick, centred in front of the caster)
    g = (0.0, 0.1, 1.0)
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
    ring_c = (0.0, 0.08, 0.3)
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
