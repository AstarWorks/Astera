#!/usr/bin/env python3
"""Bastion S1 particle layer (r16: retextured vanilla particle channels, ADR-0252): particle files + particle and
sprite-emitter elements appended to the fx files.

Run AFTER tools/item-art/scripts/bastion_s1_fx.py (that script rewrites the fx files from scratch and drops the
marked particle block); this script removes its own previous block and re-appends it, so it is idempotent:

    python3 tools/item-art/scripts/bastion_s1_particles.py

r24: every blade-riding element (sheds, ring embers, contact anchor, follow-through spray) is computed from the rig and skill
YAML through rig_path.py (no hardcoded keys / delays): re-run after any animator re-key and the particles follow the blade.

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
SLIVER_TINT = None  # painted in its final colours (r21: opaque hot head + char tail); the r8 '#FF2A2218' tint turned it black
SLIVER_SPIN = 12  # deg/tick, random sign per particle: the shards tumble apart instead of all lying at the sprite's 28 deg tilt


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
# r24: the blade path is READ from the rig (tools/item-art/scripts/rig_path.py: content/vfx/<rig>.yaml keys, pivot, tilt,
# rest, hit-stop freezes and the play_vfx / dash_strike delays of content/skills/<skill>.yaml), never hardcoded. Before r24
# this file carried the r9 tip table (H1_KEYS, H1_DELAY 4) and the r10 sweep angles, so when the animator re-keyed the
# wind-up (r23: coil, contact 8 -> 7) the sheds kept flying on the old path (director r23: detached orange streak, compare t5,
# side t5-6). Re-run this script after ANY rig or skill timing change; the windows, contact points and arcs follow.
# ---------------------------------------------------------------------------------------------------------
import sys  # noqa: E402
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import numpy as _np  # noqa: E402
from rig_path import RigPath  # noqa: E402
from bastion_s1_sprites import SWEEPS as _SWEEPS  # noqa: E402

H1_CONTACT = (-0.439, 1.533, 1.583)  # r27: hit-1 contact point on the target side (bastion_s1_fx.H1_HIT)

SKILL, RIG = "bastion-greatsword-s1", "bastion-s1-rig"
RP1 = RigPath(SKILL, RIG, fx_vfx="bastion-s1-fx-high")
RP2 = RigPath(SKILL, RIG, fx_vfx="bastion-s1-fx-low")
CASTER_EYE = (0.0, 1.62, 0.0)  # fx frame; both fx files spawn after their dash has finished (rig_path.caster_eye)
NEAR = 1.55  # caster camera near field: nothing emitted closer (brief: < 1.5)
EYE_KEEP = 0.85  # opponent eye keep-out for path points
H1_EYE, H2_EYE = RP1.target_eye(), RP2.target_eye()  # opponent camera = the target at the dash stop (capture DISTANCE 4)


def eye_band(rp):
    """Opponent eye positions to keep clear: the computed dash stop, 0.2 nearer (a target that steps in / client lag) and
    0.3 further (a dash cut short)."""
    z = rp.target_eye()[2]
    return [(0.0, 1.62, round(z + d, 3)) for d in (-0.2, -0.1, 0.0, 0.3)]


def blade_safe(rp, t, r, eyes, ymin=0.14):
    """Point at radius r along the rig blade at (fractional) fx tick t, pulled in toward the pivot until it is EYE_KEEP
    clear of every opponent eye and pushed out until it is NEAR clear of the caster eye; never under ymin."""
    for _ in range(40):
        p = rp.blade_point(t, radius=r)
        if any(math.dist(p, e) < EYE_KEEP for e in eyes):
            r -= 0.05
            continue
        if math.dist(p, CASTER_EYE) < NEAR:
            r += 0.05
            continue
        break
    return (p[0], max(p[1], ymin), p[2])


def off(p, dx=0.0, dy=0.0, dz=0.0):
    return (p[0] + dx, p[1] + dy, p[2] + dz)


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
    chdef("spray", S, 4, 0.3, 16, 0.45, comment="r16 hit spray: retextured firework sparks (ADR-0252) thrown along the element's creep direction.")
    chdef("fan", S, 4, 0.22, 28, 0.4, comment="r16 upward fan: firework sparks rising out of the contact.")
    chdef("shed", S, 3, 0.17, 25, 0.4, comment="r16 ring shed: one firework spark per path point, flung along the sweep tangent.")
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


def ring_embers(f, name, pid, rp, t0, t1, r, eyes, step, ymin=0.14):
    """Embers dropped along the TRUE blade arc (rig_path.arc) between fx ticks t0 and t1, one per `step` blocks, each
    emitted at the tick the blade has just passed it; points whose rising column would cross an eye are skipped."""
    for t, _, _ in rp.arc(t0, t1, radius=r, step=step):
        q = blade_safe(rp, t, r, eyes, ymin)
        if ember_ok(q, eyes):
            chan(f, name, pid, int(math.ceil(t - 1e-9)), [q, q], every=2)


def spark_safe(at, d, pid, eyes, n=120, floor=0.6):
    """Every sampled spark of this emission (cone x speed extremes, client maths) keeps the keep-outs and stays above
    `floor` (r15 rule: 0.6, no glints lying under the target; the hit-2 floor scrape passes a lower one)."""
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
                if p[1] < floor or math.dist(p, CASTER_EYE) < 1.5 or any(math.dist(p, e) < EYE_KEEP for e in eyes):
                    return False
    return True


def ring_sheds(f, name, pid, rp, t0, t1, r, eyes, step, lift, ymin=0.3, floor=0.6):
    """Sparks shed along the TRUE blade arc between fx ticks t0 and t1 (rig_path.arc, never a chord): one 1-tick spray per
    `step` blocks, emitted at the tick the blade has just passed the point, flung along the arc tangent + outward from the
    pivot + `lift` up; emissions whose sparks could cut a keep-out are dropped."""
    pv = rp.pivot
    for t, _, tg in rp.arc(t0, t1, radius=r, step=step):
        q = blade_safe(rp, t, r, eyes, ymin)
        o = (q[0] - pv[0], 0.0, q[2] - pv[2])
        lo = math.hypot(o[0], o[2]) or 1.0
        d = (tg[0] + 0.45 * o[0] / lo, tg[1] + lift, tg[2] + 0.45 * o[2] / lo)
        if spark_safe(q, d, pid, eyes, floor=floor):
            spray(f, name, pid, int(math.ceil(t - 1e-9)), q, d)


H1_EYES, H2_EYES = eye_band(RP1), eye_band(RP2)
EYES = {"s1h1": H1_EYES, "s1h2": H2_EYES}


def rel_box(c, box, c0):
    """A zone authored around an earlier contact point c0, moved with the contact point c read from the rig."""
    return tuple((lo + c[k] - c0[k], hi + c[k] - c0[k]) for k, (lo, hi) in enumerate(box))


def rel(c, p, c0):
    return tuple(p[k] + c[k] - c0[k] for k in range(3))


def blade_row(f, name, pid, rp, ft, radii, eyes, lift, spread_out=0.5, ymin=0.3, floor=0.6):
    """Sparks shed ALONG the blade body on fx tick ft (one spray per radius): flung along the blade's own direction (the
    pose is held, so the only motion the eye reads is the air dragging the sparks off it) plus a little outward / up. The
    spray starts ON the blade (distance 0), so it never leads it."""
    d = rp.blade_dir(ft)
    for r in radii:
        q = blade_safe(rp, ft, r, eyes, ymin)
        out = (q[0], 0.0, q[2])
        lo = math.hypot(out[0], out[2]) or 1.0
        v = (d[0] + spread_out * out[0] / lo, d[1] + lift, d[2] + spread_out * out[2] / lo)
        if spark_safe(q, v, pid, eyes, floor=floor):
            spray(f, name, pid, ft, q, v)


def blade_embers(f, name, pid, rp, ft, radii, eyes, ymin=0.14):
    """Embers dropped ON the blade at fx tick ft (they drift up off it)."""
    for r in radii:
        q = blade_safe(rp, ft, r, eyes, ymin)
        if ember_ok(q, eyes):
            chan(f, name, pid, ft, [q, q], every=2)


def hit1(rp=RP1):
    """r27 pose to pose. fx tick 0 = skill 6 = the CUT to the follow-through (the blade jumps behind the neck, held to 15; hit
    stop 7-8; overshoot cut at 16, fx 10). Nothing starts before the cut (B0 coil is `fx: none`). The contact bursts (sprays,
    fan, embers, streaks, slivers) fly off the target-side contact point H1_HIT on the cut tick (reference: the white streak
    runs into the target with orange sparks at its tip, t6-11). While the pose is held the blade keeps shedding: a spark row
    and a few embers ON the blade body every second tick (the air drags them off it), so every tick differs and the sparks
    follow the sword instead of floating at the old swing. The overshoot cut (fx 10-11) throws one more row."""
    f = Fx("s1h1")
    c = H1_CONTACT
    kc = 0
    for r_ft in (0, 1):  # the cut tick and the next one (frozen by the hit stop): full rows
        blade_row(f, "shed", "shed", rp, r_ft, (1.0, 1.4, 1.8, 2.2), H1_EYES, 0.45)
    for ft in (3, 5, 7, 9):  # held follow-through (skill 9, 11, 13, 15): thinner rows + embers
        blade_row(f, "shed", "shed", rp, ft, (1.2, 1.8), H1_EYES, 0.55)
        blade_embers(f, "ember-ring", "ember-deep" if ft % 4 == 3 else "ember", rp, ft, (1.0, 1.7, 2.2), H1_EYES)
    blade_embers(f, "ember-ring", "ember-hot", rp, 0, (1.3, 2.0), H1_EYES)
    for ft in (10, 11):  # overshoot cut (skill 16-17): the blade jumps up over the shoulder
        blade_row(f, "shed", "shed", rp, ft, (0.9, 1.3, 1.7, 2.1), H1_EYES, 0.5)
    blade_embers(f, "ember-ring", "ember-hot", rp, 10, (1.2, 1.9), H1_EYES)
    for i, (dt, p, d) in enumerate((
            (0, (-0.72, 1.62, 1.55), (-1.0, 0.18, 0.15)),
            (0, (-0.75, 1.55, 1.45), (-1.0, 0.25, -0.25)),
            (1, (-0.8, 1.7, 1.5), (-1.0, 0.3, 0.0)),
            (1, (-0.78, 1.5, 1.55), (-1.0, 0.3, 0.3)),
            (2, (-0.85, 1.6, 1.4), (-1.0, 0.2, -0.1)),
            (3, (-0.9, 1.55, 1.45), (-1.0, 0.35, 0.1)))):
        spray(f, "spray", "spray", kc + dt, p, d, ticks=2 if i < 2 else 1)
    spray(f, "fan", "fan", kc, (-1.05, 1.7, 1.35), (-0.35, 1.0, -0.05), ticks=3)
    scatter(f, "ember", "ember", kc, ((-2.0, -1.25), (1.2, 1.9), (1.15, 1.8)), 8, 11, 0.22)
    scatter(f, "ember-hot", "ember-hot", kc, ((-1.6, -1.2), (1.4, 1.8), (1.3, 1.7)), 5, 12)
    scatter(f, "ember-deep", "ember-deep", kc + 2, ((-2.2, -1.3), (1.0, 1.6), (1.1, 1.9)), 7, 13)
    s = off(c, 0, 0.15, -0.1)
    f.els.append(emitter("s1h1-p-streaks", "bastion-s1-streak", STREAK_TINT, kc, off(c, 0, -0.1, -0.15), 5, 2, (0.35, 0.5),
                         "custom[-1, 0.2, 0.25]", 25, 0.0, 0.2, (3, 4), (0.07, 0.1), STREAK_SOL, 0))
    f.els.append(emitter("s1h1-p-slivers", "bastion-s1-sliver", SLIVER_TINT, kc, off(s, -0.3, 0, -0.05), 8, 2, (0.18, 0.26),
                         "custom[-1, 0.8, 0.1]", 40, 0.03, 0.08, (4, 5), (0.04, 0.06), SLIVER_SOL, SLIVER_SPIN))
    return f


def hit2(rp=RP2):
    """r27 pose to pose. fx tick 0 = skill 30 (flash beat, no particles); fx 1 = skill 31 = the CUT to the extension (blade
    forward at chest height, held to 37; hit stop 31-32); fx 8 = skill 38 the drop cut to the low back-left pose. Contact
    bursts fly off the blade at radius 1.2 on the cut; the held extension sheds a spark row + embers every tick pair; the drop
    cut throws a last row (fx 8-9, skill 38-39, the last emissions before the B8 rest, which is `fx: none`)."""
    f = Fx("s1h2")
    k = 1
    c = rp.blade_point(k, radius=1.2)
    for ft in (1, 2):
        blade_row(f, "shed", "shed", rp, ft, (0.9, 1.3, 1.7, 2.1), H2_EYES, 0.5, ymin=0.45, floor=0.25)
    for ft in (3, 4, 5, 6, 7):
        blade_row(f, "shed", "shed", rp, ft, (1.1, 1.8) if ft % 2 else (0.9, 1.5, 2.1), H2_EYES, 0.6, ymin=0.45, floor=0.25)
        blade_embers(f, "ember-ring", "ember-deep" if ft % 2 else "ember", rp, ft, (1.0, 1.6), H2_EYES, ymin=0.3)
    for ft in (8, 9):  # the drop cut
        blade_row(f, "shed", "shed", rp, ft, (0.8, 1.2, 1.6, 2.0), H2_EYES, 0.6, ymin=0.45, floor=0.25)
        blade_embers(f, "ember-ring", "ember-deep", rp, ft, (1.0, 1.7), H2_EYES, ymin=0.3)
    for sg, side in ((1, "l"), (-1, "r")):
        for dt, dx, dy, dz, d in ((0, 0.5, 0.55, -0.02, (1.0, 0.3, 0.05)), (0, 0.55, 0.55, 0.1, (1.0, 0.4, -0.2)),
                                  (1, 0.6, 0.6, -0.05, (1.0, 0.4, 0.15)), (2, 0.65, 0.5, 0.0, (1.0, 0.3, -0.05))):
            spray(f, "spray-" + side, "spray", k + dt, (c[0] + sg * dx, c[1] + dy, c[2] + dz), (sg * d[0], d[1], d[2]),
                  ticks=2 if dt == 0 else 1)
        spray(f, "fan-" + side, "fan", k + 1, (c[0] + sg * 1.2, c[1] + 0.5, c[2] + 0.15), (sg * 0.45, 1.0, 0.1), ticks=2)
        scatter(f, "ember-" + side, "ember", k, rel_box(c, ((1.3, 2.0) if sg > 0 else (-2.0, -1.3), (0.75, 1.25), (1.2, 1.9)),
                                                         (0.02, 0.46, 1.40)), 5, 21 + sg)
        scatter(f, "ember-deep-" + side, "ember-deep", k + 2, rel_box(c, ((1.35, 2.2) if sg > 0 else (-2.2, -1.35), (0.7, 1.1),
                                                                         (1.1, 1.9)), (0.02, 0.46, 1.40)), 4, 31 + sg)
        scatter(f, "ember-hot-" + side, "ember-hot", k, rel_box(c, ((1.3, 1.6) if sg > 0 else (-1.6, -1.3), (0.85, 1.15),
                                                                   (1.3, 1.7)), (0.02, 0.46, 1.40)), 2, 41 + sg)
        f.els.append(emitter("s1h2-p-streaks-" + side, "bastion-s1-streak", STREAK_TINT, k, off(c, 0.45 * sg, 0.45, -0.1), 2, 2,
                             (0.35, 0.5), "custom[%d, 0.1, 0.15]" % sg, 20, 0.0, 0.2, (3, 4), (0.07, 0.1), STREAK_SOL, 0))
        f.els.append(emitter("s1h2-p-slivers-" + side, "bastion-s1-sliver", SLIVER_TINT, k, off(c, 0.45 * sg, 0.5, 0.0), 3, 2,
                             (0.2, 0.28), "custom[%.1f, 0.3, 0.1]" % (0.9 * sg), 20, 0.015, 0.08, (4, 5), (0.04, 0.06),
                             SLIVER_SOL, SLIVER_SPIN))
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
    bad = [(k, w) for k, w in worst.items() if k.startswith(tag) and (w[0] < 1.5 or w[1] < EYE_KEEP or w[2] < (0.25 if '-shed' in k else 0.6))]
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
