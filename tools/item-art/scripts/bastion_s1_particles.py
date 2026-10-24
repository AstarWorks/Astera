#!/usr/bin/env python3
"""Bastion S1 particle layer (r16: retextured vanilla particle channels, ADR-0252): particle files + particle and
sprite-emitter elements appended to the fx files.

Run AFTER tools/item-art/scripts/bastion_s1_fx.py (that script rewrites the fx files from scratch and drops the
marked particle block); this script removes its own previous block and re-appends it, so it is idempotent:

    python3 tools/item-art/scripts/bastion_s1_particles.py

r16 (reviewer r11: hit 1 showed ~8 glints vs dozens in the reference): the volume now comes from the three skinned channels
(content/particle-skins/), which cost no display: `spark` = firework (exact velocity, friction 0.91, visible ~12-15 ticks),
`ember` = entity_effect (server tint; horizontal velocity is RANDOM in the client, only `up` keeps them near-still, then they
rise), `smoke` = cloud (unused here: big light puffs, pulled toward a player within 2 blocks). Directions are fx-frame
vectors delivered through `along_tangent` on a 2+-keyframe element that creeps 0.02/tick along the wanted direction, so they
turn with the caster's yaw (a particle file's `custom` direction is world-fixed). Display sprites stay only for the dark
slivers (the channels cannot be dark) and a few orange streaks.
Frame: x = left, y = up, z = forward, origin at the caster's feet; the opponent camera sits ON the enemy (hit 1 eye z
2.0-2.4, hit 2 eye z 1.7-2.0): every trajectory keeps EYE_KEEP (0.85) from those eyes and NEAR (1.5) from the caster eye
(0, 1.62, 0); sparks stay at y >= 0.6 while visible. report() checks all of it with the client's own per-type maths.
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








def off(p, dx=0.0, dy=0.0, dz=0.0):
    return (p[0] + dx, p[1] + dy, p[2] + dz)


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




def h1_point(tick, r, inset):
    """Point on the r10 tip plane at horizontal radius min(r, ring outer edge - inset) for (fractional) skill tick."""
    al = h1_alpha(tick)
    rr = r if inset is None else min(r, float(_np.interp(al, _RA, _RR)) - inset)  # None: on the blade, not the smear
    x, z = H1_C[0] + rr * math.cos(math.radians(al)), rr * math.sin(math.radians(al))
    return (x, H1_C[1] + _T20 * z, z)


SPARK, STREAK_TINT = "bastion-s1-spark", "'#FF8A1E'"
SPARK_SOL = [(0, 0.6, None), (0.2, 1.0, "out_quad"), (1, 0.0, "in_quad")]
STREAK_SOL = [(0, 0.5, None), (0.15, 1.0, "out_expo"), (1, 0.0, "in_quad")]
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
# r16: the channel layer (ADR-0252). Particle files are generated per (channel, count, speed, spread, jitter); elements
# carry position and direction. CHAN_EMITS records every planned emission for the report (client maths per type).
# ---------------------------------------------------------------------------------------------------------
CH = {"spark": "firework", "ember": "entity_effect"}
EMBER = (255, 140, 40)
EMBER_HOT = (255, 196, 80)
EMBER_DEEP = (226, 84, 26)
DEFS = {}
CHAN_EMITS = []  # dict(fx, tick, at, dir, def)


def chdef(name, channel, count, speed, spread, jitter, direction="along_tangent", tint=None, comment=""):
    data, color = None, (255, 255, 255)
    if tint:
        data, color = "{ kind: tint, color: %s }" % rgb(tint), tint
    life = {"spark": 14, "ember": 24}[channel]
    vel = "{ direction: %s, speed: %s, spread_degrees: %s, speed_jitter: %s }" % (direction, speed, spread, jitter)
    pdef(name, comment, CH[channel], count, life, color=color, data=data, velocity=vel)
    DEFS[name] = dict(channel=channel, count=count, speed=speed, spread=spread, jitter=jitter, direction=direction)
    return name


def channel_defs():
    """Remove every previous bastion-s1-p-* file (r12 left none; earlier rounds' names) and write this round's set."""
    for old in PART.glob("bastion-s1-p-*.yaml"):
        old.unlink()
    S, E = "spark", "ember"
    chdef("spray", S, 3, 0.3, 16, 0.45, comment="r16 hit spray: retextured firework sparks (ADR-0252) thrown along the element's creep direction.")
    chdef("fan", S, 3, 0.22, 28, 0.4, comment="r16 upward fan: firework sparks rising out of the contact.")
    chdef("shed", S, 2, 0.17, 25, 0.4, comment="r16 ring shed: one firework spark per path point, flung along the sweep tangent.")
    chdef("ember", E, 1, 0.05, 0, 0.5, "up", EMBER, "r16 ember: retextured entity_effect, orange; `up` with spread 0 (x = z = 0 exactly) makes the client scale its random sideways drift x0.1, then it rises.")
    chdef("ember-hot", E, 1, 0.05, 0, 0.5, "up", EMBER_HOT, "r16 ember: retextured entity_effect, gold-white hot core.")
    chdef("ember-deep", E, 1, 0.05, 0, 0.5, "up", EMBER_DEEP, "r16 ember: retextured entity_effect, deep red-orange (cooling).")


def _path_between(a, b, step):
    d = math.dist(a, b)
    if d < 1e-9:
        return []
    n = max(1, min(24, math.ceil(d / step)))
    return [tuple(a[k] + (b[k] - a[k]) * i / n for k in range(3)) for i in range(1, n + 1)]


def chan(f, name, pid, start, pts, every=1, along=False, step=0.3):
    """A particle element over `pts` (one keyframe per tick); records the planned emissions (VfxTimeline maths)."""
    eid = f.add(name, pid, start, start + len(pts), pts, every=every, along=along, step=step)
    life = len(pts)
    for u in range(0, life, every):
        at = pts[u]
        nxt = tuple(pts[u + 1][k] - at[k] for k in range(3)) if u + 1 < life else tuple(at[k] - pts[max(u - 1, 0)][k] for k in range(3))
        pp = _path_between(pts[max(u - every, 0)], at, step) if along and u > 0 else []
        for q in (pp or [at]):
            CHAN_EMITS.append(dict(fx=f.tag, eid=eid, tick=start + u, at=q, dir=nxt, d=DEFS[pid]))
    return eid


def spray(f, name, pid, tick, p, d, ticks=1):
    """Directed burst(s): the element creeps 0.02/tick along d (fx frame) so along_tangent = d, turned with the yaw."""
    L = math.sqrt(sum(x * x for x in d))
    u = [x / L for x in d]
    pts = [tuple(p[k] + 0.02 * i * u[k] for k in range(3)) for i in range(max(ticks, 2))]
    if ticks == 1:  # one emission: life 2, every 2 (a 1-tick element has no tangent)
        return chan(f, name, pid, tick, pts, every=2)
    return chan(f, name, pid, tick, pts)


def scatter(f, name, pid, tick, box, ticks, seed, step=0.28):
    """Embers spread over a box: the element hops between random points each tick and along_path fills the chord, so
    every tick drops a staggered line of embers across the zone."""
    import random
    rnd = random.Random(seed)
    pts = []
    for _ in range(20000):
        if len(pts) == ticks:
            break
        q = tuple(rnd.uniform(lo, hi) for lo, hi in box)
        if ember_ok(q, EYES[f.tag]) and (not pts or all(ember_ok(c, EYES[f.tag]) for c in _path_between(pts[-1], q, 0.1))):
            pts.append(q)
    return chan(f, name, pid, tick, pts, along=True, step=step)


def eyes_clear(q, eyes, keep=EYE_KEEP, near=NEAR):
    return all(math.dist(q, e) >= keep for e in eyes) and math.dist(q, CASTER_EYE) >= near


def ember_ok(q, eyes):
    """An `up` ember drifts <= 0.45 sideways and rises up to ~2.5 blocks: its whole column (+ 0.4 drift) must clear the
    eyes."""
    def col(e, keep):
        h = math.hypot(q[0] - e[0], q[2] - e[2])
        return h >= keep + 0.4 or q[1] >= e[1] + keep
    return col(CASTER_EYE, NEAR) and all(col(e, EYE_KEEP) for e in eyes)


def ring_embers(f, name, pid, fx0, pts, eyes, step):
    """along_path ember emitters over the runs of path points (and chords) whose columns clear every eye."""
    run, t0 = [], fx0
    for i, q in enumerate(pts + [None]):
        ok = q is not None and ember_ok(q, eyes) and (not run or all(ember_ok(c, eyes) for c in _path_between(run[-1], q, 0.1)))
        if ok:
            if not run:
                t0 = fx0 + i
            run.append(q)
            continue
        if len(run) >= 2:
            chan(f, name, pid, t0, run, along=True, step=step)
        run = [q] if q is not None and ember_ok(q, eyes) else []
        t0 = fx0 + i


def spark_safe(at, d, pid, eyes, n=120):
    """Every sampled spark of this emission (cone x speed extremes, client maths) keeps the keep-outs and y >= 0.6."""
    import random
    rnd = random.Random(3)
    df = DEFS[pid]
    for _ in range(n):
        dv = _cone(d, df["spread"], rnd)
        for sp in (df["speed"] * (1 - df["jitter"]), df["speed"] * (1 + df["jitter"])):
            p, v = list(at), [x * sp for x in dv]
            for _ in range(15):
                v[1] -= 0.004
                p = [p[k] + v[k] for k in range(3)]
                v = [x * 0.91 for x in v]
                if p[1] < 0.6 or math.dist(p, CASTER_EYE) < 1.5 or any(math.dist(p, e) < EYE_KEEP for e in eyes):
                    return False
    return True


def ring_sheds(f, name, pid, fx0, pts, centre, eyes, step, lift):
    """Sparks shed along the sweep: one 1-tick spray per `step` blocks of each tick's chord, flung along the sweep tangent
    + outward from the ring centre + `lift` up; emissions whose sparks could cut a keep-out are dropped."""
    for i in range(1, len(pts)):
        a, b = pts[i - 1], pts[i]
        tg = [b[k] - a[k] for k in range(3)]
        L = math.sqrt(sum(x * x for x in tg))
        if L < 1e-6:
            continue
        for q in _path_between(a, b, step):
            o = (q[0] - centre[0], 0.0, q[2] - centre[2])
            lo = math.hypot(o[0], o[2]) or 1.0
            d = (tg[0] / L + 0.45 * o[0] / lo, tg[1] / L + lift, tg[2] / L + 0.45 * o[2] / lo)
            if spark_safe(q, d, pid, eyes):
                spray(f, name, pid, fx0 + i, q, d)


H1_EYES = [(0.0, 1.62, z) for z in (2.0, 2.2, 2.4)]
H2_EYES = [(0.0, 1.62, z) for z in (1.7, 2.0)]
EYES = {"s1h1": H1_EYES, "s1h2": H2_EYES}


def hit1():
    """r16. Contact fx 4 (skill 8) at c = (-0.44, 1.54, 1.59) (the display flash). Sparks fly to the caster's right (-x) and
    up, never across the +x gap between the two eye columns; embers fill the right-hand contact zone and the ring's right
    and left flanks (the front of the ring is inside the opponent-eye keep-out)."""
    f = Fx("s1h1")
    # ring sheds: one spark per 0.32 blocks along the smear edge while the blade sweeps (skill 6-12 = fx 2-8)
    ring = [h1_point(t + H1_DELAY, 2.4, 0.12) for t in range(2, 9)]
    ring_sheds(f, "shed", "shed", 2, ring, H1_C, H1_EYES, 0.4, 0.5)
    # contact sprays: three waves to the right, fanned forward / level / back, start on the cut line right of the flash
    for i, (tick, p, d) in enumerate((
            (4, (-0.72, 1.62, 1.55), (-1.0, 0.18, 0.15)),
            (4, (-0.75, 1.55, 1.45), (-1.0, 0.25, -0.25)),
            (5, (-0.8, 1.7, 1.5), (-1.0, 0.3, 0.0)),
            (5, (-0.78, 1.5, 1.55), (-1.0, 0.3, 0.3)),
            (6, (-0.85, 1.6, 1.4), (-1.0, 0.2, -0.1)),
            (7, (-0.9, 1.55, 1.45), (-1.0, 0.35, 0.1)))):
        spray(f, "spray", "spray", tick, p, d, ticks=2 if i < 2 else 1)
    # upward fan out of the contact's right half (fx 4-6)
    spray(f, "fan", "fan", 4, (-1.05, 1.7, 1.35), (-0.35, 1.0, -0.05), ticks=3)
    # follow-through: the blade leaves the target (skill 11-12 = fx 7-8) and throws a last wave off the left flank
    spray(f, "spray", "spray", 8, h1_point(11 + 0.5, 2.0, 0.3), (0.6, 0.35, -0.75), ticks=2)
    # embers: right contact zone (fx 4-8, ~5 per tick), right flank of the ring (fx 3-6), left flank on the follow-through
    scatter(f, "ember", "ember", 4, ((-2.0, -1.25), (1.2, 1.9), (1.15, 1.8)), 6, 11, 0.22)
    scatter(f, "ember-hot", "ember-hot", 4, ((-1.6, -1.2), (1.4, 1.8), (1.3, 1.7)), 3, 12)
    scatter(f, "ember-deep", "ember-deep", 6, ((-2.2, -1.3), (1.0, 1.6), (1.1, 1.9)), 5, 13)
    ring_embers(f, "ember-ring", "ember", 3, [h1_point(t + H1_DELAY, 2.2, 0.25) for t in range(3, 7)], H1_EYES, 0.35)
    ring_embers(f, "ember-ring", "ember-deep", 7, [h1_point(t + H1_DELAY + 0.5, 2.1, 0.3) for t in range(7, 10)], H1_EYES, 0.35)
    # display sprites: orange streaks along the cut (5) and dark brown slivers (6), the parts the channels cannot draw
    c = (-0.44, 1.54, 1.59)
    s = off(c, 0, 0.15, -0.1)
    f.els.append(emitter("s1h1-p-streaks", "bastion-s1-streak", STREAK_TINT, 4, off(c, 0, -0.1, -0.15), 5, 2, (0.35, 0.5),
                         "custom[-1, 0.2, 0.25]", 25, 0.0, 0.2, (3, 4), (0.07, 0.1), STREAK_SOL, 0))
    f.els.append(emitter("s1h1-p-slivers", "bastion-s1-sliver", SLIVER_TINT, 4, off(s, -0.3, 0, -0.05), 6, 2, (0.18, 0.26),
                         "custom[-1, 0.8, 0.1]", 40, 0.03, 0.08, (4, 5), (0.04, 0.06), SLIVER_SOL, 0))
    return f


def hit2():
    """r16. Contact fx 4 (skill 31) on the low blade at c = (0.02, 0.46, 1.40), 1.2 under the opponent eye: sparks spray
    sideways (+-x) from 0.45-0.5 off the centre line at y ~1.0 with a little lift; embers fill both side zones; the ring
    sweep (skill 28-36) sheds sparks along its tangent where it is clear of the eyes and above y 0.75."""
    f = Fx("s1h2")
    c = on_blade(H2_KEYS, 31, 1.2)

    def ringpt(t, r, ymin=0.8):
        p = on_blade(H2_KEYS, t + H2_DELAY, r)
        return (p[0], max(p[1], ymin), p[2])
    ring_sheds(f, "shed", "shed", 1, [ringpt(t, 2.3) for t in range(1, 5)], PIVOT, H2_EYES, 0.4, 0.55)
    ring_sheds(f, "shed", "shed", 5, [ringpt(t, 2.2, 1.0) for t in range(5, 11)], PIVOT, H2_EYES, 0.4, 0.75)
    for sg, side in ((1, "l"), (-1, "r")):
        for tick, dx, dy, dz, d in ((4, 0.5, 0.55, -0.02, (1.0, 0.3, 0.05)), (4, 0.55, 0.55, 0.1, (1.0, 0.4, -0.2)),
                                    (5, 0.6, 0.6, -0.05, (1.0, 0.4, 0.15)), (6, 0.65, 0.5, 0.0, (1.0, 0.3, -0.05))):
            spray(f, "spray-" + side, "spray", tick, (c[0] + sg * dx, c[1] + dy, c[2] + dz), (sg * d[0], d[1], d[2]),
                  ticks=2 if tick == 4 else 1)
        spray(f, "fan-" + side, "fan", 5, (c[0] + sg * 1.2, c[1] + 0.5, c[2] + 0.15), (sg * 0.45, 1.0, 0.1), ticks=2)
        scatter(f, "ember-" + side, "ember", 4, ((1.3, 2.0) if sg > 0 else (-2.0, -1.3), (0.75, 1.25), (1.2, 1.9)), 5,
                21 + sg)
        scatter(f, "ember-deep-" + side, "ember-deep", 6, ((1.35, 2.2) if sg > 0 else (-2.2, -1.35), (0.7, 1.1), (1.1, 1.9)), 4,
                31 + sg)
        scatter(f, "ember-hot-" + side, "ember-hot", 4, ((1.3, 1.6) if sg > 0 else (-1.6, -1.3), (0.85, 1.15), (1.3, 1.7)), 2,
                41 + sg)
        f.els.append(emitter("s1h2-p-streaks-" + side, "bastion-s1-streak", STREAK_TINT, 4, off(c, 0.45 * sg, 0.45, -0.1), 2, 2,
                             (0.35, 0.5), "custom[%d, 0.1, 0.15]" % sg, 20, 0.0, 0.2, (3, 4), (0.07, 0.1), STREAK_SOL, 0))
        f.els.append(emitter("s1h2-p-slivers-" + side, "bastion-s1-sliver", SLIVER_TINT, 4, off(c, 0.45 * sg, 0.5, 0.0), 3, 2,
                             (0.2, 0.28), "custom[%.1f, 0.3, 0.1]" % (0.9 * sg), 20, 0.015, 0.08, (4, 5), (0.04, 0.06),
                             SLIVER_SOL, 0))
    ring_embers(f, "ember-ring", "ember-deep", 5, [ringpt(t + 0.5, 2.4) for t in range(5, 10)], H2_EYES, 0.35)
    return f


# ---------------------------------------------------------------------------------------------------------
# channel report: client maths per type (26.3 jar). spark (firework): v exact, per tick v.y -= 0.004, p += v, v *= 0.91,
# drawn for the first 8/32 of a 48-60 tick life = 12-15 ticks. ember (entity_effect, `up`): horizontal |v| <= 0.018
# random, v.y in [-0.016, 0.056], per tick v.y += 0.004, p += v, v *= 0.96, life 8-40.
# ---------------------------------------------------------------------------------------------------------
def _cone(d, spread, rnd):
    L = math.sqrt(sum(x * x for x in d))
    b = [x / L for x in d]
    h = (0.0, 1.0, 0.0) if abs(b[1]) < 0.99 else (1.0, 0.0, 0.0)
    u = (b[1] * h[2] - b[2] * h[1], b[2] * h[0] - b[0] * h[2], b[0] * h[1] - b[1] * h[0])
    lu = math.sqrt(sum(x * x for x in u))
    u = tuple(x / lu for x in u)
    w = (b[1] * u[2] - b[2] * u[1], b[2] * u[0] - b[0] * u[2], b[0] * u[1] - b[1] * u[0])
    ct = 1 - rnd.random() * (1 - math.cos(math.radians(spread)))
    st = math.sqrt(max(0.0, 1 - ct * ct))
    ph = rnd.uniform(0, 2 * math.pi)
    return tuple(b[k] * ct + u[k] * st * math.cos(ph) + w[k] * st * math.sin(ph) for k in range(3))


def channel_report(tag, eyes, samples=60):
    import random
    rnd = random.Random(5)
    ems = [e for e in CHAN_EMITS if e["fx"] == tag]
    worst = {}
    alive = {"spark": [0] * 40, "ember": [0] * 40}
    for e in ems:
        d = e["d"]
        n = d["count"]
        ch = d["channel"]
        key = (ch, e["tick"])
        for i in range(n):
            vis = 15 if ch == "spark" else 24  # alive estimate (ember mean of 8-40)
            for t in range(e["tick"], min(40, e["tick"] + vis)):
                alive[ch][t] += 1
        for _ in range(samples):
            p = list(e["at"])
            if ch == "spark":
                dv = _cone(e["dir"] if d["direction"] == "along_tangent" else (0, 1, 0), d["spread"], rnd)
                sp = d["speed"] * (1 + d["jitter"] * rnd.uniform(-1, 1))
                v = [x * sp for x in dv]
                life, fr, acc = 15, 0.91, -0.004
            else:
                a = rnd.uniform(0, 2 * math.pi)
                hz = rnd.uniform(0, 0.018)
                v = [hz * math.cos(a), rnd.uniform(-0.016, 0.056), hz * math.sin(a)]
                life, fr, acc = rnd.randint(8, 40), 0.96, 0.004
            for _ in range(life):
                v[1] += acc
                p = [p[k] + v[k] for k in range(3)]
                v = [x * fr for x in v]
                for w in (worst.setdefault(ch, [9.0, 9.0, 9.0]), worst.setdefault(e["eid"], [9.0, 9.0, 9.0])):
                    w[0] = min(w[0], math.dist(p, CASTER_EYE))
                    w[1] = min(w[1], min(math.dist(p, q) for q in eyes))
                    if ch == "spark":
                        w[2] = min(w[2], p[1])
        del key
    bad = [(k, w) for k, w in worst.items() if k.startswith(tag) and (w[0] < 1.5 or w[1] < EYE_KEEP or w[2] < 0.6)]
    for k, w in sorted(bad):
        print("   !! %-24s caster %.2f  opponent %.2f  y_min %.2f" % (k, w[0], w[1], w[2]))
    for ch in ("spark", "ember"):
        w = worst.get(ch, [9, 9, 9])
        cnt = sum(e["d"]["count"] for e in ems if e["d"]["channel"] == ch)
        print("%s %-5s emitted %3d   min caster-eye %.2f  min opponent-eye %.2f  spark y_min %.2f" % (tag, ch, cnt, w[0], w[1], w[2]))
        per = {}
        for e in ems:
            if e["d"]["channel"] == ch:
                per[e["tick"]] = per.get(e["tick"], 0) + e["d"]["count"]
        print("   spawned per fx tick:", dict(sorted(per.items())))
        print("   alive per fx tick 0-24:", alive[ch][:25])


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
    channel_defs()
    make_spark()
    patch(VFX / "bastion-s1-fx-high.yaml", hit1())
    report(H1_EYE, "hit 1 display", list(EMITS))
    channel_report("s1h1", H1_EYES)
    n1 = len(EMITS)
    patch(VFX / "bastion-s1-fx-low.yaml", hit2())
    report(H2_EYE, "hit 2 display", EMITS[n1:])
    channel_report("s1h2", H2_EYES)
