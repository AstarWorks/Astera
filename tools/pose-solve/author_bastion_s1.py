#!/usr/bin/env python3
"""Hand-authored 3D performance of bastion-greatsword-s1 (月輪の薙ぎ): body keys + wrist keys from pose intents.

    python3 tools/pose-solve/author_bastion_s1.py [--dump]

The keypoint solver (solve.py) fits the 2D reference picture; a 2D side-view picture under-determines a 3D pose and its fit
read wrong from every other angle (upright, narrow, chest to the camera). This script authors the INTENT of each reference beat
in 3D instead: twist / lean / crouch of the root, feet planted on the floor, the grip at a target, the left hand on the hilt, the
blade along a direction with its broad face toward a hint, the head looking at a point. The FK is rigmodel.py (the runtime math).

Frames: world = rig frame of the cast (X = the caster's LEFT at the cast, Y up, Z = toward the enemy); `yaw` > 0 turns the
chest to its left (a right-handed forehand spin). Positions in blocks, feet at y 0. Points can be given in the WORLD frame (`w`)
or the pose's HEADING frame (`h`: f = where the chest faces after the yaw, l = its left, u = up).
Writes content/animations/bastion-s1-body.yaml and the `swing:` keys of content/vfx/bastion-s1-rig.yaml.
"""
from __future__ import annotations

import math
import re
import sys
from pathlib import Path

import numpy as np
from scipy.optimize import least_squares
from scipy.spatial.transform import Rotation as Rot

sys.path.insert(0, str(Path(__file__).resolve().parent))
import rigmodel as RM  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
K = RM.PX * RM.PLAYER_SCALE          # blocks per px
LEG_LEN = 12.0 * K                   # hip joint -> ankle
FREEZES = [(7, 2), (31, 2)]          # the skill's play_vfx freeze windows (effect ticks)
DURATION = 58
FLOOR = 0.03                         # lowest tip height


def unit(v):
    v = np.asarray(v, float)
    return v / np.linalg.norm(v)


def heading(yaw):
    r = math.radians(yaw)
    return np.array([math.sin(r), 0.0, math.cos(r)]), np.array([math.cos(r), 0.0, -math.sin(r)])


def to_world(p, yaw, frame):
    """(f, l, u) heading coords -> world, or a world (x_left, y, z_fwd) triple."""
    if frame == "w":
        return np.array([p[1], p[2], p[0]], float)  # world points are also written (fwd, left, up)
    f, l = heading(yaw)
    return f * p[0] + l * p[1] + np.array([0.0, p[2], 0.0])


def rot_from_to(a, b):
    a, b = unit(a), unit(b)
    c = np.cross(a, b)
    s = np.linalg.norm(c)
    if s < 1e-9:
        return Rot.identity() if a @ b > 0 else Rot.from_rotvec(np.pi * unit(np.cross(a, [1.0, 0.0, 0.0])))
    return Rot.from_rotvec(c / s * math.atan2(s, a @ b))


class Spec:
    def __init__(self, name, yaw, lean, drop, feet, grip, blade, face, look, roll=0.0, frame="h", shift=(0.0, 0.0), two_hand=True,
                 arm_twist=0.0, left_hand=None):
        d = dict(locals())
        d.pop("self")
        self.__dict__.update(d)


HIP_SHARE = 0.45                     # share of the chest yaw the hips take (root); the rest twists the spine (`body`, ADR-0278)


