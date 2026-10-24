#!/usr/bin/env python3
"""Writes content/vfx/bastion-s1-fx-high.yaml and bastion-s1-fx-low.yaml (elements only) for the bastion-greatsword S1
pilot, and prints the display budget (alive entities per effect tick). Sprite blocks: width = font height / 40 * scale,
so a 256 px sprite is 6.4 blocks * scale and a 128 px sprite is 3.2 blocks * scale.

    python3 tools/item-art/scripts/bastion_s1_fx.py
"""
import os
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bastion_s1_sprites import (RIG_VFX, SKILL_ID, SWEEP_HALF, SWEEPS, TILES, ring_schedule,  # noqa: E402
                                sweep_euler, tile_grow)  # one geometry (and one rig-driven schedule) for art + placement
from rig_path import RigPath  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))


def s256(w):
    return w / 6.4


def s128(w):
    return w / 3.2


def sprite(id_, name, start, end, kfs, frame_ticks=1, color=None, loop=False, see_through=True):
    """Sprite keyframes cannot animate colour/alpha (only quads can), so a fade (alpha -> 0) becomes a collapse of
    the scale (thin sliver, then gone at end_tick); other alpha-only keyframes are dropped."""
    kind = {"type": "sprite", "sprite": name, "frame_ticks": frame_ticks, "loop": loop, "see_through": see_through}
    if color:
        kind["color"] = color
    out, last, cur = [], None, {}
    for k in kfs:
        k = dict(k)
        a = k.pop("alpha", None)
        for f in ("translate", "rotate", "scale"):  # a keyframe that omits a field resets it: carry the last value
            if f in k:
                cur[f] = k[f]
            elif f in cur and len(k) > 1:
                k[f] = cur[f]
        if "scale" in k:
            last = k["scale"]
        if a == 0.0 and last is not None:
            k["scale"] = [last[0] * 0.85, last[1] * 0.04, 1.0]
            k["ease"] = k.get("ease", "in_quad")
        if len(k) > 1:
            out.append(k)
    return {"id": id_, "kind": kind, "billboard": "center", "brightness": 15, "start_tick": start, "end_tick": end,
            "keyframes": out}, ("sprite", start, end)


def kf(t, translate=None, rotate=None, scale=None, ease=None, alpha=None):
    d = {"t": t}
    if translate is not None: d["translate"] = translate
    if rotate is not None: d["rotate"] = rotate
    if scale is not None: d["scale"] = scale
    if alpha is not None: d["alpha"] = alpha
    if ease: d["ease"] = ease
    return d


def quad(id_, color, start, origin, emit):
    el = {"id": id_, "kind": {"type": "quad", "color": color, "alpha": 1.0}, "brightness": 15, "start_tick": start,
          "keyframes": [{"t": 0, "translate": origin}], "emit": emit}
    return el, ("emit", start, emit)


def curve(values):
    """Evenly spaced list -> curve keys [{t, v}] (emit *_over_life format)."""
    n = len(values)
    return [{"t": round(i / (n - 1), 4) if n > 1 else 0, "v": v} for i, v in enumerate(values)]


def emit(count, rate, direction, spread, speed, life, size, gravity, colors, alphas, drag=0.04, sol=(1.0, 0.3), spin=0):
    d = {"count": count, "rate_per_tick": rate, "speed": speed, "direction": direction, "spread_deg": spread,
         "gravity": gravity, "drag": drag, "life_ticks": life, "size": size, "size_over_life": curve(list(sol))}
    if colors: d["color_over_life"] = curve(colors)
    d["alpha_over_life"] = curve(alphas)
    if spin: d["spin_over_life"] = [{"t": 0, "v": spin}]
    return d


SPARK_C = ["#FFC040", "#FF7A12", "#B03000"]


import math

ORANGE = "#FF8A1E"


