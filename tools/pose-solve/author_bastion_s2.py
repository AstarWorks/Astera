#!/usr/bin/env python3
"""Hand-authored 3D performance of bastion-greatsword-s2 (審判の突進): body keys + wrist keys.

    python3 tools/pose-solve/author_bastion_s2.py

Reuses the S1 pose machinery (author_bastion_s1.py: build / Beat / grip pins / rigmodel FK). S2's two horizontal rings are the
S1 "cut 2" blade path (CW from above, low plane round the feet, thrust-hold, drop), re-timed so the contact lands on t13 / t32; the
S2 ring VFX (bastion-s2-fx-ring) is a copy of the S1 hit-2 effect and therefore needs exactly that path. The vertical chop is new.

Reference (Svaaq9F7Ty8 14.633-18.4, beat sheet r2): coil t0-9 (blade low behind), ring 1 t12 (contact t13), blade back low t23,
hold to t27, back-to-camera coil t28-30 (CCW body turn), ring 2 t30 (contact t32, CW again), blade back t41 and lifted overhead
(facing the compare camera), hold to t50, vertical chop t51-53 (contact t53), kneel with the blade on the floor to t75.
Writes content/animations/bastion-s2-body.yaml and content/vfx/bastion-s2-rig.yaml.
"""
from __future__ import annotations

import copy
import math
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import author_bastion_s1 as A  # noqa: E402
import rigmodel as RM  # noqa: E402

ROOT = A.ROOT
FREEZES = [(13, 2), (32, 2), (53, 3)]   # the three contacts: hit_stop ticks of the skill (the rig freezes with the caster)
DURATION = 80
A.FREEZES = FREEZES
A.DURATION = DURATION

S1 = {b.name: b for b in A.BEATS}


def cut(name, tick, **kw):
    b = copy.copy(S1[name])
    b.tick = tick
    b.__dict__.update(kw)
    return b


def spec_of(b: A.Beat) -> A.Spec:
    if b.face != "side":
        return A.spec_of(b)
    # vertical chop: the broad face lies sideways (world X), so the cutting edge leads in the forward / up swing plane
    chest = 90.0 + b.yaw
    gy = b.grip_y - b.drop
    off = max(-80.0, min(80.0, ((b.th - chest + 180.0) % 360.0) - 180.0))
    grip = A.az(chest + off) * b.reach + np.array([0.0, gy, 0.0])
    sp = max(-0.98, min(0.98, (b.tip_y - gy) / A.BLADE))
    a = A.az(b.th) * math.sqrt(1 - sp * sp) + np.array([0.0, sp, 0.0])
    look = b.look if b.look is not None else A.ENEMY
    return A.Spec(b.name, yaw=b.yaw, lean=b.lean, drop=b.drop, roll=b.roll, frame="w", feet=A.FEET, grip=A.W(grip), blade=A.W(a),
                  face=A.W(np.array([1.0, 0.0, 0.0])), look=A.W(look), arm_twist=b.twist)


def B(tick, yaw, th, tip_y, face, name, **kw):
    return A.Beat(tick, yaw, th, tip_y, face, name=name, **kw)


CAM = np.array([1.0, 0.4, 0.5])   # looks at the compare camera side (the caster's right)
BEATS = [
    cut("coil", 0),
    cut("coil-hold", 8),
    # ring 1: S1 cut 2 shifted -18 ticks (contact 13)
    cut("crouch2", 9), cut("crouch2-hold", 11), cut("sweep2", 12), cut("thrust2", 13),
    cut("thrust2-mid", 17), cut("thrust2-m2", 18), cut("thrust2-m3", 19), cut("thrust2-hold", 20),
    cut("drop2", 21), cut("drop2-b", 22), cut("coil-end", 23),
    cut("coil-end", 27),
    # ring 2: S1 cut 2 shifted +1 tick (contact 32); the body turns away (back to the camera) first, CCW, then releases CW
    cut("crouch2", 28), cut("crouch2-hold", 30), cut("sweep2", 31), cut("thrust2", 32),
    cut("thrust2-mid", 36), cut("thrust2-m2", 37), cut("thrust2-m3", 38), cut("thrust2-hold", 39),
    cut("drop2", 40),
    # overhead lift: the caster turns to the compare camera, blade over the head pointing back-up
    B(41, -85, 270, 2.6, "cam", "raise", lean=8, drop=0.1, grip_y=1.38, reach=0.4, look=CAM),
    B(42, -90, 270, 2.7, "cam", "raise-hold", lean=6, drop=0.1, grip_y=1.38, reach=0.4, look=CAM),
    B(50, -90, 270, 2.7, "cam", "raise-hold2", lean=6, drop=0.1, grip_y=1.38, reach=0.4, look=CAM),
    # vertical chop: anticipation lift, top, contact t53 (hit-stop 3), plant + kneel
    B(51, -80, 270, 3.0, "side", "chop-wind", lean=4, drop=0.1, grip_y=1.45, reach=0.4, look=CAM),
    B(52, -40, 90, 3.4, "side", "chop-top", lean=12, drop=0.1, grip_y=1.6, reach=0.45),
    B(53, 0, 90, 0.5, "side", "chop-hit", lean=30, drop=0.3, grip_y=1.05, reach=0.7),
    B(57, 0, 90, 0.1, "side", "chop-plant", lean=30, drop=0.45, grip_y=1.2, reach=0.62),
    B(66, 0, 90, 0.1, "side", "chop-kneel", lean=30, drop=0.45, grip_y=1.2, reach=0.62),
]