def build(spec: Spec, prev: RM.Pose | None = None) -> RM.Pose:
    """One pose: hips take HIP_SHARE of the yaw (root), the spine (`body`) the rest of the yaw and the whole lean, so the chest
    frame is Ry(yaw) Rx(lean) as before while the legs stay planted. `prev` warm-starts the arm solves (continuous eulers)."""
    p = RM.Pose(root_rot=[0.0, spec.yaw * HIP_SHARE, spec.roll], root_pos=[spec.shift[1] / K, -spec.drop / K, spec.shift[0] / K])
    p.bones["body"]["rotation"] = np.array([spec.lean, spec.yaw * (1 - HIP_SHARE), 0.0])
    rootR = RM.euler_body(p.root_rot)
    lm = RM.fk(p)
    chestR = lm["body_R"]
    # legs: aim each leg at its foot target, stretch / shorten with scale y
    for s, name in (("r", "right_leg"), ("l", "left_leg")):
        foot = to_world(spec.feet[s], spec.yaw, "w") * np.array([1.0, 0.0, 1.0])
        d = foot - lm["hip_" + s]
        dl = rootR.inv().apply(d)
        R = rot_from_to([0.0, -1.0, 0.0], dl)
        p.bones[name]["rotation"] = np.array(RM.body_euler_of(R))
        p.bones[name]["scale"] = np.array([1.0, float(np.linalg.norm(d) / LEG_LEN), 1.0])
    # right arm: the grip lands on the target (3 dof, small preference for no twist)
    grip_t = to_world(spec.grip, spec.yaw, spec.frame)

    def res_r(x):
        q = p.copy()
        q.bones["right_arm"]["rotation"] = x
        return np.concatenate([(RM.fk(q)["grip"] - grip_t) * 10.0, [0.004 * (x[1] - spec.arm_twist)]])
    x0 = prev.bones["right_arm"]["rotation"] if prev is not None else \
        np.array(RM.body_euler_of(rot_from_to([0.0, -1.0, 0.0], chestR.inv().apply(grip_t - lm["joint_r"]))))
    sol = least_squares(res_r, x0)
    p.bones["right_arm"]["rotation"] = sol.x
    lm = RM.fk(p)
    # blade frame: axis + broad-face normal (hint orthogonalised), tip kept above the floor
    a = unit(to_world(spec.blade, spec.yaw, spec.frame))
    grip = lm["grip"]
    if grip[1] + a[1] * 1.75 < FLOOR:
        hz = unit([a[0], 0.0, a[2]])
        sp = (FLOOR - grip[1]) / 1.75
        a = hz * math.sqrt(max(0.0, 1 - sp * sp)) + np.array([0.0, sp, 0.0])
    n = to_world(spec.face, spec.yaw, spec.frame)
    n = unit(n - (n @ a) * a)
    if prev is not None and n @ RM.weapon(prev)["normal"] < 0:
        n = -n  # the blade is a flat item sprite: both faces / edges read the same, so take the side with the least roll
    e = np.cross(n, a)
    al = unit(RM.WEAPON_TIP_LOCAL)
    el = unit(RM.WEAPON_EDGE_LOCAL)
    nl = np.cross(al, el)
    Fw = np.stack([a, e, n], axis=1) @ np.stack([al, el, nl], axis=1).T
    W = lm["frame_R"].inv() * Rot.from_matrix(Fw)
    p.wrist = np.array(RM.wrist_euler_of(W))
    if prev is not None:  # same rotation, euler branch closest to the previous key (no long-way lerp between keys)
        alt = np.array([p.wrist[0] + 180.0, 180.0 - p.wrist[1], p.wrist[2] + 180.0])
        def wrap(v):
            return (v + 180.0) % 360.0 - 180.0
        cands = [p.wrist, alt]
        best = None
        for c in cands:
            c2 = prev.wrist + wrap(c - prev.wrist)
            d = float(np.abs(c2 - prev.wrist).sum())
            if best is None or d < best[0]:
                best = (d, c2)
        p.wrist = best[1]
    # left arm: hand on the hilt below the right hand (toward the pommel)
    if spec.two_hand:
        lt = grip - a * 0.17 if spec.left_hand is None else to_world(spec.left_hand, spec.yaw, spec.frame)

        def res_l(x):
            q = p.copy()
            q.bones["left_arm"]["rotation"] = x
            return np.concatenate([(RM.fk(q)["hand_l"] - lt) * 10.0, [0.004 * x[1]]])
        x0 = prev.bones["left_arm"]["rotation"] if prev is not None else \
            np.array(RM.body_euler_of(rot_from_to([0.0, -1.0, 0.0], chestR.inv().apply(lt - lm["joint_l"]))))
        p.bones["left_arm"]["rotation"] = least_squares(res_l, x0).x
    # head (child of the spine): look at a point
    tgt = to_world(spec.look, spec.yaw, spec.frame)
    dl = chestR.inv().apply(unit(tgt - lm["neck"] - np.array([0.0, 4 * K, 0.0])))
    yh = max(-75.0, min(75.0, math.degrees(math.atan2(dl[0], dl[2]))))
    ph = max(-40.0, min(60.0, math.degrees(math.asin(-dl[1]))))
    p.bones["head"]["rotation"] = np.array([ph, yh, 0.0])
    return p