def sweep(id_, name, start, end):
    """Round 7: swing-plane smear (fixed billboard) lying in the r7 blade plane, front + back pair (a TextDisplay is
    only trusted from its +Z side; the back strip is mirrored so both draw the same 3D arc). Flipbook = the wipe
    following the blade, then the fade; frame k shows at element tick k + 1 (frame 0 at spawn and spawn + 1)."""
    c = [round(float(v), 3) for v in SWEEPS[name]["centre"]]
    sc = 2 * SWEEPS[name].get("half", SWEEP_HALF) / 6.4
    out = []
    for suffix, back in (("", False), ("-b", True)):
        el, m = sprite(id_ + suffix, "bastion-s1-" + name + suffix, start, end, [
            kf(0, c, sweep_euler(name, back), [sc, sc, 1.0])])
        el["billboard"] = "fixed"
        out.append((el, m))
    return out


def sweep_tiles(id_, name, faces=("q",)):
    """A plane ring shipped as n x n tiles (`<sprite>-q<i>` front, `<sprite>-bq<i>` back = mirrored image with the back
    euler; r13 / r23): each tile is the same plane at 1/n of the size, centred at centre + sa*h*e1 + sb*h*e2 (h = half / n).
    r25: start / end of every tile come from the RIG (bastion_s1_sprites.ring_schedule, rig_path.RigPath): a tile spawns
    so that its band first shows on the tick the blade tip has swept into its quarter (blank frame 0, see the schedule),
    and all tiles end after the last fade frame. No hard-coded ticks: re-run this script and bastion_s1_sprites.py after
    any rig / skill timing change. A tile the tip never reaches is an error (r25b), never silently left out."""
    sw = SWEEPS[name]
    sch = ring_schedule(name)
    n = sw["tiles"]
    h = sw.get("tile_half", SWEEP_HALF) / n
    sc = 2 * h * tile_grow(name) / 6.4  # r26: tiles overlap by tile_overlap texels per edge (no crack at the join)
    out = []
    for pre in faces:
        for qi, (sa, sb) in enumerate(TILES):
            if qi not in sch["spawn"]:  # r25b: never drop a tile silently (ring_schedule already fails on this)
                raise SystemExit(f"sweep_tiles({name}): tile q{qi} has no spawn tick in the rig schedule")
            c = [round(float(v), 3) for v in sw["centre"] + sa * h * sw["e1"] + sb * h * sw["e2"]]
            el, m = sprite(f"{id_}-{pre}{qi}", f"bastion-s1-{name}-{pre}{qi}", sch["spawn"][qi], sch["end"],
                           [kf(0, c, sweep_euler(name, pre == "bq"), [sc, sc, 1.0])])
            el["billboard"] = "fixed"
            out.append((el, m))
    return out


def at(p, off):
    return [round(p[i] + off[i], 3) for i in range(3)]


def contact_anchor(fx_vfx, radius, offset=(0.0, 0.0, 0.0)):
    """r25: (contact fx tick, point) of a blade-following contact element: the blade point `radius` blocks from the rig
    pivot on the fx file's (only) contact tick, read from the rig (rig_path.RigPath), plus the element's authored offset
    (camera-tuned: kept relative to the blade so it moves when the animator moves the contact)."""
    rp = RigPath(SKILL_ID, RIG_VFX, fx_vfx=fx_vfx)
    c = rp.contact_fx_ticks()
    c = [t for t in c if 0 <= t < (rp.fx_duration or 999)][0]
    p = rp.blade_point(c, radius=radius)
    return c, [round(p[i] + offset[i], 3) for i in range(3)]


def spark(id_, start, p, theta, length, dist, life=4, color=ORANGE):
    """Orange spark streak (tinted white-hot streak sprite): flies along (0, sin, cos) of theta, shrinks to a needle."""
    r = math.radians(theta)
    d = [0.0, math.sin(r) * dist, math.cos(r) * dist]
    pos = lambda f: [round(p[0], 3), round(p[1] + d[1] * f, 3), round(p[2] + d[2] * f, 3)]
    return sprite(id_, "bastion-s1-streak", start, start + life, [
        kf(0, pos(0.0), [0, 0, theta], [s256(length * 0.35), 0.05, 1.0]),
        kf(1, pos(0.4), scale=[s256(length), 0.05, 1.0], ease="out_expo"),
        kf(life - 1, pos(1.0), scale=[s256(length * 0.45), 0.012, 1.0], ease="in_quad")], color=color)


