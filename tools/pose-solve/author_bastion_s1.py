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
Writes content/animations/bastion-s1-body.yaml and the `move:` keys of the `blade` frame in content/skills/bastion-greatsword-s1/skill.yaml
(the right hand is pinned onto that frame origin by the engine, ADR-0315; the clip has no right_arm track).
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
TW = -30.0  # arm twist of the cut1-a key: keeps the wrist swing t3 -> t4 under the 170 deg per tick slerp limit
DURATION = 58
LEG_W = 4 * K                        # leg box edge, blocks
ARM_MIN = 0.42                       # the arm cannot fold: a grip closer than this to the left shoulder pushes the hand past it
ARM_REACH = 0.62                     # shoulder to grip: the engine aims the left arm at the grip (two_hand_grip), arm length 0.586, lint allows 0.35 more
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
    def posed(sh):
        q = RM.Pose(root_rot=[0.0, spec.yaw * HIP_SHARE, spec.roll], root_pos=[sh[1] / K, -spec.drop / K, sh[0] / K])
        q.bones["body"]["rotation"] = np.array([spec.lean, spec.yaw * (1 - HIP_SHARE), 0.0])
        return q
    shift = np.array(spec.shift, float)
    if spec.two_hand:
        # both arms are fixed-length sticks: the hilt must sit ARM_MIN..ARM_REACH from BOTH shoulders. The blade path is the ring's source, so the
        # grip stays exactly where the key put it and the BODY steps under it (root shift, feet stay on their world targets) the least it must.
        g0 = to_world(spec.grip, spec.yaw, spec.frame)
        best = None
        for dx in np.arange(-0.5, 0.501, 0.05):
            for dz in np.arange(-0.5, 0.501, 0.05):
                sh = shift + np.array([dx, dz])
                lq = RM.fk(posed(sh))
                v = 0.0
                for j in (lq["joint_l"], lq["joint_r"]):
                    dd = float(np.linalg.norm(g0 - j))
                    v += max(0.0, ARM_MIN - dd) + max(0.0, dd - ARM_REACH)
                c = (v, float(np.hypot(dx, dz)))
                if best is None or c < best[0]:
                    best = (c, sh)
        shift = best[1]
    p = posed(shift)
    rootR = RM.euler_body(p.root_rot)
    lm = RM.fk(p)
    chestR = lm["body_R"]
    # legs: aim each leg at its foot target, stretch / shorten with scale y
    for s, name in (("r", "right_leg"), ("l", "left_leg")):
        foot = to_world(spec.feet[s], spec.yaw, "w") * np.array([1.0, 0.0, 1.0])
        for _ in range(3):  # a tilted leg box dips below its end centre by half the footprint: lift the target so the lowest CORNER is on the floor
            d = foot - lm["hip_" + s]
            dl = rootR.inv().apply(d)
            R = rot_from_to([0.0, -1.0, 0.0], dl)
            M = (rootR * R).as_matrix()
            foot[1] = min(0.2, 2.0 * 0.5 * LEG_W * (abs(M[1, 0]) + abs(M[1, 2])) * 0.5)
        d = foot - lm["hip_" + s]
        dl = rootR.inv().apply(d)
        R = rot_from_to([0.0, -1.0, 0.0], dl)
        p.bones[name]["rotation"] = np.array(RM.body_euler_of(R))
        p.bones[name]["scale"] = np.array([1.0, float(np.linalg.norm(d) / LEG_LEN), 1.0])
    # right arm: the grip lands on the target (3 dof, small preference for no twist)
    grip_t = to_world(spec.grip, spec.yaw, spec.frame)
    a_world = unit(to_world(spec.blade, spec.yaw, spec.frame))
    def res_r(x):
        q = p.copy()
        q.bones["right_arm"]["rotation"] = x
        return np.concatenate([(RM.fk(q)["grip"] - grip_t) * 10.0, [0.004 * (x[1] - spec.arm_twist)]])
    x0 = prev.bones["right_arm"]["rotation"] if prev is not None else \
        np.array(RM.body_euler_of(rot_from_to([0.0, -1.0, 0.0], chestR.inv().apply(grip_t - lm["joint_r"]))))
    sol = least_squares(res_r, x0)
    if sol.cost > 1e-4:  # warm start stuck in a local minimum: retry from the straight aim, keep the better
        x1 = np.array(RM.body_euler_of(rot_from_to([0.0, -1.0, 0.0], chestR.inv().apply(grip_t - lm["joint_r"]))))
        s1 = least_squares(res_r, x1)
        sol = s1 if s1.cost < sol.cost else sol
    p.bones["right_arm"]["rotation"] = sol.x
    lm = RM.fk(p)
    # blade frame: axis + broad-face normal (hint orthogonalised), tip kept above the floor
    a = a_world
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
    lh = max(lh, 0) if not spec.two_hand else float(np.linalg.norm(lm["hand_l"] - wp["grip"]))  # the lint's number: left hand end to the grip origin
    print(f"  {spec.name:<14} grip err {rg:.3f} lhand err {lh:.3f} grip {np.round(wp['grip'], 2).tolist()} shoulder {np.round(lm['joint_r'], 2).tolist()}"
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


GRIP_PIN = {  # world (x_left, y, z_fwd) grip of each key as the approved blade path reached it (the ring derives from it): the BODY moves, the blade does not
    "coil": (-0.383, 0.533, -0.297),
    "lift": (-0.383, 1.333, -0.297),        # straight above the coil grip: the lift stays in the (still vertical) blade plane
    "lift-hold": (-0.383, 1.333, -0.297),
    "cut1-a": (-0.727, 1.348, -0.132),
    "cut1-b": (-0.021, 1.308, 0.910),
    "cut1-hit": (0.642, 1.313, -0.109),
    "follow-a": (0.380, 1.228, -0.364),
    "follow-b": (0.255, 1.210, -0.397),
    "over": (0.205, 1.205, -0.400),
    "over-hold": (0.181, 1.207, -0.418),
    "settle-a": (0.134, 1.021, -0.435),     # the hand comes down evenly over 16-19 (0.19 blocks a tick, below the tip-speed the edge test judges)
    "settle-b": (0.088, 0.836, -0.451),
    "rest-away": (0.041, 0.650, -0.468),
    "rest-hold": (0.041, 0.650, -0.468),
    "crouch2": (0.075, 0.719, -0.364),
    "crouch2-hold": (0.087, 0.708, -0.326),
    "sweep2": (0.520, 0.667, 0.012),
    "thrust2": (0.030, 0.807, 0.707),
    "thrust2-mid": (-0.036, 0.810, 0.710),
    "thrust2-m2": (-0.058, 0.811, 0.711),
    "thrust2-m3": (-0.080, 0.812, 0.712),
    "thrust2-hold": (-0.101, 0.812, 0.713),
    "drop2": (-0.15, 0.70, 0.25),           # the blade runs out level (37-39) and is rolled for the slam with the hand still (40): the hand comes
    "drop2-b": (-0.22, 0.60, -0.30),        # back in to the body.s right-front, then right-back, descending 0.1 a tick (at arm.s length of each beat.s shoulder)
    "coil-end": (-0.383, 0.533, -0.297),
}


class Beat:
    def __init__(self, tick, yaw, th, tip_y, face, lean=22.0, drop=0.2, grip_y=0.92, reach=0.42, look=None, roll=0.0, name="", twist=0.0):
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
                grip=W(GRIP_PIN.get(b.name, grip)), blade=W(a), face=W(n), look=W(look), arm_twist=b.twist)