def main():
    poses = []
    prev = None
    print("poses (th = tip azimuth from above, + = CCW):")
    pw = None
    for b in BEATS:
        s = spec_of(b)
        p = A.build(s, prev)
        A.report(s, p)
        wp = RM.weapon(p)
        th = math.degrees(math.atan2(wp["tip"][2], -wp["tip"][0]))
        if pw is not None:
            pth = math.degrees(math.atan2(pw["tip"][2], -pw["tip"][0]))
            dth = (th - pth + 180) % 360 - 180
            print(f"      t{b.tick:<3} th {th:7.1f}  d {dth:+6.1f} {'CCW' if dth > 1 else 'CW' if dth < -1 else '-':<3} tip {np.round(wp['tip'], 2).tolist()}")
        prev, pw = p, wp
        poses.append((b.tick, A.rig_tick(b.tick), p, None if not poses else "linear", s.name))
    poses.append((75, A.rig_tick(75), RM.Pose(), "step", "hand-back"))
    r1 = A.r1

    def key(rt, v, ease):
        return f"      - {{ t: {rt}, v: {r1(v)}" + (f", ease: {ease} }}" if ease and ease != "linear" else " }")
    L = ["# bastion-s2-body: HAND-AUTHORED 3D performance, written by tools/pose-solve/author_bastion_s2.py (edit the pose table there and rerun).",
         "# Rings 1 and 2 = the S1 cut-2 blade path (CW from above) so the copied ring VFX fits; the chop is the vertical forward / up swing.",
         "# Keys are RIG ticks; the hit-stop freezes (13 / 32 / 53) shift them to skill ticks.",
         "# beats (skill tick -> rig tick): " + ", ".join(f"{n} {st}->{rt}" for st, rt, _, _, n in poses),
         "id: bastion-s2-body", f"length_ticks: {DURATION}", "loop: false", "lock_movement: false", "blend_in_ticks: 0", "blend_out_ticks: 0", "bones:"]
    for bone in ("head", "body", "right_arm", "left_arm", "right_leg", "left_leg"):
        L += [f"  {bone}:", "    rotation:"]
        for st, rt, p, ease, _ in poses:
            L.append(key(rt, p.bones[bone]["rotation"], ease))
        if bone.endswith("leg"):
            L.append("    scale:")
            for st, rt, p, ease, _ in poses:
                L.append(f"      - {{ t: {rt}, v: [1, {round(float(p.bones[bone]['scale'][1]), 3)}, 1]" + (f", ease: {ease} }}" if ease and ease != "linear" else " }"))
    L += ["root:", "  rotation:"]
    for st, rt, p, ease, _ in poses:
        L.append(key(rt, p.root_rot, ease).replace("      ", "    ", 1))
    L.append("  position:")
    for st, rt, p, ease, _ in poses:
        L.append(key(rt, p.root_pos, ease).replace("      ", "    ", 1))
    (ROOT / "content/animations/bastion-s2-body.yaml").write_text("\n".join(L) + "\n")

    rig = (ROOT / "content/vfx/bastion-s1-rig.yaml").read_text().split("  swing:\n")[0]
    head = [ln for ln in rig.splitlines() if not ln.startswith("#")]
    out = ["# Bastion S2 (審判の突進) weapon: ONE ItemDisplay of the real main-hand stack hanging from the right hand of the skin-rig body `bastion-s2-body`.",
           "# Swing keys are ABSOLUTE wrist rotations in the arm frame (zero on a body at rest = the vanilla held item), written by tools/pose-solve/author_bastion_s2.py.",
           "# Rig ticks = skill ticks minus the hit-stop freezes (13 x2, 32 x2, 53 x3); the rig plays at skill tick 0 with `freeze` (see the skill)."]
    for ln in head:
        if ln.startswith("id:"):
            ln = "id: bastion-s2-rig"
        elif ln.startswith("duration_ticks:"):
            ln = f"duration_ticks: {DURATION}"
        elif ln.startswith("body:"):
            ln = "body: bastion-s2-body"
        out.append(ln)
    out.append("  swing:")
    for st, rt, p, ease, name in poses:
        out.append(f"  - {{ t: {rt}, rotate: {r1(p.wrist)}" + (f", ease: {ease} }}" if ease and ease != "linear" else " }") + f"   # skill {st} {name}")
    (ROOT / "content/vfx/bastion-s2-rig.yaml").write_text("\n".join(out) + "\n")
    print("wrote bastion-s2-body.yaml and bastion-s2-rig.yaml")


if __name__ == "__main__":
    main()