def spark3(id_, start, p, d, roll, length, life=4, color=ORANGE):
    """r10: orange spark streak flying along a 3D direction d (blocks travelled over its life); roll = its screen angle."""
    pos = lambda f: [round(p[0] + d[0] * f, 3), round(p[1] + d[1] * f, 3), round(p[2] + d[2] * f, 3)]
    return sprite(id_, "bastion-s1-streak", start, start + life, [
        kf(0, pos(0.0), [0, 0, roll], [s256(length * 0.35), 0.05, 1.0]),
        kf(1, pos(0.4), scale=[s256(length), 0.05, 1.0], ease="out_expo"),
        kf(life - 1, pos(1.0), scale=[s256(length * 0.45), 0.012, 1.0], ease="in_quad")], color=color)


# r10 hit-1 contact (rig Round 10): blade root (-0.39, 1.13, 0.89) -> tip (-0.50, 2.04, 2.44) at skill 8 = fx 4, held 8-10.
# The tip is past the opponent eye (0, 1.62, 2.4), so the core sits ON the contact blade at 45% (-0.44, 1.54, 1.59): 0.93 from
# the opponent eye, 1.6 from the caster eye, at the ring smear's front edge (z 1.6).
# r25: no hard-coded contact point any more: contact_anchor("bastion-s1-fx-high", 1.5) reads it from the rig
# ((-0.439, 1.533, 1.583) on fx 4 for the r23 keys); the offsets below are the camera-tuned shifts from that blade point.
H1_LEAD = SWEEPS["hring"].get("lead", 0)  # r26: hit-1 contact displays spawn this many ticks before the rig contact
H1_R, H2_R = 1.5, 1.2  # blade radius (from the rig pivot) of the hit-1 / hit-2 contact anchors


