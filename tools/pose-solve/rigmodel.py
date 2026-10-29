"""Forward kinematics of the skin rig + the main-hand weapon, a numpy port of the runtime math (ADR-0250 / ADR-0276).

Sources that this file mirrors (change both together):
  SkinRigBone.rest / .parent / SkinRigTransforms.compute / rightArm / hips / spine   kernel/domain/.../animation/skinrig/
  VfxBodyTrack, VfxRigMotion (originAt, frameRotationAt, pointAt)  plugin/core/.../domain/effect/vfx/
  VfxPerformanceSampler (the plan.json frame: +x right, +y up, +z forward, mirrored from the rig frame)

Two frames are in play:
  rig frame   +Y up, +Z front, the character's RIGHT hand at -X (what the YAML keys and the planner use).
  plan frame  caster-local, +x right (= the rig frame mirrored in x), what plan.json and the lint use.
Everything public here that returns positions returns the PLAN frame unless it says `rig`.

Euler conventions (they differ, this is the usual trap):
  body / bone / root rotation  R = Rz * Ry * Rx           (degrees, `[x, y, z]`)
  wrist (rig `swing`)          R = Ry(-a) * Rx(b) * Rz(c) (`fromEulerDegrees(a, b, c)`, `rotate: [a, b, c]`)
"""
from __future__ import annotations

import numpy as np
from scipy.spatial.transform import Rotation as Rot

PX = 1.0 / 16.0
PLAYER_SCALE = 0.9375
ROOT_PIVOT = np.array([0.0, 12.0, 0.0])
HELD_GRIP_PITCH = 72.0
HELD_GRIP_OFFSET = Rot.from_euler("x", HELD_GRIP_PITCH, degrees=True).apply(np.array([-1.0, 2.0, 10.0])) * PX * PLAYER_SCALE

# SkinRigBone.rest(slim = False): center, size, pivot (px, rig frame).
BONES = {
    "head": dict(center=(0, 28, 0), size=(8, 8, 8), pivot=(0, 24, 0)),
    "body": dict(center=(0, 18, 0), size=(8, 12, 4), pivot=(0, 12, 0)),  # spine: pivots at the waist
    "right_arm": dict(center=(-6, 18, 0), size=(4, 12, 4), pivot=(-5, 22, 0)),
    "left_arm": dict(center=(6, 18, 0), size=(4, 12, 4), pivot=(5, 22, 0)),
    "right_leg": dict(center=(-1.9, 6, 0), size=(4, 12, 4), pivot=(-1.9, 12, 0)),
    "left_leg": dict(center=(1.9, 6, 0), size=(4, 12, 4), pivot=(1.9, 12, 0)),
}
BONE_NAMES = list(BONES)
# SkinRigBone.parent: head and arms hang from the spine (`body`), body and legs from root.
PARENT = {"head": "body", "right_arm": "body", "left_arm": "body", "body": None, "right_leg": None, "left_leg": None}
ARM_LEN_PX = 12.0   # box height; the hand end of an arm is pivot + R * (0, -(12 - 2), 0) for the classic arm (box top is 2 px above the joint)

# weapon geometry of the shipped greatsword rig (content/vfx/bastion-s1-rig.yaml; rig-local, after the grip pitch)
WEAPON_TIP_LOCAL = np.array([0.0, 1.3789, 1.0769])
WEAPON_EDGE_LOCAL = np.array([0.0, -0.6155, 0.7881])
WEAPON_PIVOT = np.zeros(3)
POMMEL_BACK = 0.15  # blocks behind the grip along the blade axis: where the pommel sits (annotation landmark only)

MIRROR = np.diag([-1.0, 1.0, 1.0])


def euler_body(v) -> Rot:
    """Rz * Ry * Rx, degrees `[x, y, z]`."""
    return Rot.from_euler("ZYX", [v[2], v[1], v[0]], degrees=True)


def body_euler_of(R: Rot) -> list[float]:
    z, y, x = R.as_euler("ZYX", degrees=True)
    return [float(x), float(y), float(z)]


def euler_wrist(v) -> Rot:
    """Ry(-a) * Rx(b) * Rz(c), degrees `[a, b, c]` (the planner's Quaternion.fromEulerDegrees)."""
    return Rot.from_euler("y", -v[0], degrees=True) * Rot.from_euler("x", v[1], degrees=True) * Rot.from_euler("z", v[2], degrees=True)