ENEMY = np.array([0.0, 1.3, 4.0])
AWAY = np.array([0.8, 0.6, -3.5])
BEATS = [  # skill tick, chest yaw, tip azimuth, tip height, face; ticks 1, 8-9 and 32-33 have no rig tick (cast tick / hit-stop)
    Beat(0, -35, -62, 0.05, "cam", lean=24, name="coil"),
    # ADR-0315: the hand lifts straight up over t0-2 while the blade is still edge-up (the lift is IN the blade plane), the blade rolls flat
    # at t2-3 with the hand still, then the wind-back t3-5 travels level: every tick the tip moves, it moves along the edge
    Beat(2, -35, -62, 2.9, "cam", lean=24, grip_y=1.33, name="lift"),
    Beat(3, -35, -62, 2.9, "cam", lean=24, grip_y=1.33, name="lift-hold"),
    # r5: the blade lies IN the ring plane through the swing + follow-through (plane ~y 1.3, derived from the tip path): grip at 1.2-1.25
    # (arm raised to ~shoulder height, shallower crouch) and tip at 1.3-1.4, so blade-vs-plane stays under ~6 deg on skill 5-16
    Beat(5, 5, 5, 1.30, "ccw", lean=18, drop=0.12, grip_y=1.32, name="cut1-a", twist=TW),                          # behind -> the near side
    Beat(6, 50, 85, 1.38, "ccw", lean=16, drop=0.10, grip_y=1.32, reach=0.55, name="cut1-b"),  # across the enemy (ring snap)
    Beat(7, 95, 165, 1.36, "ccw", lean=20, drop=0.14, grip_y=1.34, name="cut1-hit"),       # contact, hit-stop t7-9
    Beat(10, 108, 212, 1.34, "ccw", lean=20, drop=0.14, grip_y=1.34, look=ENEMY, name="follow-a"),
    Beat(11, 113, 232, 1.32, ("ccw", "cam", 0.4), lean=19, drop=0.14, grip_y=1.34, look=ENEMY, name="follow-b"),
    Beat(12, 115, 240, 1.30, ("ccw", "cam", 0.7), lean=18, drop=0.14, grip_y=1.34, look=ENEMY, name="over"),
    Beat(16, 120, 243, 1.30, "cam", lean=18, drop=0.14, grip_y=1.34, look=ENEMY, name="over-hold"),
    Beat(17, 140, 250, 1.0, "cam", lean=20, drop=0.2, look=AWAY, name="settle-a"),
    Beat(18, 160, 256, 0.35, "cam", lean=18, drop=0.2, look=AWAY, name="settle-b"),
    Beat(19, 172, 258, 0.05, "cam", lean=17, drop=0.2, look=AWAY, name="rest-away"),
    Beat(27, 172, 258, 0.05, "cam", lean=17, drop=0.2, look=AWAY, name="rest-hold"),
    Beat(28, 120, 266, 0.6, ("cam", "cw", 0.6), lean=36, drop=0.3, grip_y=1.0, look=np.array([1.0, 0.0, 0.5]), name="crouch2"),
    Beat(29, 112, 268, 0.65, "cw", lean=38, drop=0.32, grip_y=1.0, look=np.array([1.0, 0.0, 0.8]), name="crouch2-hold"),
    Beat(30, 60, 182, 0.9, "cw", lean=34, drop=0.3, grip_y=0.95, reach=0.55, look=ENEMY, name="sweep2"),
    Beat(31, 0, 92, 1.0, "cw", lean=24, drop=0.24, grip_y=1.05, reach=0.7, look=ENEMY, name="thrust2"),
    Beat(35, -2, 86, 0.99, "cw", lean=23, drop=0.23, grip_y=1.05, reach=0.69, look=ENEMY, name="thrust2-mid"),  # the body lerps 7 ticks here: a middle key keeps the left hand on the hilt
    Beat(36, -3, 84, 0.99, "cw", lean=23, drop=0.23, grip_y=1.05, reach=0.69, look=ENEMY, name="thrust2-m2"),
    Beat(37, -4, 82, 0.98, "cw", lean=22, drop=0.22, grip_y=1.05, reach=0.68, look=ENEMY, name="thrust2-m3"),
    Beat(38, -5, 80, 0.98, "cw", lean=22, drop=0.22, grip_y=1.05, reach=0.68, look=ENEMY, name="thrust2-hold"),
    Beat(39, -20, 10, 0.8, "cw", lean=22, drop=0.2, look=ENEMY, name="drop2"),
    Beat(40, -30, -40, 0.45, ("cw", "cam", 0.6), lean=23, drop=0.2, look=ENEMY, name="drop2-b"),
    Beat(41, -35, -62, 0.05, "cam", lean=24, name="coil-end"),
]