def high():
    els = []
    c, hit = contact_anchor("bastion-s1-fx-high", H1_R)  # r25: contact tick + blade point from the rig
    # r10: hit 1 is a tilted horizontal sweep (tip plane y = 1.15 + tan20 z). The smear is a gold RING in that plane around the
    # caster (bastion_s1_sprites.py SWEEPS["hring"]: chest height at the sides, head height in front, ~0.2 above the floor
    # behind), front + back pair. Spawn fx 0 (skill 4): frame 0 (seed at the wind-up extreme) on skill 4-5, frame k on skill
    # 5 + k: 181 deg at 6, 135 at 7, contact 93.5 at 8 (held 9-10), 45 at 11, -3 at 12 (follow-through), fade frames 8-11 on
    # skill 13-16.
    # r23: 2 x 2 tiles per face (texels half as big: no stair at the compare close-up), tilt 14 (bastion_s1_sprites.HRING_TILT).
    # r25 (director r23 / particle-artist r24: tile q2 frame 0 hung at the right-back on skill 3, before the blade): the
    # tiles and their frames come from the rig (ring_schedule): each tile appears on the tick the tip sweeps into it.
    els += sweep_tiles("s1h1-ring", "hring", faces=("q", "bq"))
    # white-hot cut streak through the contact, horizontal on screen (the cut is horizontal: roll 6 reads as the slash in first
    # person and as the forward streak of reference b01/b03 from the side cameras)
    # r26 (reviewer r25 #2: compare f13-14 = the contact and tint frames showed no streak / flash, both first read at f15):
    # they spawned ON the contact as a 0.15-0.6 block seed that only grew to full size two ticks later (keyframe clock
    # spawn + 1, then a 1-tick interpolation), and a spawn sent on the "reached" contact tick shows a client tick after the
    # blade (the rig pose packet goes out one tick before, see SWEEPS hring `lead`). Now both spawn `lead` ticks before
    # the contact at (near) PEAK size: the spawn tick itself carries the impact, so it reads on the tint frame.
    s0 = c - H1_LEAD
    els.append(sprite("s1h1-streak-main", "bastion-s1-streak", s0, s0 + 7, [
        kf(0, at(hit, (0.0, 0.047, -0.083)), [0, 0, 6], [s256(2.5), 0.3, 1.0]),
        kf(1, scale=[s256(3.0), 0.3, 1.0], ease="out_quad"),
        kf(6, scale=[s256(2.3), 0.03, 1.0], ease="in_quad")]))
    # flash on the contact blade. r13: the dark-tinted glint behind it is gone (reviewer r9: it rendered as a big black "+"
    # across the target); contrast comes from the glint's own orange halo and the dark sliver particles.
    # r15 (reviewer r11: the 128 px 4-point glint read as an axis-aligned pixel "+"): `bastion-s1-flare` (256 px, soft radial
    # core + two slanted tapered spikes), shrinking uniformly instead of collapsing into a horizontal slit.
    els.append(sprite("s1h1-flash", "bastion-s1-flare", s0, s0 + 4, [  # r26: peak on spawn (see the streak above)
        kf(0, at(hit, (0.0, 0.007, 0.007)), [0, 0, 0], [s256(1.1), s256(1.1), 1.0]),
        kf(1, scale=[s256(0.95), s256(0.95), 1.0], ease="out_quad"),
        kf(3, scale=[s256(0.45), s256(0.45), 1.0], ease="in_quad")]))
    # r12 (particle-artist): the six static orange spark-streak sprites are gone. A sprite element cannot move (it only
    # scaled in place) and they held 6 of the ~38 displays hit 1 can spend; the orange streaks are now MOVING display
    # particles (s1h1-p-streaks in bastion_s1_particles.py), next to the gold spark particles.
    # r12: the three static chip sprites are gone (a sprite cannot move, it only turned 70 deg in place: with the tinted-black
    # slanted shard it read as an "X" shuriken). Dark debris = the moving sliver display particles of the particle layer
    # (bastion_s1_particles.py, dark-brown needle sprite, no spin).
    # r16 (director r12: the grey spiral puffs read as hard "C" crescents behind the target, t14-16 compare + caster FP): the
    # smoke sprites are dropped from both hits. The 0.1-0.5 alpha spiral is cut at alpha 0.1 by the client's text shader, so
    # its outline is a hard-edged crescent at every size; the grain after the flash is carried by the particle layer.
    return {"id": "bastion-s1-fx-high", "duration_ticks": 20, "orient": "yaw", "follow": False, "sample_ticks": 1,
            "elements": [e for e, _ in els]}, [m for _, m in els]