def wrist_euler_of(R: Rot) -> list[float]:
    """Inverse of euler_wrist: R = Ry(-a) Rx(b) Rz(c)  =>  intrinsic 'YXZ' angles (-a, b, c)."""
    wy, wx, wz = R.as_euler("YXZ", degrees=True)
    return [float(-wy), float(wx), float(wz)]


def mir(v):
    return MIRROR @ np.asarray(v, float)


def mir_rot(R: Rot) -> Rot:
    """Conjugate a rotation by the x mirror: (x, y, z, w) -> (x, -y, -z, w)."""
    x, y, z, w = R.as_quat()
    return Rot.from_quat([x, -y, -z, w])


class Pose:
    """One frame of body keys: `bones[name] = {rotation, position, scale}` + `root = {rotation, position}` (px / degrees)."""

    def __init__(self, root_rot=(0, 0, 0), root_pos=(0, 0, 0), bones=None, wrist=(0, 0, 0), two_hand_grip=False):
        self.two_hand_grip = bool(two_hand_grip)
        self.root_rot = np.array(root_rot, float)
        self.root_pos = np.array(root_pos, float)
        self.bones = {n: dict(rotation=np.zeros(3), position=np.zeros(3), scale=np.ones(3)) for n in BONE_NAMES}
        for n, b in (bones or {}).items():
            for k, v in b.items():
                self.bones[n][k] = np.array(v, float)
        self.wrist = np.array(wrist, float)

    def copy(self) -> "Pose":
        return Pose(self.root_rot, self.root_pos, {n: {k: v.copy() for k, v in b.items()} for n, b in self.bones.items()}, self.wrist, self.two_hand_grip)


class _Xf:
    """Rigid `p -> R p + t` in rig px (SkinRigTransforms.Xf); `a * b` applies b first."""

    def __init__(self, R: Rot, t):
        self.R, self.t = R, np.asarray(t, float)

    def apply(self, p):
        return self.R.apply(np.asarray(p, float)) + self.t

    def __mul__(self, o: "_Xf") -> "_Xf":
        return _Xf(self.R * o.R, self.apply(o.t))


def _about(pivot, R: Rot, offset) -> _Xf:
    pivot = np.asarray(pivot, float)
    return _Xf(R, pivot + np.asarray(offset, float) - R.apply(pivot))


def _local(pose: Pose, n: str, R: Rot) -> _Xf:
    return _about(BONES[n]["pivot"], R, pose.bones[n]["position"])


def _parent_xf(pose: Pose, n: str) -> _Xf:
    p = PARENT[n]
    if p is None:
        return _about(ROOT_PIVOT, euler_body(pose.root_rot), pose.root_pos)
    return _parent_xf(pose, p) * _local(pose, p, euler_body(pose.bones[p]["rotation"]))


def _local_rot(pose: Pose, n: str) -> Rot:
    """The bone's local rotation; with `two_hand_grip` the left arm aims its -Y at the right-hand grip (SkinRigTransforms.leftArmRot)."""
    R = euler_body(pose.bones[n]["rotation"])
    if n != "left_arm" or not pose.two_hand_grip:
        return R
    arm = _parent_xf(pose, "right_arm") * _local(pose, "right_arm", euler_body(pose.bones["right_arm"]["rotation"]))
    grip = arm.apply(HELD_GRIP_OFFSET / (PX * PLAYER_SCALE) + np.array(BONES["right_arm"]["pivot"], float))
    par = _parent_xf(pose, "left_arm")
    joint = par.apply(np.array(BONES["left_arm"]["pivot"], float) + pose.bones["left_arm"]["position"])
    d = grip - joint
    if np.linalg.norm(d) < 1e-6:
        return R
    d = par.R.inv().apply(d / np.linalg.norm(d))
    a = np.array([0.0, -1.0, 0.0])
    axis = np.cross(a, d)
    s_, c_ = np.linalg.norm(axis), float(np.dot(a, d))
    if s_ < 1e-9:
        return Rot.identity() if c_ > 0 else Rot.from_rotvec([np.pi, 0.0, 0.0])
    return Rot.from_rotvec(axis / s_ * np.arctan2(s_, c_))


def _chain(pose: Pose, n: str) -> _Xf:
    return _parent_xf(pose, n) * _local(pose, n, _local_rot(pose, n))