def rig_tick(skill_tick):
    """ADR-0315: the engine pushes the clip frame of its own tick to the puppet (clip tick = skill tick since the rig row's cast tick); the
    hit-stop freezes no longer shift the body, the frame keys span them like the blade's rotate keys do."""
    return skill_tick


# ------------------------------------------------------------------------------------------------ the hand path (blade frame `move:` keys)
G_LEN = float(np.linalg.norm(RM.HELD_GRIP_OFFSET))  # joint -> grip, blocks (0.600): the look-at IK lands the grip at this distance on the line to the target
REACH_TOL = 0.02                                     # a target farther off the arm's sphere than this gets its own key (the lerp chord between keys cuts inside)


def body_doc_of(poses):
    """The written clip as the engine samples it (linear between keys, held outside), for per-tick FK."""
    doc = {"bones": {}, "root": {"rotation": [], "position": []}}
    for st, rt, p, ease, _ in poses:
        doc["root"]["rotation"].append({"t": rt, "v": p.root_rot.tolist()})
        doc["root"]["position"].append({"t": rt, "v": p.root_pos.tolist()})
        for bone in RM.BONE_NAMES:
            ch = doc["bones"].setdefault(bone, {"rotation": [], "scale": []})
            ch["rotation"].append({"t": rt, "v": p.bones[bone]["rotation"].tolist()})
            ch["scale"].append({"t": rt, "v": p.bones[bone]["scale"].tolist()})
    return doc


