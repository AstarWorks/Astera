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


def build(spec: Spec) -> RM.Pose:
    p = RM.Pose(root_rot=[spec.lean, spec.yaw, spec.roll], root_pos=[spec.shift[1] / K, -spec.drop / K, spec.shift[0] / K])
    rootR = RM.euler_body(p.root_rot)
    lm = RM.fk(p)
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
    x0 = np.array(RM.body_euler_of(rot_from_to([0.0, -1.0, 0.0], rootR.inv().apply(grip_t - lm["joint_r"]))))
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
    e = np.cross(n, a)
    al = unit(RM.WEAPON_TIP_LOCAL)
    el = unit(RM.WEAPON_EDGE_LOCAL)
    nl = np.cross(al, el)
    Fw = np.stack([a, e, n], axis=1) @ np.stack([al, el, nl], axis=1).T
    W = lm["frame_R"].inv() * Rot.from_matrix(Fw)
    p.wrist = np.array(RM.wrist_euler_of(W))
    # left arm: hand on the hilt below the right hand (toward the pommel)
    if spec.two_hand:
        lt = grip - a * 0.17 if spec.left_hand is None else to_world(spec.left_hand, spec.yaw, spec.frame)

        def res_l(x):
            q = p.copy()
            q.bones["left_arm"]["rotation"] = x
            return np.concatenate([(RM.fk(q)["hand_l"] - lt) * 10.0, [0.004 * x[1]]])
        x0 = np.array(RM.body_euler_of(rot_from_to([0.0, -1.0, 0.0], rootR.inv().apply(lt - lm["joint_l"]))))
        p.bones["left_arm"]["rotation"] = least_squares(res_l, x0).x
    # head: look at a point
    tgt = to_world(spec.look, spec.yaw, spec.frame)
    dl = rootR.inv().apply(unit(tgt - lm["neck"] - np.array([0.0, 4 * K, 0.0])))
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
    feet = [np.round(lm["ankle_" + s], 2).tolist() for s in "rl"]
    print(f"  {spec.name:<14} grip err {rg:.3f} lhand err {lh:.3f} grip {np.round(wp['grip'], 2).tolist()} tip {np.round(wp['tip'], 2).tolist()}"
          f" head_top y {lm['head_top'][1]:.2f} hips y {lm['hips'][1]:.2f} leg scale {sc} feet {feet}")


# ------------------------------------------------------------------------------------------------ the performance (skill ticks)
# Reference (Svaaq9F7Ty8 11.633-14.233): cut 1 snaps at t6 (high tilted ring, waist streak), follow-through t7-16 twisted away and
# leaning, turned-away low rest t19-27; cut 2: deep crouch t28-29 (head down, blade down behind the back), low sweep round the feet
# t30 (flash), chest-height thrust t31-38, drop t39, low-back rest t41+ (= the coil); hand-back t52.
COIL = Spec("coil", yaw=-35, lean=24, drop=0.2, frame="w",
            feet={"l": (0.45, 0.14), "r": (-0.42, -0.36)},
            grip=(-0.02, -0.34, 0.56), blade=(-1.5, -0.5, -0.52), face=(0.0, -0.55, 0.85), look=(3.0, 0.0, 0.25))
SWEEP1 = Spec("sweep1", yaw=45, lean=20, drop=0.17, frame="w",
              feet={"l": (0.45, 0.14), "r": (-0.42, -0.36)},
              grip=(0.55, 0.05, 0.98), blade=(0.72, 0.66, -0.12), face=(0.15, -0.1, 1.0), look=(3.5, 0.3, 0.6))
FOLLOW1 = Spec("follow1", yaw=70, lean=34, drop=0.25, roll=-8,
               feet={"l": (0.46, 0.14), "r": (-0.44, -0.26)},
               grip=(0.3, 0.38, 1.12), blade=None, face=None, look=None)
OVER1 = Spec("over1", yaw=95, lean=22, drop=0.2,
             feet={"l": (0.44, 0.16), "r": (-0.44, -0.26)},
             grip=(0.15, 0.32, 1.35), blade=None, face=None, look=None)
REST_AWAY = Spec("rest-away", yaw=172, lean=17, drop=0.2,
                 feet={"l": (0.38, 0.18), "r": (-0.42, -0.26)},
                 grip=(0.32, -0.12, 0.6), blade=(0.92, 0.12, -0.34), face=None, look=(1.8, 0.1, 0.0))
CROUCH2 = Spec("crouch2", yaw=45, lean=36, drop=0.3,
               feet={"l": (0.5, 0.22), "r": (-0.48, -0.3)},
               grip=(0.05, -0.36, 0.72), blade=None, face=None, look=(0.7, 0.0, -0.5))
SWEEP2 = Spec("sweep2", yaw=10, lean=34, drop=0.3,
              feet={"l": (0.5, 0.2), "r": (-0.46, -0.3)},
              grip=(0.45, -0.12, 0.48), blade=None, face=None, look=None)
THRUST2 = Spec("thrust2", yaw=-20, lean=24, drop=0.22,
               feet={"l": (0.5, 0.16), "r": (-0.45, -0.24)},
               grip=(0.6, -0.02, 1.17), blade=(0.99, 0.02, 0.04), face=None, look=(4.0, 0.0, 1.0))