def report(spec, p):
    lm = RM.fk(p)
    wp = RM.weapon(p, lm)
    rg = abs(np.linalg.norm(lm["grip"] - to_world(spec.grip, spec.yaw, spec.frame)))
    lh = np.linalg.norm(lm["hand_l"] - (wp["grip"] - wp["axis"] * 0.17)) if spec.two_hand else 0
    sc = [round(float(p.bones[n]["scale"][1]), 2) for n in ("right_leg", "left_leg")]
    print(f"  {spec.name:<14} grip err {rg:.3f} lhand err {lh:.3f} grip {np.round(wp['grip'], 2).tolist()}"
          f" head_top y {lm['head_top'][1]:.2f} hips y {lm['hips'][1]:.2f} leg scale {sc}")


# ------------------------------------------------------------------------------------------------ the performance (skill ticks)
# Reference (Svaaq9F7Ty8 11.633-14.233), checked frame by frame (beat sheet: tools/skill-compare notes, round r3):
#   cut 1 = COUNTER-CLOCKWISE from above (左回り): coil t0-3 (3/4 to the compare camera, blade low behind), wind-back t4, travel
#   t5-6 (the ring snaps at t6), contact t7 (back to the camera, hit-stop), follow-through t10-12, over-shoulder hold to t16, the
#   blade drops and the body keeps turning to the rest t19-27 (facing away from the enemy, screen-left on the compare camera).
#   cut 2 = CLOCKWISE (右回り), the body reverses: crouch t28-29 (back to the camera, blade trailing), low sweep round the feet t30
#   (flash), contact + chest thrust t31-38, the blade passes the near side right->left t39-40, coil t41, hand-back t52.
# Top-view azimuth `th` (deg): 0 = the caster's right (compare camera side), 90 = the enemy, 180 = the caster's left, 270 = behind;
# increasing th = CCW seen from above. A beat gives the chest yaw, lean, crouch, the blade tip azimuth + height, the grip height /
# reach and the face mode: "ccw" / "cw" = edge leading in that travel sense (the broad face lies in the swing plane), "cam" = flat
# face toward the compare camera (rests), or a world normal.
FEET = {"l": (0.45, 0.14, 0.0), "r": (-0.42, -0.36, 0.0)}  # (f, l, u) world
CAM_SIDE = np.array([-0.62, 0.78, 0.0])  # world xyz: up + toward the compare side camera (the caster's right)
BLADE = 1.75


def az(th):
    r = math.radians(th)
    return np.array([-math.cos(r), 0.0, math.sin(r)])


def tangent(th, sense):
    r = math.radians(th)
    t = np.array([math.sin(r), 0.0, math.cos(r)])  # d(az)/d(th): CCW from above
    return t if sense == "ccw" else -t


def W(v):
    """world xyz -> the (f, l, u) triple of a frame="w" Spec."""
    return (float(v[2]), float(v[0]), float(v[1]))


class Beat:
    def __init__(self, tick, yaw, th, tip_y, face, lean=22.0, drop=0.2, grip_y=0.92, reach=0.42, look=None, roll=0.0, name=""):
        self.__dict__.update(dict(locals()))
        self.__dict__.pop("self")


def spec_of(b: Beat) -> Spec:
    chest = 90.0 + b.yaw
    gy = b.grip_y - b.drop
    off = max(-80.0, min(80.0, ((b.th - chest + 180.0) % 360.0) - 180.0))
    grip = az(chest + off) * b.reach + np.array([0.0, gy, 0.0])
    # blade direction: horizontal toward th, pitched so the tip lands at tip_y
    sp = max(-0.98, min(0.98, (b.tip_y - gy) / BLADE))
    a = az(b.th) * math.sqrt(1 - sp * sp) + np.array([0.0, sp, 0.0])
    if b.face in ("ccw", "cw"):
        n = np.cross(a, tangent(b.th, b.face))
    elif isinstance(b.face, tuple):  # blend between two modes: ("ccw" | "cw" | "cam", "cam" | ..., weight of the second)
        def one(m):
            return CAM_SIDE if m == "cam" else np.cross(a, tangent(b.th, m))
        n0, n1 = unit(one(b.face[0])), unit(one(b.face[1]))
        n1 = n1 if n0 @ n1 >= 0 else -n1  # either face reads the same (flat sprite): blend toward the nearer one
        n = n0 * (1 - b.face[2]) + n1 * b.face[2]
    else:
        n = CAM_SIDE
    look = b.look if b.look is not None else np.array([0.0, 1.2, 4.0])
    return Spec(b.name or f"t{b.tick}", yaw=b.yaw, lean=b.lean, drop=b.drop, roll=b.roll, frame="w", feet=FEET,
                grip=W(grip), blade=W(a), face=W(n), look=W(look))