def track(keys, t):
    """FrameGrammar.track: each key holds `cur` until its `at`, lerps to `to` over `for`, a `for: 0` key is `to` from `at` on."""
    cur = np.zeros(3)
    for at, dur, to in keys:
        if t < at:
            return cur
        if dur <= 0 or t >= at + dur:
            cur = np.array(to, float)
            continue
        return cur + (np.array(to, float) - cur) * (t - at) / dur
    return cur


LIFT_Y = 1.45  # hand height the lift ends at: the cut height (cut1-a's grip)


def lift_arc(coil: RM.Pose):
    """The lift t0 -> t2 (ADR-0315): the arm swings in the sagittal plane (x fixed) about the coil's shoulder, up through the back, until the
    hand is at LIFT_Y. Every tick's travel is then in the (still vertical) blade plane, and every point is at arm's length. Returns the
    t1 point and the end point (the `lift` / `lift-hold` pin)."""
    lm = RM.fk(coil)
    j, d0 = lm["joint_r"], lm["grip"] - lm["joint_r"]
    r = math.sqrt(max(G_LEN * G_LEN - d0[0] * d0[0], 1e-6))
    phi0 = math.atan2(d0[2], d0[1])                       # angle in the (y, z) plane, 0 = straight up, -90 = straight back
    phi1 = -math.acos(max(-1.0, min(1.0, (LIFT_Y - j[1]) / r)))  # the back-side solution at LIFT_Y
    if phi0 > 0:
        phi0 -= 2 * math.pi                               # the coil hangs behind: measure the way through the back
    def at(phi):
        return j + np.array([d0[0], r * math.cos(phi), r * math.sin(phi)])
    return at(phi0 + (phi1 - phi0) * 0.5), at(phi1)


def hand_path(poses, extra):
    """`move:` keys of the blade frame (world x_left, y, z_fwd): the solved grip of every beat plus the `extra` points (tick -> point), then a
    key on every tick the lerp between keys leaves the arm's reach sphere (the joint moves with the body, so the chord between two reachable
    grips need not be reachable)."""
    doc = body_doc_of(poses)
    pts = {}
    for st, rt, p, ease, name in poses:
        pts[rt] = RM.fk(p)["grip"]
    pts.update(extra)
    clamped = []
    for _ in range(60):
        ticks = sorted(pts)
        keys = [(ticks[0], 0, pts[ticks[0]])] + [(ticks[i], ticks[i + 1] - ticks[i], pts[ticks[i + 1]]) for i in range(len(ticks) - 1)]
        worst = None
        for t in range(DURATION + 1):
            if t in pts:
                continue
            joint = RM.fk(RM.pose_from_yaml(doc, None, t))["joint_r"]
            target = track(keys, t)
            reach = float(np.linalg.norm(target - joint)) - G_LEN
            if abs(reach) > REACH_TOL and (worst is None or abs(reach) > abs(worst[1])):
                worst = (t, reach, joint + unit(target - joint) * G_LEN)
        if worst is None:
            break
        pts[worst[0]] = worst[2]
        clamped.append(f"t{worst[0]} {worst[1]:+.3f}")
    else:
        raise SystemExit("hand path: the reach sphere keys did not settle in 60 passes")
    ticks = sorted(pts)
    keys = [(ticks[0], 0, pts[ticks[0]])] + [(ticks[i], ticks[i + 1] - ticks[i], pts[ticks[i + 1]]) for i in range(len(ticks) - 1)]
    print("hand path: reach-sphere keys added " + (", ".join(clamped) if clamped else "none"))
    for t in range(DURATION + 1):
        joint = RM.fk(RM.pose_from_yaml(doc, None, t))["joint_r"]
        target = track(keys, t)
        print(f"  t{t:<3} grip {np.round(target, 3).tolist()}  reach {float(np.linalg.norm(target - joint)) - G_LEN:+.3f}")
    return keys