def fk(pose: Pose) -> dict:
    """All landmarks of one pose in the RIG frame, blocks, PLAYER_SCALE applied (feet at y = 0, x = z = 0).

    Two-level hierarchy (SkinRigBone.parent): root -> body (waist pivot) -> head / arms; root -> legs.
    keys: bone centres + rotations (`<name>`), `hips`, `spine`, `joint_<r|l>`, `hand_<r|l>` (arm end), `elbow_<r|l>`, `knee_<r|l>`,
    `ankle_<r|l>`, `grip` (weapon origin = right-hand grip), `arm_R_right` (the right-arm frame), `frame_R` (rig frame of the weapon
    without the wrist, i.e. the arm frame), `neck`, `head_top`.
    """
    rootR = euler_body(pose.root_rot)
    k = PX * PLAYER_SCALE
    out: dict = {}
    chains = {n: _chain(pose, n) for n in BONES}
    for n, rest in BONES.items():
        b = pose.bones[n]
        piv = np.array(rest["pivot"], float)
        out[n] = chains[n].apply(piv + (np.array(rest["center"], float) - piv) * b["scale"]) * k
        out[n + "_R"] = chains[n].R
    out["hips"] = (ROOT_PIVOT + pose.root_pos) * k
    out["hips_R"] = rootR
    out["spine"] = _parent_xf(pose, "body").apply(np.array(BONES["body"]["pivot"], float) + pose.bones["body"]["position"]) * k
    out["spine_R"] = chains["body"].R

    def joint(n):
        return chains[n].apply(np.array(BONES[n]["pivot"], float)) * k

    def end(n, down_px):
        """A point `down_px` below the pivot along the bone's own -Y (arm hand / leg foot), scaled by the bone scale."""
        b = pose.bones[n]
        return chains[n].apply(np.array(BONES[n]["pivot"], float) + np.array([0.0, -down_px, 0.0]) * b["scale"][1]) * k

    for s, side in (("r", "right"), ("l", "left")):
        a, lg = side + "_arm", side + "_leg"
        out["joint_" + s] = joint(a)
        out["hand_" + s] = end(a, 10.0)        # box spans 2 px above the joint to 10 px below it
        out["elbow_" + s] = end(a, 5.0)
        out["hip_" + s] = joint(lg)
        out["ankle_" + s] = end(lg, 12.0)
        out["knee_" + s] = end(lg, 6.0)
    armR = chains["right_arm"].R
    out["frame_R"] = armR
    out["grip"] = out["joint_r"] + armR.apply(HELD_GRIP_OFFSET + WEAPON_PIVOT)
    out["neck"] = chains["body"].apply(np.array([0.0, 24.0, 0.0])) * k
    out["head_top"] = chains["head"].apply(np.array([0.0, 32.0, 0.0])) * k
    return out


def weapon(pose: Pose, lm: dict | None = None) -> dict:
    """Weapon landmarks in the rig frame: grip, tip, edge direction, face normal; `rotation` = the weapon frame (arm x wrist)."""
    lm = lm or fk(pose)
    frame = lm["frame_R"] * euler_wrist(pose.wrist)
    tip = lm["grip"] + frame.apply(WEAPON_TIP_LOCAL)
    edge = frame.apply(WEAPON_EDGE_LOCAL)
    axis = tip - lm["grip"]
    axis = axis / np.linalg.norm(axis)
    n = np.cross(axis, edge)
    n = n / np.linalg.norm(n)
    return dict(grip=lm["grip"], tip=tip, edge=edge, axis=axis, normal=n, frame=frame, pommel=lm["grip"] - axis * POMMEL_BACK)


def to_plan_frame(lm: dict, wp: dict | None = None) -> dict:
    """Mirror a rig-frame landmark dict to the plan frame (positions only; rotations are converted separately)."""
    out = {}
    for k, v in lm.items():
        if isinstance(v, np.ndarray):
            out[k] = mir(v)
    if wp:
        for k in ("grip", "tip", "edge", "axis", "normal", "pommel"):
            out["w_" + k] = mir(wp[k])
    return out