ENEMY = np.array([0.0, 1.3, 4.0])
AWAY = np.array([0.8, 0.6, -3.5])
BEATS = [  # skill tick, chest yaw, tip azimuth, tip height, face; ticks 1, 8-9 and 32-33 have no rig tick (cast tick / hit-stop)
    Beat(0, -35, -62, 0.05, "cam", lean=24, name="coil"),
    Beat(3, -35, -62, 0.05, "cam", lean=24, name="coil-hold"),
    Beat(4, -45, -75, 1.0, ("cam", "ccw", 0.6), lean=28, drop=0.24, grip_y=1.15, name="wind-back"),  # anticipation: lift behind the shoulder, against the cut
    Beat(5, 5, 5, 1.3, "ccw", lean=24, drop=0.2, name="cut1-a"),                          # behind -> the near side
    Beat(6, 50, 85, 1.85, "ccw", lean=18, drop=0.17, grip_y=1.15, reach=0.55, name="cut1-b"),  # across the enemy (ring snap)
    Beat(7, 95, 165, 1.35, "ccw", lean=30, drop=0.24, grip_y=1.15, name="cut1-hit"),       # contact, hit-stop t7-9
    Beat(10, 108, 212, 1.5, "ccw", lean=26, drop=0.22, grip_y=1.2, look=ENEMY, name="follow-a"),
    Beat(11, 113, 232, 1.6, ("ccw", "cam", 0.4), lean=24, drop=0.2, grip_y=1.2, look=ENEMY, name="follow-b"),
    Beat(12, 115, 240, 1.62, ("ccw", "cam", 0.7), lean=22, drop=0.2, grip_y=1.2, look=ENEMY, name="over"),
    Beat(16, 120, 243, 1.55, "cam", lean=22, drop=0.2, grip_y=1.2, look=ENEMY, name="over-hold"),
    Beat(17, 140, 250, 1.0, "cam", lean=20, drop=0.2, look=AWAY, name="settle-a"),
    Beat(18, 160, 256, 0.35, "cam", lean=18, drop=0.2, look=AWAY, name="settle-b"),
    Beat(19, 172, 258, 0.05, "cam", lean=17, drop=0.2, look=AWAY, name="rest-away"),
    Beat(27, 172, 258, 0.05, "cam", lean=17, drop=0.2, look=AWAY, name="rest-hold"),
    Beat(28, 120, 266, 0.1, ("cam", "cw", 0.6), lean=36, drop=0.3, grip_y=1.0, look=np.array([1.0, 0.0, 0.5]), name="crouch2"),
    Beat(29, 112, 268, 0.12, "cw", lean=38, drop=0.32, grip_y=1.0, look=np.array([1.0, 0.0, 0.8]), name="crouch2-hold"),
    Beat(30, 60, 182, 0.25, "cw", lean=34, drop=0.3, grip_y=0.95, reach=0.55, look=ENEMY, name="sweep2"),
    Beat(31, 0, 92, 0.6, "cw", lean=24, drop=0.24, grip_y=1.05, reach=0.7, look=ENEMY, name="thrust2"),
    Beat(38, -5, 80, 0.58, "cw", lean=22, drop=0.22, grip_y=1.05, reach=0.68, look=ENEMY, name="thrust2-hold"),
    Beat(39, -20, 10, 0.2, "cw", lean=22, drop=0.2, look=ENEMY, name="drop2"),
    Beat(40, -30, -40, 0.06, ("cw", "cam", 0.6), lean=23, drop=0.2, look=ENEMY, name="drop2-b"),
    Beat(41, -35, -62, 0.05, "cam", lean=24, name="coil-end"),
]


def rig_tick(skill_tick):
    if skill_tick == 0:
        return 0  # the rig starts one effect tick after the cast: rig 0 is what the cast tick shows
    stops = RM.stops_from_freezes(FREEZES, base=1)
    for l in range(DURATION + 1):
        if RM.effect_tick_of_rig_tick(l, 1, stops) == skill_tick:
            return l
    raise SystemExit(f"skill tick {skill_tick} is inside a freeze window: no rig tick lands on it")