def move_yaml(keys):
    """Blade frame `move:` lines: origin caster (feet), v forward / w up / s right, so (x_left, y, z_fwd) -> {v: z, w: y, s: -x}."""
    out = ["    move:"]
    for i, (at, dur, to) in enumerate(keys):
        if i > 0 and np.allclose(to, keys[i - 1][2], atol=1e-6):
            continue  # a hold is the key before it holding; the next key starts from there
        out.append(f"      - {{at: {at}, for: {dur}, to: {{v: {to[2]:.2f}, w: {to[1]:.2f}, s: {-to[0]:.2f}}}}}")
    return out


def r1(v):
    return [round(float(x) + 0.0, 1) for x in v]


def main():
    poses = []
    print("poses (th = tip azimuth from above, + = CCW; roll = turn of the broad face about the blade since the previous key):")
    prev, prev_wp = None, None
    extra = {}
    for b in BEATS:
        s = spec_of(b)
        p = build(s, prev)
        if b.name == "coil":
            mid, end = lift_arc(p)
            extra[1] = mid
            GRIP_PIN["lift"] = GRIP_PIN["lift-hold"] = tuple(float(x) for x in end)
            print(f"  lift arc: t1 {np.round(mid, 3).tolist()} -> t2 {np.round(end, 3).tolist()} (shoulder {np.round(RM.fk(p)['joint_r'], 3).tolist()})")
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
    # the vanilla held pose (all zero), eased to over the hold (a step here snapped the hand 0.6 blocks in one tick: the tip left the cut plane, ADR-0314)
    poses.append((52, rig_tick(52), RM.Pose(), "linear", "hand-back"))
    # body yaml. No right_arm track: the engine pins the right hand onto the blade frame's origin by look-at IK (ADR-0315), the hand path
    # below is that origin's `move:` keys, so the arm the solver found here is only the source of those keys.
    L = []
    L.append("# bastion-s1-body: HAND-AUTHORED 3D performance, written by tools/pose-solve/author_bastion_s1.py (edit the pose table there and rerun).")
    L.append("# Per-tick keys through the swings (cut 1 CCW from above, cut 2 CW), holds between; spine (`body`) carries the lean + most of the twist (ADR-0278).")
    L.append("# Keys are SKILL ticks (the engine drives the puppet from its own tick, ADR-0315). No right_arm track: the right hand is pinned onto the")
    L.append("# blade frame's origin (content/skills/bastion-greatsword-s1/skill.yaml `move:` keys, written by the same script).")
    L.append("# beats (skill tick): " + ", ".join(f"{n} {st}" for st, rt, _, _, n in poses))
    L += ["id: bastion-s1-body", f"length_ticks: {DURATION}", "loop: false", "lock_movement: false", "blend_in_ticks: 0", "blend_out_ticks: 0", "bones:"]

    def key(rt, v, ease):
        return f"      - {{ t: {rt}, v: {r1(v)}" + (f", ease: {ease} }}" if ease and ease != "linear" else " }")
    for bone in ("head", "body", "left_arm", "right_leg", "left_leg"):
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
    # the blade frame's hand path: the `move:` block of frame `blade` in the skill (the block holds nothing but `- {at: ...}` lines)
    skill = ROOT / "content/skills/bastion-greatsword-s1/skill.yaml"
    txt = skill.read_text()
    block = "\n".join(move_yaml(hand_path(poses, extra))) + "\n"
    new, n = re.subn(r"    move:\n(      - \{at: .*\n)+", block, txt, count=1)
    if n != 1:
        raise SystemExit("skill.yaml: no `    move:` block of `- {at: ...}` lines under the blade frame to rewrite")
    skill.write_text(new)
    print("wrote content/animations/bastion-s1-body.yaml and the blade `move:` keys of", skill.relative_to(ROOT))


if __name__ == "__main__":
    main()