DROP2 = Spec("drop2", yaw=-25, lean=20, drop=0.2,
             feet={"l": (0.47, 0.15), "r": (-0.43, -0.3)},
             grip=(0.2, -0.3, 0.72), blade=(-0.9, -0.55, -0.5), face=None, look=(3.0, 0.0, 0.5))

# world-frame face / blade / look of the heading-frame poses (written here so the rest of the table stays readable)
CAM_SIDE = (0.0, -0.62, 0.78)        # world (f, l, u): up + toward the compare side camera (the caster's right at the cast)
FOLLOW1.blade, FOLLOW1.face, FOLLOW1.look = ("w", (-0.93, 0.15, 0.2)), ("w", (0.0, -0.5, 0.86)), ("w", (3.0, 0.3, 0.4))
OVER1.blade, OVER1.face, OVER1.look = ("w", (-0.95, 0.05, 0.27)), ("w", (0.0, -0.85, 0.3)), ("w", (3.0, 0.0, 0.8))
SWEEP2.blade, SWEEP2.face, SWEEP2.look = ("w", (0.6, 0.78, -0.18)), ("w", (0.0, 0.0, 1.0)), ("w", (2.5, 0.3, 0.2))
REST_AWAY.face = ("w", CAM_SIDE)
CROUCH2.blade, CROUCH2.face = ("w", (-0.35, -0.75, -0.56)), ("w", (0.5, -0.6, 0.6))
THRUST2.face = ("w", CAM_SIDE)
THRUST2.blade = ("w", (1.0, 0.0, 0.04))
DROP2.face = ("w", CAM_SIDE)


def resolve(spec: Spec) -> Spec:
    """Mixed frames: a ("w", v) field is a world point, anything else follows spec.frame."""
    s = Spec(**{k: v for k, v in spec.__dict__.items()})
    out = {}
    for k in ("blade", "face", "look"):
        v = getattr(s, k)
        if isinstance(v, tuple) and len(v) == 2 and v[0] == "w":
            out[k] = to_world(v[1], s.yaw, "w")
        else:
            out[k] = to_world(v, s.yaw, s.frame)
    # re-express everything in the world and mark the spec world-framed for build()
    w = lambda p: (p[2], p[0], p[1])  # world vec -> (f, l, u) for to_world(.., "w")
    s.grip = w(to_world(s.grip, s.yaw, s.frame))
    s.feet = {k: w(to_world(v + (0.0,), s.yaw, s.frame)) for k, v in s.feet.items()}
    s.blade, s.face, s.look = w(out["blade"]), w(out["face"]), w(out["look"])
    s.frame = "w"
    return s


TIMELINE = [  # (skill tick, pose, ease)  -- every key after the first is a cut (ease step) unless it says otherwise
    (0, COIL, None),
    (6, SWEEP1, "step"),
    (7, FOLLOW1, "step"),
    (16, OVER1, "step"),
    (19, REST_AWAY, "step"),
    (28, CROUCH2, "step"),
    (30, SWEEP2, "step"),
    (31, THRUST2, "step"),
    (39, DROP2, "step"),
    (41, COIL, "step"),
    (52, None, "step"),  # hand-back: the vanilla held pose (all zero)
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
    print("poses:")
    for st, spec, ease in TIMELINE:
        if spec is None:
            poses.append((st, rig_tick(st), RM.Pose(), ease, "hand-back"))
            continue
        s = resolve(spec)
        s.feet = {k: v for k, v in s.feet.items()}
        p = build(s)
        report(s, p)
        poses.append((st, rig_tick(st), p, ease, spec.name))
    # body yaml
    L = []
    L.append("# bastion-s1-body: HAND-AUTHORED 3D performance, written by tools/pose-solve/author_bastion_s1.py (edit the pose table there and rerun).")
    L.append("# Pose to pose (ADR-0276): every key after the first is a CUT (`ease: step`). Keys are RIG ticks; the hit-stop freezes (7/31) shift them to skill ticks.")
    L.append("# beats (skill tick -> rig tick): " + ", ".join(f"{n} {st}->{rt}" for st, rt, _, _, n in poses))
    L += ["id: bastion-s1-body", f"length_ticks: {DURATION}", "loop: false", "lock_movement: false", "blend_in_ticks: 0", "blend_out_ticks: 0", "bones:"]

    def key(rt, v, ease):
        return f"      - {{ t: {rt}, v: {r1(v)}" + (f", ease: {ease} }}" if ease else " }")
    for bone in ("head", "body", "right_arm", "left_arm", "right_leg", "left_leg"):
        L.append(f"  {bone}:")
        L.append("    rotation:")
        for st, rt, p, ease, _ in poses:
            L.append(key(rt, p.bones[bone]["rotation"], ease))
        if bone.endswith("leg"):
            L.append("    scale:")
            for st, rt, p, ease, _ in poses:
                L.append(f"      - {{ t: {rt}, v: [1, {round(float(p.bones[bone]['scale'][1]), 3)}, 1]" + (f", ease: {ease} }}" if ease else " }"))
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
        sw.append(f"  - {{ t: {rt}, rotate: {r1(p.wrist)}" + (f", ease: {ease} }}" if ease else " }") + f"   # skill {st} {name}")
    txt = re.sub(r"  swing:\n(  - .*\n)+", "\n".join(sw) + "\n", txt)
    rig.write_text(txt)
    print("wrote", "content/animations/bastion-s1-body.yaml", "and the swing keys of", rig.relative_to(ROOT))


if __name__ == "__main__":
    main()