def r1(v):
    return [round(float(x) + 0.0, 1) for x in v]


def main():
    poses = []
    print("poses (th = tip azimuth from above, + = CCW; roll = turn of the broad face about the blade since the previous key):")
    prev, prev_wp = None, None
    for b in BEATS:
        s = spec_of(b)
        p = build(s, prev)
        report(s, p)
        wp = RM.weapon(p)
        th = math.degrees(math.atan2(wp["tip"][2], -wp["tip"][0]))
        if prev_wp is not None:
            pth = math.degrees(math.atan2(prev_wp["tip"][2], -prev_wp["tip"][0]))
            dth = (th - pth + 180) % 360 - 180
            n0 = prev_wp["normal"] - (prev_wp["normal"] @ wp["axis"]) * wp["axis"]
            roll = math.degrees(math.acos(max(-1.0, min(1.0, float(unit(n0) @ wp["normal"])))))
            sense = "CCW" if dth > 1 else "CW" if dth < -1 else "-"
            print(f"      th {th:7.1f}  d {dth:+6.1f} {sense:<3}  roll {roll:5.1f}  tip {np.round(wp['tip'], 2).tolist()}")
        prev, prev_wp = p, wp
        poses.append((b.tick, rig_tick(b.tick), p, None if not poses else "linear", s.name))
    poses.append((52, rig_tick(52), RM.Pose(), "step", "hand-back"))  # the vanilla held pose (all zero)
    # body yaml
    L = []
    L.append("# bastion-s1-body: HAND-AUTHORED 3D performance, written by tools/pose-solve/author_bastion_s1.py (edit the pose table there and rerun).")
    L.append("# Per-tick keys through the swings (cut 1 CCW from above, cut 2 CW), holds between; spine (`body`) carries the lean + most of the twist (ADR-0278).")
    L.append("# Keys are RIG ticks; the hit-stop freezes (7/31) shift them to skill ticks.")
    L.append("# beats (skill tick -> rig tick): " + ", ".join(f"{n} {st}->{rt}" for st, rt, _, _, n in poses))
    L += ["id: bastion-s1-body", f"length_ticks: {DURATION}", "loop: false", "lock_movement: false", "blend_in_ticks: 0", "blend_out_ticks: 0", "bones:"]

    def key(rt, v, ease):
        return f"      - {{ t: {rt}, v: {r1(v)}" + (f", ease: {ease} }}" if ease and ease != "linear" else " }")
    for bone in ("head", "body", "right_arm", "left_arm", "right_leg", "left_leg"):
        L.append(f"  {bone}:")
        L.append("    rotation:")
        for st, rt, p, ease, _ in poses:
            L.append(key(rt, p.bones[bone]["rotation"], ease))
        if bone.endswith("leg"):
            L.append("    scale:")
            for st, rt, p, ease, _ in poses:
                L.append(f"      - {{ t: {rt}, v: [1, {round(float(p.bones[bone]['scale'][1]), 3)}, 1]" + (f", ease: {ease} }}" if ease and ease != "linear" else " }"))
    L.append("root:")
    L.append("  rotation:")
    for st, rt, p, ease, _ in poses:
        L.append(key(rt, p.root_rot, ease).replace("      ", "    ", 1))
    L.append("  position:")
    for st, rt, p, ease, _ in poses:
        L.append(key(rt, p.root_pos, ease).replace("      ", "    ", 1))
    (ROOT / "content/animations/bastion-s1-body.yaml").write_text("\n".join(L) + "\n")
    # rig swing keys
    rig = ROOT / "content/vfx/bastion-s1-rig.yaml"
    txt = rig.read_text()
    sw = ["  swing:"]
    for st, rt, p, ease, name in poses:
        sw.append(f"  - {{ t: {rt}, rotate: {r1(p.wrist)}" + (f", ease: {ease} }}" if ease and ease != "linear" else " }") + f"   # skill {st} {name}")
    txt = re.sub(r"  swing:\n(  - .*\n)+", "\n".join(sw) + "\n", txt)
    rig.write_text(txt)
    print("wrote", "content/animations/bastion-s1-body.yaml", "and the swing keys of", rig.relative_to(ROOT))


if __name__ == "__main__":
    main()
