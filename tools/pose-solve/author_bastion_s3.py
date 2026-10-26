#!/usr/bin/env python3
"""Hand-authored 3D performance of bastion-greatsword-s3 (巨剣墜つ): body keys + wrist keys.

    python3 tools/pose-solve/author_bastion_s3.py

Same machinery as author_bastion_s2.py (S1 pose build / Beat / grip pins / rigmodel FK). S3 = ring 1 (back-low -> front -> right ->
back-up over the shoulder, top view CW, contact t10), a long low hold with the blade scraping the floor, a dash-thrust (contact t40),
a one-tick raise to the overhead pose (S2 `raise-hold` manner) and the S2 vertical chop as the slam (contact t63), then kneeling beside
the upright planted sword to t95.

Reference (Svaaq9F7Ty8 18.9-23.7, beat sheet r2): see tools/skill-compare/data/bastion-greatsword-s3/beat-sheet/beat-sheet.md.
Writes content/animations/bastion-s3-body.yaml and content/vfx/bastion-s3-rig.yaml.
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import author_bastion_s1 as A  # noqa: E402
import author_bastion_s2 as S2  # noqa: E402  (importing it sets A.FREEZES / A.DURATION to the S2 values: overridden below)
import rigmodel as RM  # noqa: E402

ROOT = A.ROOT
FREEZES = [(10, 2), (40, 2), (63, 3)]   # contacts: hit_stop ticks of the skill (the rig freezes with the caster)
DURATION = 96
A.FREEZES = FREEZES
A.DURATION = DURATION
S2.FREEZES = FREEZES
S2.DURATION = DURATION

B = S2.B
ENEMY = A.ENEMY
CAMV = np.array([-3.0, 1.3, 0.6])      # world (x_left, y, z_fwd): the compare camera is the caster's RIGHT = -x
BEHIND = np.array([3.0, 1.2, 1.0])     # while the back is turned: she looks away over her left shoulder


def beats():
    L = []
    # -- coil t0-9: crouched, blade flat low behind (tip near the floor, a little toward the camera side), turning away
    L.append(B(0, 0, 275, 0.05, "cam", "s3-coil", lean=26, drop=0.3, grip_y=0.92, look=ENEMY))
    L.append(B(5, 15, 275, 0.05, "cam", "s3-coil-b", lean=28, drop=0.32, grip_y=0.92, look=ENEMY))
    L.append(B(7, 30, 275, 0.05, "cam", "s3-coil-c", lean=30, drop=0.34, grip_y=0.92, look=BEHIND))
    # ring 1 travel, top view CW (th falls): back-low -> left-low (t8) -> front-right (t9) -> back-up over the shoulder (t10 contact)
    L.append(B(8, 36, 165, 0.1, "cam", "s3-ring-left", lean=30, drop=0.35, grip_y=0.92, look=BEHIND))
    L.append(B(9, 40, 35, 0.35, "cam", "s3-ring-front", lean=28, drop=0.35, grip_y=0.95, look=BEHIND))
    # -- ring 1 contact t10: blade already up-back over the shoulder, the body has turned CW to the camera
    L.append(B(10, -70, 285, 2.6, ("cw", "cam", 0.5), "ring1-hit", lean=12, drop=0.1, grip_y=1.32, reach=0.4, look=CAMV))
    # -- shoulder hold t13-22 (rig 10-19), body unwinds slowly toward the enemy
    L.append(B(13, -66, 285, 2.55, "cam", "hold-a", lean=11, drop=0.12, grip_y=1.3, reach=0.4, look=CAMV))
    L.append(B(17, -50, 285, 2.5, "cam", "hold-b", lean=12, drop=0.14, grip_y=1.28, reach=0.4, look=ENEMY))
    L.append(B(22, -30, 280, 2.45, "cam", "hold-c", lean=14, drop=0.16, grip_y=1.25, reach=0.42, look=ENEMY))
    # -- lower t22-24: blade down to the floor behind, yaw -> 0, deep crouch
    L.append(B(23, -14, 277, 1.0, "cam", "lower", lean=22, drop=0.3, grip_y=1.0, look=ENEMY))
    L.append(B(24, 0, 275, 0.05, "cam", "low", lean=28, drop=0.45, grip_y=0.95, look=ENEMY))
    # -- low hold t24-37: almost still; scrape drag (the blade tip slides a little, the body breathes)
    L.append(B(30, 0, 277, 0.05, "cam", "low-drag", lean=29, drop=0.47, grip_y=0.95, look=ENEMY))
    L.append(B(33, 0, 274, 0.05, "cam", "low-drag2", lean=28, drop=0.46, grip_y=0.95, look=ENEMY))
    # -- dash t37-40: the wind-up sinks, the blade rips up from back-low through the front (screen CCW), thrust-hold at t40
    L.append(B(36, 0, 272, 0.05, "cam", "dash-wind", lean=33, drop=0.5, grip_y=0.95, look=ENEMY))
    L.append(B(37, 0, 340, 0.1, "cam", "dash-sweep-a", lean=30, drop=0.45, grip_y=0.95, reach=0.5, look=ENEMY))
    L.append(B(38, 0, 40, 0.2, "cam", "dash-sweep", lean=26, drop=0.4, grip_y=0.95, reach=0.55, look=ENEMY))
    L.append(B(39, 0, 90, 1.0, "cam", "dash-rise", lean=22, drop=0.34, grip_y=1.35, reach=0.65, look=ENEMY))
    L.append(B(40, 0, 90, 1.4, "cam", "thrust", lean=22, drop=0.32, grip_y=1.55, reach=0.7, look=ENEMY))
    L.append(B(44, 0, 90, 1.42, "cam", "thrust-b", lean=23, drop=0.33, grip_y=1.56, reach=0.7, look=ENEMY))
    L.append(B(46, 0, 90, 1.4, "cam", "thrust-hold", lean=24, drop=0.34, grip_y=1.55, reach=0.7, look=ENEMY))
    L.append(B(47, -8, 90, 1.6, "cam", "raise-a", lean=20, drop=0.3, grip_y=1.6, reach=0.62, look=ENEMY))
    L.append(B(48, -40, 90, 2.8, "cam", "raise-b", lean=14, drop=0.15, grip_y=1.85, reach=0.45, look=CAMV))
    L.append(B(49, -65, 180, 2.9, "cam", "raise-c", lean=10, drop=0.1, grip_y=1.95, reach=0.35, look=CAMV))
    L.append(B(50, -90, 270, 2.4, "cam", "raise-d", lean=6, drop=0.05, grip_y=1.95, reach=0.3, look=CAMV))
    L.append(B(51, -90, 270, 1.15, "cam", "raise-hold", lean=4, drop=0.0, grip_y=2.05, reach=0.25, look=CAMV))
    L.append(B(54, -90, 270, 1.2, "cam", "raise-breath", lean=2, drop=0.0, grip_y=2.08, reach=0.25, look=CAMV))
    L.append(B(57, -90, 270, 1.15, "cam", "raise-hold2", lean=5, drop=0.02, grip_y=2.05, reach=0.25, look=CAMV))
    L.append(B(58, -88, 270, 2.1, "side", "chop-lift", lean=4, drop=0.06, grip_y=1.75, reach=0.32, look=CAMV))
    # -- slam t60-63 = S2 chop (anticipation lift, coil, top, contact), plant, kneel with the sword upright
    L.append(B(60, -84, 270, 3.0, "side", "chop-wind", lean=4, drop=0.1, grip_y=1.45, reach=0.4, look=CAMV))
    L.append(B(61, -76, 310, 3.3, "side", "chop-wind2", lean=2, drop=0.1, grip_y=1.5, reach=0.4, look=CAMV))
    L.append(B(62, -40, 90, 3.4, "side", "chop-top", lean=12, drop=0.1, grip_y=1.6, reach=0.45))
    L.append(B(63, 0, 90, 0.5, "side", "chop-hit", lean=30, drop=0.3, grip_y=1.05, reach=0.7))
    L.append(B(67, 0, 90, 0.1, "side", "chop-plant", lean=30, drop=0.45, grip_y=1.2, reach=0.62))
    L.append(B(68, 0, 90, -0.05, "side", "plant-b", lean=29, drop=0.46, grip_y=1.3, reach=0.62))
    L.append(B(69, 0, 90, -0.15, "side", "plant-c", lean=28, drop=0.48, grip_y=1.45, reach=0.62))
    L.append(B(70, 0, 90, -0.3, "side", "stand-a", lean=26, drop=0.5, grip_y=1.6, reach=0.62))
    L.append(B(72, 0, 90, -0.4, "side", "stand-a2", lean=24, drop=0.52, grip_y=1.7, reach=0.62))
    L.append(B(73, 0, 90, -0.45, "side", "stand-b", lean=22, drop=0.54, grip_y=1.71, reach=0.62))
    L.append(B(74, 0, 90, -0.5, "side", "stand-c", lean=21, drop=0.56, grip_y=1.72, reach=0.62))
    L.append(B(75, 0, 90, -0.55, "side", "stand-d", lean=19, drop=0.58, grip_y=1.74, reach=0.62))
    L.append(B(76, 0, 90, -0.6, "side", "kneel", lean=18, drop=0.6, grip_y=1.75, reach=0.62))
    L.append(B(94, 0, 90, -0.6, "side", "kneel-hold", lean=20, drop=0.6, grip_y=1.72, reach=0.62))
    for b in L:
        if b.tick <= 9 or 22 <= b.tick <= 37:
            b.off = -80.0
    return L


def spec3(b):
    """S2 spec, but the right-hand grip may be forced to the caster's right side (off = degrees from the chest facing): a blade straight
    behind the chest is ambiguous (+-180) and the grip would flip sides between two keys."""
    s = S2.spec_of(b)
    off = getattr(b, "off", None)
    if off is not None:
        s.grip = A.W(A.az(90.0 + b.yaw + off) * b.reach + np.array([0.0, b.grip_y - b.drop, 0.0]))
    return s


def th_of(wp):
    return math.degrees(math.atan2(wp["tip"][2], -wp["tip"][0]))


def lerp_pose(p, q, f):
    r = p.copy()
    r.root_rot = np.array(p.root_rot, float) * (1 - f) + np.array(q.root_rot, float) * f
    r.root_pos = np.array(p.root_pos, float) * (1 - f) + np.array(q.root_pos, float) * f
    r.wrist = np.array(p.wrist, float) * (1 - f) + np.array(q.wrist, float) * f
    for n in r.bones:
        for k in r.bones[n]:
            r.bones[n][k] = np.array(p.bones[n][k], float) * (1 - f) + np.array(q.bones[n][k], float) * f
    return r


def poses_rt(b):
    return A.rig_tick(b.tick)


def main():
    BEATS = beats()
    poses = []
    prev = None
    pw = None
    print("poses (th = tip azimuth from above, + = CCW):")
    for b in BEATS:
        s = spec3(b)
        A.FLOOR = -2.0 if b.name.startswith(("stand", "kneel")) and b.tick >= 72 else 0.03   # the upright planted sword sinks into the floor
        p = A.build(s, prev)
        A.report(s, p)
        wp = RM.weapon(p)
        th = th_of(wp)
        if pw is not None:
            dth = (th - th_of(pw) + 180) % 360 - 180
            print(f"      t{b.tick:<3} th {th:7.1f}  d {dth:+6.1f} {'CCW' if dth > 1 else 'CW' if dth < -1 else '-':<3} tip {np.round(wp['tip'], 2).tolist()}")
            ang = (RM.weapon(prev)["frame"].inv() * wp["frame"]).magnitude()
            dw = math.degrees(ang) / max(1, poses_rt(b) - poses[-1][1])
            dr = np.abs(np.array(p.root_rot) - np.array(prev.root_rot)).max()
            db = np.abs(np.array(p.bones['body']['rotation']) - np.array(prev.bones['body']['rotation'])).max()
            n = max(1, poses_rt(b) - poses[-1][1])
            wq = math.degrees((RM.euler_wrist(prev.wrist).inv() * RM.euler_wrist(p.wrist)).magnitude()) / n
            we = float(np.abs(np.array(p.wrist) - np.array(prev.wrist)).max()) / n
            if max(wq, we) > 150:
                print(f"      !!!! WRIST over 150 into t{b.tick}: quat {wq:.0f} euler {we:.0f} per rig tick")
            if dw > 60 or dr > 60 or db > 60:
                print(f"      !! big chunk into t{b.tick}: weapon world {dw:.0f} deg/rig tick; wrist-euler-max {np.abs(np.array(p.wrist)-np.array(prev.wrist)).max():.0f} root {dr:.0f} spine {db:.0f}")
            if b.tick - poses[-1][0] <= 2 and b.name in ("ring1-hit", "dash-sweep", "dash-rise", "thrust", "raise-hold", "chop-top", "chop-hit", "s3-ring-front", "raise-a", "raise-b"):
                mids = []
                for f in (0.25, 0.5, 0.75):
                    mw = RM.weapon(lerp_pose(prev, p, f))
                    mids.append(f"{th_of(mw):6.1f}/y{mw['tip'][1]:.2f}")
                print(f"      lerp mid th/tipy: {mids}")
        prev, pw = p, wp
        poses.append((b.tick, A.rig_tick(b.tick), p, None if not poses else "linear", s.name))
    A.FLOOR = 0.03
    poses.append((95, A.rig_tick(95), RM.Pose(), "step", "hand-back"))
    r1 = A.r1

    def key(rt, v, ease):
        return f"      - {{ t: {rt}, v: {r1(v)}" + (f", ease: {ease} }}" if ease and ease != "linear" else " }")
    L = ["# bastion-s3-body: HAND-AUTHORED 3D performance, written by tools/pose-solve/author_bastion_s3.py (edit the pose table there and rerun).",
         "# Ring 1 (top CW), low scrape hold, dash-thrust, overhead raise, vertical slam (the S2 chop), kneel beside the upright planted sword.",
         "# Keys are RIG ticks; the hit-stop freezes (10 / 40 / 63) shift them to skill ticks.",
         "# beats (skill tick -> rig tick): " + ", ".join(f"{n} {st}->{rt}" for st, rt, _, _, n in poses),
         "id: bastion-s3-body", f"length_ticks: {DURATION}", "loop: false", "lock_movement: false", "blend_in_ticks: 0", "blend_out_ticks: 0", "bones:"]
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
    (ROOT / "content/animations/bastion-s3-body.yaml").write_text("\n".join(L) + "\n")

    rig = (ROOT / "content/vfx/bastion-s1-rig.yaml").read_text().split("  swing:\n")[0]
    head = [ln for ln in rig.splitlines() if not ln.startswith("#")]
    out = ["# Bastion S3 (巨剣墜つ) weapon: ONE ItemDisplay of the real main-hand stack hanging from the right hand of the skin-rig body `bastion-s3-body`.",
           "# Swing keys are ABSOLUTE wrist rotations in the arm frame (zero on a body at rest = the vanilla held item), written by tools/pose-solve/author_bastion_s3.py.",
           "# Rig ticks = skill ticks minus the hit-stop freezes (10 x2, 40 x2, 63 x3); the rig plays at skill tick 0 with `freeze` (see the skill)."]
    for ln in head:
        if ln.startswith("id:"):
            ln = "id: bastion-s3-rig"
        elif ln.startswith("duration_ticks:"):
            ln = f"duration_ticks: {DURATION}"
        elif ln.startswith("body:"):
            ln = "body: bastion-s3-body"
        out.append(ln)
        if ln.startswith("follow:"):
            out.append("follow_pitch: true")
    out.append("  swing:")
    for st, rt, p, ease, name in poses:
        out.append(f"  - {{ t: {rt}, rotate: {r1(p.wrist)}" + (f", ease: {ease} }}" if ease and ease != "linear" else " }") + f"   # skill {st} {name}")
    (ROOT / "content/vfx/bastion-s3-rig.yaml").write_text("\n".join(out) + "\n")
    print("wrote bastion-s3-body.yaml and bastion-s3-rig.yaml")


if __name__ == "__main__":
    main()