def low():
    els = []
    c, hit = contact_anchor("bastion-s1-fx-low", H2_R)  # r25: contact tick + blade point from the rig
    # ONE connected torn ink mass of horizontal brush tongues (reference e00-e02), camera-facing (billboard center).
    # r10 (reviewer, real client r7: visible 5-6 ticks t31-36, pale cream, soft, hiding the caster and the weapon):
    # - life 3 ticks (fx 3-5 = skill 30-32, end_tick exclusive): frame 0 full mass on 30-31, frame 1 torn on 32.
    # - placed BEHIND the caster/target line from the compare + side cameras (they sit at the caster's right = fx -x),
    #   centre x +1.0, z 1.0, y 0.55, and depth-tested (see_through false): the caster, the target and the blade in
    #   front of it occlude it instead of being painted over, and the floor clips its lower edge. Opponent eye
    #   (0, 1.62, 1.7) and caster eye see it ~40 deg below the view centre (mostly under the frame, as r9).
    # r13 (reviewer r9: visible t30-33 = 4 ticks, a flat floor puddle from the caster view): hit_stop freezes the displays
    # on skill 31-33, so life 2 (fx 3-5, end exclusive) is removed at skill 33 -> on screen t30-32; raised to y 0.95 (spans
    # ~0.1-1.8, the floor no longer clips it) and 4.0 wide: a screen-facing flash beside the bodies as in reference e00/e01.
    # r14 (director r10: y 0.95 / 4.0 wide filled 21-27% of the opponent view and 17% of the caster FP): 2.8 x 1.18 blocks,
    # centre (1.4, 0.72, 1.0) = bottom edge y 0.13 (still above the floor clip), 0.4 farther out to the caster's left.
    # Perspective model (calibrated on r9/r10 captures, caster pitch 15 / opponent pitch 25, real ~1.25x model):
    # caster 7.5% / opponent 9.8% model -> ~9-10% / ~12-13% expected (r10 model 14.1 / 20.6).
    # r15 (reviewer r11: from caster, side AND opponent the ink read as a ragged floor puddle; only the low compare camera saw it
    # upright): the billboard is camera-facing (center) but sat at y 0.13-1.3, 1.7 blocks from the caster / opponent eyes,
    # so both looked DOWN on a wide flat slab and the side camera saw it at the bodies' feet. Now a chest-height upright
    # flash beside the pair: centre (1.7, 1.35, 0.85) (bottom y ~0.7, top ~2.0), 2.2 x 1.21 blocks (ratio 0.55, was 0.42).
    # Perspective model (vfov 90 = the review captures; calibrated on r11: caster 6.5 model vs 6.1 measured): caster 4.4%,
    # opponent 6.5-8.4% (eye z 2.2 / 1.7), side 0.7% at the bodies' waist-chest band, compare 2.4%.
    # r16 (director r12: at (1.7, 1.35, 0.85) the ink sat at the target's side, so the opponent saw a flat yellow blob floating
    # in the right third of its screen on t30-31, BEFORE the contact tint (t32), and the caster saw it at the left screen
    # edge): the ink IS the impact flash (reference e00-e02: a ragged horizontal band across the contact). Centre ON the
    # contact between the bodies, (0.15, 1.15, 1.05): 1.05 in front of the caster eye, ~0.9 in front of the opponent eye
    # (z 1.95), at the s1h2-flash core (0.05, 1.05, 1.0), which draws on top of it (see_through) = flash core in an ink band.
    # A flatter band (2.0 x 0.84 blocks, ratio 0.42) so it crosses the screen instead of filling it. Spawn fx 4 (skill 31 =
    # the damage tick), life 2: r12 showed the opponent draws a display on its spawn tick (fx 3 -> t30-31) and the caster
    # / compare 1-2 ticks later (t32-33 / t31-33), so fx 4 gives opponent t31-32, compare t32-33, caster ~t33-34 around the
    # tint frame t32 (the reference ink also leads the contact by one frame, e00 t30 -> e02 t31).
    # Perspective model (vfov 90, recalibrated on the r12 captures: caster pitch 0, opponent eye (0, 1.62, 1.95) pitch -3;
    # r12 placement models caster 4.3 / opponent 7.6 vs 4.0 / 7.7 measured): frame 0 caster 6.8%, opponent 9.7%, side 0.2%,
    # compare 2.5%; frame 1 4.9 / 7.0 / 0.1 / 1.8. Screen centre: caster ndc (-0.08, -0.45) = across the target's waist,
    # opponent (0.07, -0.59) = across the caster's waist where the blade comes in.
    # r17 (director real client r13: opponent 20.9% yellow / caster 9.1%, a flat opaque slab over the caster's waist,
    # hands and blade; compare: ~0.4 block ABOVE the blade). The r16 model missed that the hit-2 dash closes to ~1 block:
    # fitting the projection to the r13 ink masks (inverse ray -> quad, alpha > 0.1, vfov 90, 1280x720; IoU 0.67) puts
    # the opponent eye at fx (0, 1.62, ~1.35) pitched ~18 deg DOWN (its view tilts on the hit) and the caster eye at
    # (0, 1.57, ~0.2) pitch ~-4 (the caster ends ~0.2 past the fx origin). That model gives the r13 ink 28.5% / 11.2%
    # footprint; 70% of it is yellow in the capture, the rest is the white streak, ring and flare drawn over it
    # (-> 20-21% / 8-9% yellow, as measured). New: the shaded soft band sprite (core + bands, ~18% alpha area of the
    # frame vs 32%), lowered 0.4 onto the blade line, 2.4 x 2.4 blocks (content band ~2.3 x 0.77, y ~0.37-1.13).
    # Model footprint frame 0: opponent 8.3% (5.9-10.0 over eye z 1.45-1.3), caster 8.1% (8.1-9.8); frame 1 6.0 / 6.8;
    # x 0.73 for the overlays drawn on it -> ~6% yellow pixels. At the contact the blade line is at the BOTTOM of both
    # first-person views (waist height ~1 block from the eye), so the band now enters there (opponent rows ~580-720 of
    # 720 across the caster's hands/hilt, caster ~580-720, partly under the hotbar). A 2.4 x 1.92 quad (5.1 / 5.7%)
    # left only a ~90 px sliver at the bottom edge.
    els.append(sprite("s1h2-ink", "bastion-s1-ink", c, c + 2, [
        kf(0, at(hit, (0.139, 0.287, -0.345)), [0, 0, 0], [s256(2.4), s256(2.4), 1.0])], see_through=False))
    # r23 (reviewer r22: reference t30 = 13.133 s, one frame BEFORE the hit-2 contact, is an irregular flat yellow fill on
    # the floor around the caster, streaking along the cut; nothing at t30-31 in any view): the ink brush mass laid FLAT on
    # the floor (fixed, rotate [0, -90, 90] = front up, long axis along fx z), 4.4 blocks long (bands ~4.2 x 1.4), y 0.03,
    # depth-tested so the bodies stand on it. Spawn fx 3 (skill 30), end fx 5: frame 0 full mass on 30, frame 1 torn on
    # 31; the hit_stop (31-33) may hold the torn frame a tick or two. +1 display.
    floor_fill, m = sprite("s1h2-floor-fill", "bastion-s1-ink", c - 1, c + 1, [  # r25: timing from the rig contact
        kf(0, [0.25, 0.03, 0.5], [0, -90, 90], [s256(4.4), s256(4.4), 1.0])], see_through=False)
    floor_fill["billboard"] = "fixed"
    els.append((floor_fill, m))
    # Round 7b: the low ring is the smear of the r7 ring swing, IN its plane (10 deg tilt, lowest toward phi 60 = left-
    # front at the floor pitch -15 / y 0.11, centre on the pivot axis at tip pitch -5, radius 2.535 = tip; fitted to the
    # r7 keys 24-30 within 0.12 blocks), turning the blade's way (left-back -> back -> right -> front), 221 deg from
    # alpha 158 to the contact alpha 379.1. Spawn fx 0 (skill 27): frame 0 ticks 27-28
    # (blade at 212 on 28), 265 on 29, 321 on 30, full at contact 31, hold 32, fade 33-38. The front curls to z <= 2.3.
    els += sweep_tiles("s1h2-ring", "ring")  # r13: 4 front-only tiles; r25: spawn / frames from the rig (ring_schedule)
    els.append(sprite("s1h2-streak-main", "bastion-s1-streak", c, c + 6, [
        kf(0, at(hit, (-0.011, 0.837, -0.295)), [0, 0, -4], [s256(0.6), 0.32, 1.0]),
        kf(1, scale=[s256(4.6), 0.32, 1.0], ease="out_expo"),
        kf(6, scale=[s256(3.6), 0.04, 1.0], ease="in_quad")]))
    for i, (start, x, y, z, w, col) in enumerate([
            (c - 1, 0.5, 0.6, 0.8, 2.4, None), (c, -0.5, 1.05, 1.0, 2.0, "#FFE070")]):  # r25: speed lines lead the contact
        els.append(sprite(f"s1h2-line-{i + 1}", "bastion-s1-streak", start, start + 3, [
            kf(0, [x, y, z + 0.4], [0, 0, 0], [s256(0.5), 0.1, 1.0]),
            kf(1, scale=[s256(w), 0.1, 1.0], ease="out_expo"),
            kf(2, scale=[s256(w * 0.7), 0.02, 1.0], ease="in_quad")], color=col))
    # r13: s1h2-flash-dark removed (black "+" across the target, see high())
    # r15 (reviewer r11, opponent f33: a big axis-aligned cross with stair-stepped rays): the 128 px glint at 1.6 blocks sat
    # ~0.6-0.9 blocks from the opponent eye. Now `bastion-s1-flare` (256 px, soft radial core + slanted tapered spikes,
    # 2.5x the texel density per block), peak 1.0 block, moved 0.15 lower / closer to the caster (1.0 / 0.95 from the
    # opponent eye at z 1.7 / 2.0), shrinking uniformly.
    els.append(sprite("s1h2-flash", "bastion-s1-flare", c, c + 4, [
        # r14 director: lowered 1.05 -> 0.8 onto the r17 ink band (centre y 0.75, the blade line) so the flare core sits on
        # the contact inside the ink instead of ~0.3 above it.
        kf(0, at(hit, (0.039, 0.337, -0.395)), [0, 0, 0], [s256(0.15), s256(0.15), 1.0]),
        kf(1, scale=[s256(1.0), s256(1.0), 1.0], ease="out_back"),
        kf(3, scale=[s256(0.45), s256(0.45), 1.0], ease="out_quad")]))
    # r12 (particle-artist): static orange spark sprites removed (see high()); moving streak particles s1h2-p-streaks-*
    # r12: static chips removed (see high()); dark debris = sliver display particles of the particle layer
    # r16: s1h2-smoke-up dropped (hard grey "C" crescent between the bodies, compare t36-40; see high()).
    # r13: the flattened floor smoke (s1h2-smoke, y 0.18) is dropped: it read as a low grey smear on t35-39 (reviewer r9).
    return {"id": "bastion-s1-fx-low", "duration_ticks": 18, "orient": "yaw", "follow": False, "sample_ticks": 1,
            "elements": [e for e, _ in els]}, [m for _, m in els]