# ---- sampling a body animation + rig the way VfxBodyTrack / VfxRigMotion do ---------------------------------------------------
def _sample(keys, t, ease_key="ease"):
    """keys: [{t, v, ease?}] sorted. Piecewise: `step` holds the previous value until the key tick; anything else lerps."""
    if t <= keys[0]["t"]:
        return np.array(keys[0]["v"], float)
    if t >= keys[-1]["t"]:
        return np.array(keys[-1]["v"], float)
    i = 0
    while keys[i + 1]["t"] <= t:
        i += 1
    a, b = keys[i], keys[i + 1]
    if b.get(ease_key, "linear") == "step":
        return np.array(a["v"], float)
    u = (t - a["t"]) / (b["t"] - a["t"])
    return np.array(a["v"], float) + (np.array(b["v"], float) - np.array(a["v"], float)) * u


def pose_from_yaml(body_doc: dict, rig_doc: dict | None, rig_tick: float) -> Pose:
    p = Pose(two_hand_grip=body_doc.get("two_hand_grip", False))
    root = body_doc.get("root") or {}
    if "rotation" in root:
        p.root_rot = _sample(root["rotation"], rig_tick)
    if "position" in root:
        p.root_pos = _sample(root["position"], rig_tick)
    for n, ch in (body_doc.get("bones") or {}).items():
        for k in ("rotation", "position", "scale"):
            if k in ch:
                p.bones[n][k] = _sample(ch[k], rig_tick)
    if rig_doc is not None:
        swing = rig_doc["rigs"][0].get("swing") or []
        if swing:
            p.wrist = _sample([{"t": s["t"], "v": s["rotate"], "ease": s.get("ease", "linear")} for s in swing], rig_tick)
    return p


def rig_tick_of_skill_tick(e: int, base: int, stops: list) -> int:
    """Largest rig tick l whose state has been reached at effect tick e (VfxPerformanceSampler's `while` loop)."""
    def eff_end(l):
        return base + l + sum(d for (at, d) in stops if at < l)
    l = 0
    while eff_end(l + 1) <= e:
        l += 1
    return l


def effect_tick_of_rig_tick(l: int, base: int, stops: list) -> int:
    return base + l + sum(d for (at, d) in stops if at < l)


def stops_from_freezes(freezes: list[tuple[int, int]], base: int = 1) -> list[tuple[int, int]]:
    """VfxRigMotion `stops`: (rig tick the clock stops at, frozen ticks). `freezes` = [(at, ticks)] in effect ticks (merged windows)."""
    out, acc = [], 0
    for at, ticks in sorted(freezes):
        a = at - base - acc
        if a >= 0:
            out.append((a, ticks))
            acc += ticks
    return out


def simulate_plan(body_doc: dict, rig_doc: dict, freezes: list[tuple[int, int]], duration: int, base: int = 1) -> dict:
    """What the removed `make skill-lint-plan` would have written for these two documents, without a JVM (Python port of VfxPerformanceSampler)."""
    stops = stops_from_freezes(freezes, base)
    last = duration - base
    ticks = []
    for e in range(duration + 1):
        l = min(rig_tick_of_skill_tick(e, base, stops), last)
        pose = pose_from_yaml(body_doc, rig_doc, l)
        ticks.append(plan_tick(e, pose))
    return {"skill": body_doc.get("id", "?"), "ticks": ticks, "simulated": True}


def _q(R: Rot):
    x, y, z, w = R.as_quat()
    return [round(float(x), 6), round(float(y), 6), round(float(z), 6), round(float(w), 6)]


def plan_tick(tick: int, pose: Pose) -> dict:
    lm = fk(pose)
    wp = weapon(pose, lm)
    bones = {}
    for n in BONE_NAMES:
        bones[n] = {"pos": [round(float(x), 6) for x in mir(lm[n])], "rot": _q(mir_rot(lm[n + "_R"]))}
    bones["hips"] = {"pos": [round(float(x), 6) for x in mir(lm["hips"])], "rot": _q(mir_rot(lm["hips_R"]))}
    arm_R = lm["frame_R"]
    hand = lm["joint_r"] + arm_R.apply(HELD_GRIP_OFFSET)
    bones["right_hand"] = {"pos": [round(float(x), 6) for x in mir(hand)], "rot": _q(mir_rot(arm_R))}
    return {
        "tick": tick, "bones": bones,
        "weapon": {"pos": [round(float(x), 6) for x in mir(wp["grip"])], "rot": _q(mir_rot(wp["frame"] * Rot.from_euler("x", HELD_GRIP_PITCH, degrees=True))),
                   "tip": [round(float(x), 6) for x in mir(wp["tip"])], "edgeNormal": [round(float(x), 6) for x in mir(wp["edge"])]},
        "visibleWeapons": 1,
    }