def budget(meta, duration):
    rows = []
    for t in range(duration):
        n = {"sprite": 0, "emit": 0}
        for m in meta:
            if m[0] == "sprite":
                if m[1] <= t <= m[2]:  # conservative: end tick counted as alive
                    n["sprite"] += 1
            else:
                _, start, e = m
                lo, hi = e["life_ticks"]
                for slot in range(e["count"]):
                    spawn = start + slot // e["rate_per_tick"]
                    if spawn <= t <= spawn + hi:  # conservative: longest life
                        n["emit"] += 1
        rows.append((t, n["sprite"], n["emit"], n["sprite"] + n["emit"]))
    return rows


class Dumper(yaml.SafeDumper):
    pass


def _float(d, v):
    t = f"{v:.4g}"
    if "." not in t and "e" not in t:
        t += ".0"
    return d.represent_scalar("tag:yaml.org,2002:float", t)


Dumper.add_representer(float, _float)
Dumper.ignore_aliases = lambda self, data: True


def write(path, header, doc):
    with open(os.path.join(ROOT, path), "w") as f:
        f.write(header)
        yaml.dump(doc, f, Dumper=Dumper, sort_keys=False, default_flow_style=None, width=140)


if __name__ == "__main__":
    for fn, name, header in ((high, "bastion-s1-fx-high", "# Bastion S1 hit 1 (video 11.933-12.5 s): gold ring smear in the tilted horizontal sweep plane (front + back) + cut streak + flash. Played at skill tick 3, contact = effect tick 4 (skill 7, r23).\n# Generated by tools/item-art/scripts/bastion_s1_fx.py; sprites from tools/item-art/scripts/bastion_s1_sprites.py. Elements only (rig is the animator's).\n"),
                             (low, "bastion-s1-fx-low", "# Bastion S1 hit 2 (video 13.1-13.7 s): yellow ink flash (2 ticks, depth-tested), low ring smear in the swing plane (4 front-only tiles), streak + speed lines, flash, particles. Played at skill tick 27, contact = effect tick 4 (skill 31).\n# Generated by tools/item-art/scripts/bastion_s1_fx.py. Elements only.\n")):
        doc, meta = fn()
        write(f"content/vfx/{name}.yaml", header, doc)
        print(name, "elements:", len(doc["elements"]))
        rows = budget(meta, doc["duration_ticks"])
        print(" tick sprites particles total:", " | ".join(f"e{t}:{a}+{b}={c}" for t, a, b, c in rows))
        print(" peak:", max(r[3] for r in rows))
