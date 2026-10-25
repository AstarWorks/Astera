#!/usr/bin/env python3
"""pose-solve: reference keypoints -> skin-rig body keys + wrist keys + skill-lint targets (deterministic, no client).

    python3 tools/pose-solve/solve.py <skill-id> [--out content/skill-lint/solved] [--no-write-view] [--only B3]

Reads  content/skill-lint/<skill>.keypoints.yaml   (annotated by an agent, corrected by a human on the skill-compare /annotate page)
       content/animations/<body>.yaml + content/vfx/<rig>.yaml   (the shipped keys; only used for the hand-back key, ids, header)
       tools/skill-compare/data/<case>/<intent>.json              (the compare camera)
Writes <out>/<body-id>.yaml, <out>/<rig-id>.yaml, <out>/<skill>.targets.yaml and build/pose-solve/<skill>/solve.json.
The outputs never overwrite the shipped content: the manager copies them over when the review says so.

Method per beat: the unknowns are the root (lean, yaw, roll, height), head, both arms, both legs and a small image-space fit of
the camera estimate (pan + zoom, written back to the keypoints file as `view`). Residuals are the 2D reprojections of the
annotated joints (weighted by confidence), depth-order hinges from the near/far flags, feet on the floor, the left hand on the
grip (two-handed), and weak priors. Then the blade is solved analytically: the tip pixel + the reference blade length give the
blade direction up to the depth sign (the `blade.depth` flag), the `blade.face` flag fixes the roll, and the wrist key is the
rotation that takes the arm frame to that blade frame (euler order Ry(-a) Rx(b) Rz(c), ADR-0254).
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path

import numpy as np
import yaml
from scipy.optimize import least_squares
from scipy.spatial.transform import Rotation as Rot

sys.path.insert(0, str(Path(__file__).resolve().parent))
import kp as KP  # noqa: E402
import rigmodel as RM  # noqa: E402

ROOT = KP.ROOT
PXN = 15.0           # pixels per residual unit
FOOT_Y = 0.0

# fk landmark used for each annotated point; weight multiplier (a rigid straight arm / leg has no elbow / knee: kept weak)
POINT_MAP = {
    "head_top": ("head_top", 1.0), "neck": ("neck", 1.0),
    "r_shoulder": ("joint_r", 1.0), "l_shoulder": ("joint_l", 1.0),
    "r_elbow": ("elbow_r", 0.25), "l_elbow": ("elbow_l", 0.25),
    "r_wrist": ("hand_r", 0.6), "l_wrist": ("hand_l", 0.6),
    "pelvis": ("hips", 1.0),
    "r_knee": ("knee_r", 0.25), "l_knee": ("knee_l", 0.25),
    "r_ankle": ("ankle_r", 0.8), "l_ankle": ("ankle_l", 0.8),
    "grip": ("grip", 1.0),
}


def wrap(a):
    return (a + 180.0) % 360.0 - 180.0


def unpack(x):
    p = RM.Pose(root_rot=[x[0], x[1], x[2]], root_pos=[0.0, x[3], 0.0])
    p.bones["head"]["rotation"] = np.array([x[4], x[5], 0.0])
    p.bones["right_arm"]["rotation"] = np.array(x[6:9])
    p.bones["left_arm"]["rotation"] = np.array(x[9:12])
    p.bones["right_leg"]["rotation"] = np.array([x[12], 0.0, x[13]])
    p.bones["left_leg"]["rotation"] = np.array([x[14], 0.0, x[15]])
    return p


def facing_of(pose: RM.Pose, cam: KP.Camera) -> float:
    """0 = the torso faces the camera, 90 = faces screen right, +-180 = away, -90 = screen left."""
    f = RM.mir(RM.euler_body(pose.root_rot).apply([0.0, 0.0, 1.0]))
    hips = RM.mir(RM.fk(pose)["hips"])
    toward = cam.pos - hips
    toward[1] = 0.0
    toward /= np.linalg.norm(toward) + 1e-9
    right = cam.r.copy()
    right[1] = 0.0
    right /= np.linalg.norm(right) + 1e-9
    f[1] = 0.0
    return math.degrees(math.atan2(f @ right, f @ toward))


class BeatProblem:
    def __init__(self, beat: dict, cam: KP.Camera):
        self.beat, self.cam = beat, cam
        self.pts = {n: p for n, p in beat["points"].items() if n in POINT_MAP and p.get("c", 1.0) > 0}
        self.view0 = np.array(beat.get("view") or (0, 0, 1), float)
        self.facing = beat.get("facing")

    def project(self, lm: dict, name: str, view) -> np.ndarray:
        cam = KP.Camera(self.cam.pos, self.cam.target, self.cam.fov, view)
        return cam.project(RM.mir(lm[POINT_MAP[name][0]]))[0]

    def residuals(self, x):
        pose = unpack(x[:16])
        view = (x[16], x[17], 1.0 + x[18])
        lm = RM.fk(pose)
        cam = KP.Camera(self.cam.pos, self.cam.target, self.cam.fov, view)
        res = []
        names = list(self.pts)
        P = cam.project(np.array([RM.mir(lm[POINT_MAP[n][0]]) for n in names])) if names else np.zeros((0, 3))
        pel = cam.project(RM.mir(lm["hips"]))[0]
        for n, row in zip(names, P):
            p = self.pts[n]
            w = float(p.get("c", 1.0)) * POINT_MAP[n][1]
            res += [w * (row[0] - p["xy"][0]) / PXN, w * (row[1] - p["xy"][1]) / PXN]
            d = p.get("depth", "mid")
            if d != "mid":
                dz = (row[2] - pel[2]) / 0.12   # camera depth difference in units of ~12 cm; near = smaller depth
                res.append(w * 2.0 * max(0.0, (dz + 1.0) if d == "near" else (-dz + 1.0)))
        # two-handed grip: the left hand rests on the grip
        if self.beat.get("two_handed", True):
            res += list(6.0 * (lm["hand_l"] - lm["grip"]) / 0.15)
        # feet on the floor
        ay = [lm["ankle_r"][1], lm["ankle_l"][1]]
        res.append(4.0 * min(ay) / 0.1)
        res += [3.0 * max(0.0, -a) / 0.05 for a in ay]
        if self.facing is not None:
            res.append(0.6 * wrap(facing_of(pose, cam) - self.facing) / 30.0)
        # priors (weak: they only break ties between equally good fits)
        res += [x[2] / 12.0, (x[4] + 0.5 * x[0]) / 25.0, x[5] / 45.0, x[7] / 50.0, x[10] / 45.0, x[8] / 120.0, x[11] / 120.0,
                x[13] / 30.0, x[15] / 30.0, x[3] / 4.5]
        res += [x[16] / 250.0, x[17] / 250.0, x[18] / 0.3]
        res += [0.15 * x[12] / 60.0, 0.15 * x[14] / 60.0, 0.1 * x[6] / 60.0, 0.1 * x[9] / 60.0]
        return np.array(res)


def candidate_yaws(bp: "BeatProblem"):
    """Root yaws worth starting from: those whose facing matches the annotated `facing` hint (3 best), else a coarse sweep."""
    if bp.facing is None:
        return range(-180, 180, 45)
    scan = []
    for yaw in range(-180, 180, 10):
        p = unpack(np.array([10, yaw, 0, -2] + [0.0] * 12))
        scan.append((abs(wrap(facing_of(p, bp.cam) - bp.facing)), yaw))
    return [y for _, y in sorted(scan)[:3]]


def solve_pose(bp: BeatProblem, starts_yaw=None):
    best = None
    yaws = starts_yaw if starts_yaw is not None else candidate_yaws(bp)
    lo = [-60, -400, -45, -14, -50, -120, -250, -400, -250, -250, -400, -250, -120, -45, -120, -45, -500, -500, -0.45]
    hi = [60, 400, 45, 6, 50, 120, 250, 400, 250, 250, 400, 250, 120, 45, 120, 45, 500, 500, 0.45]
    for yaw in yaws:
        for arm0 in ((-60.0, 0.0, 0.0), (-130.0, 0.0, 0.0)):
            x0 = np.zeros(19)
            x0[0], x0[1], x0[3] = 10.0, yaw, -2.0
            x0[6:9] = arm0
            x0[9:12] = (-60.0, 0.0, 20.0)
            x0[12:16] = (10.0, -6.0, -30.0, 6.0)
            x0 = np.clip(x0, np.array(lo) + 1e-6, np.array(hi) - 1e-6)
            try:
                s = least_squares(bp.residuals, x0, bounds=(lo, hi), max_nfev=120)
            except Exception:
                continue
            if best is None or s.cost < best.cost:
                best = s
    return best


# ---- blade + wrist ----------------------------------------------------------------------------------------------------------------
def solve_blade(beat: dict, cam: KP.Camera, grip_plan: np.ndarray, length: float, prev_normal):
    """-> (direction d, face normal n) in the plan frame."""
    bl = beat.get("blade") or {}
    tip = beat["points"].get("blade_tip")
    if tip is None or tip.get("c", 1) <= 0:
        raise ValueError(f"{beat['id']}: blade_tip is required")
    o, v = cam.ray(*tip["xy"])
    g = grip_plan
    b = float(v @ (g - o))
    c = float((g - o) @ (g - o)) - length * length
    disc = b * b - c
    if disc <= 0:
        cands = [o + v * b]
    else:
        s = math.sqrt(disc)
        cands = [o + v * (b - s), o + v * (b + s)]   # near, far
    dirs = [(q - g) / np.linalg.norm(q - g) for q in cands]
    flag = bl.get("depth", "inplane")
    if len(dirs) == 1:
        d = dirs[0]
    elif bl.get("floor"):
        # the blade lies / rests on the floor: take the candidate whose tip is closest to a floor-level tip (0.1 above the floor)
        d = min(dirs, key=lambda q: abs((g + q * length)[1] - 0.1))
    elif flag == "toward":
        d = dirs[0]
    elif flag == "away":
        d = dirs[1]
    else:
        d = min(dirs, key=lambda q: abs(q @ v))
    up = np.array([0.0, 1.0, 0.0])
    face = bl.get("face", "unknown")
    if "normal" in bl:
        n = np.array(bl["normal"], float)
    elif face == "up":
        n = up
    elif face == "camera":
        n = cam.pos - g
    elif face == "edge":
        n = np.cross(d, v)
        if n @ up < 0:
            n = -n
    else:
        n = prev_normal if prev_normal is not None else up
    n = n - (n @ d) * d
    if np.linalg.norm(n) < 1e-3:   # blade parallel to the requested normal: take any perpendicular
        n = np.cross(d, [1.0, 0.0, 0.0] if abs(d[0]) < 0.9 else [0.0, 0.0, 1.0])
    n = n / np.linalg.norm(n)
    return d, n


def wrist_for(pose: RM.Pose, d_plan, n_plan):
    """Wrist euler [a, b, c] so that weapon.axis = d and the broad-face normal = n (plan frame vectors)."""
    lm = RM.fk(pose)
    d, n = RM.mir(d_plan), RM.mir(n_plan)
    e1 = RM.WEAPON_TIP_LOCAL / np.linalg.norm(RM.WEAPON_TIP_LOCAL)
    x = np.array([1.0, 0.0, 0.0])
    B = np.column_stack([x, e1, np.cross(x, e1)])
    # the plan frame mirror turns a proper rotation into an improper one for the third basis vector: build it in the rig frame
    Gm = np.column_stack([n, d, np.cross(n, d)])
    G = Rot.from_matrix(Gm @ B.T)
    return RM.wrist_euler_of(lm["frame_R"].inv() * G)


# ---- driver -------------------------------------------------------------------------------------------------------------------------
def body_keys_from_pose(p: RM.Pose) -> dict:
    return {
        "root_rot": [round(float(v), 1) for v in p.root_rot], "root_pos": [round(float(v), 1) for v in p.root_pos],
        "bones": {n: [round(float(v), 1) for v in p.bones[n]["rotation"]] for n in RM.BONE_NAMES},
    }


def fmt(v):
    return "[" + ", ".join(("%g" % round(float(x), 1)) for x in v) + "]"


def load_content(skill: str):
    sk = yaml.safe_load((ROOT / "content/skills" / f"{skill}.yaml").read_text())
    rig_id = body_id = None
    freezes = []
    for s in sk["phases"]["cast"]:
        if s.get("type") == "play_vfx":
            doc = yaml.safe_load((ROOT / "content/vfx" / f"{s['vfx_id']}.yaml").read_text())
            if doc.get("rigs") and doc.get("body"):
                rig_id, body_id = s["vfx_id"], doc["body"]
                freezes = [(int(f["at"]), int(f["ticks"])) for f in s.get("freeze", [])]
    if rig_id is None:
        raise SystemExit(f"{skill}: no play_vfx with a body rig")
    return rig_id, body_id, freezes


def rig_tick_for(skill_tick: int, freezes, base=1, length=200) -> int:
    stops = RM.stops_from_freezes(freezes, base)
    for l in range(0, length):
        if l == 0 and skill_tick == 0:
            return 0
        if RM.effect_tick_of_rig_tick(l, base, stops) == skill_tick:
            return l
    raise SystemExit(f"skill tick {skill_tick} is inside a hit-stop freeze: no rig key can become visible there")


def targets_from_pose(beat: dict, pose: RM.Pose, d_plan, n_plan, height: float):
    """The lint numbers of a solved beat, measured the way skill_lint.check1 measures them (lean in the torso's own frame)."""
    lm = RM.fk(pose)
    wp = RM.weapon(pose, lm)
    tip = RM.mir(wp["tip"])
    ax = RM.mir(wp["axis"])
    side = math.degrees(math.atan2(ax[1], ax[2]))
    torso = RM.mir_rot(lm["body_R"])
    up, fw = torso.apply([0, 1, 0]), torso.apply([0, 0, 1])
    psi = math.atan2(fw[0], fw[2])
    upz = up[0] * math.sin(psi) + up[2] * math.cos(psi)
    lean = math.degrees(math.atan2(upz, up[1]))
    twist = abs(math.degrees(psi))
    return dict(tip_side_deg=side, tip_y=float(tip[1]), normal=[float(v) for v in RM.mir(wp["normal"])],
                hips_h=float(lm["hips"][1] / height), lean_deg=lean, twist_deg=twist)


TOL = {   # [high, medium, low]: what an annotation of that confidence can honestly pin down
    "tip_side_deg": (15, 22, 35), "tip_y": (0.20, 0.30, 0.50), "face_tol": (30, 40, 55),
    "hips_h": (0.06, 0.09, 0.14), "lean_deg": (10, 15, 22), "twist_deg": (20, 30, 45),
}


def tol(key, conf):
    return TOL[key][{"high": 0, "medium": 1, "low": 2}.get(conf, 1)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("skill")
    ap.add_argument("--out", default=str(ROOT / "content/skill-lint/solved"))
    ap.add_argument("--no-write-view", action="store_true")
    ap.add_argument("--only")
    a = ap.parse_args()
    kpd = KP.load(a.skill)
    intent = KP.intent_of(kpd)
    rig_id, body_id, freezes = load_content(a.skill)
    body0 = yaml.safe_load((ROOT / "content/animations" / f"{body_id}.yaml").read_text())
    rig_text = (ROOT / "content/vfx" / f"{rig_id}.yaml").read_text()
    height = float(kpd.get("caster_height", 1.8))
    length = float(kpd.get("blade_length_ref", 1.9))
    solved, prev_n = [], None
    report = {"skill": a.skill, "beats": []}
    for beat in kpd["beats"]:
        if a.only and not beat["id"].startswith(a.only):
            continue
        cam = KP.camera_for(kpd, beat, intent)
        bp = BeatProblem(beat, cam)
        s = solve_pose(bp)
        x = s.x
        pose = unpack(x[:16])
        view = [round(float(x[16]), 1), round(float(x[17]), 1), round(float(1.0 + x[18]), 3)]
        cam = KP.Camera(cam.pos, cam.target, cam.fov, view)
        lm = RM.fk(pose)
        d, n = solve_blade(beat, cam, RM.mir(lm["grip"]), length, prev_n)
        prev_n = n
        pose.wrist = np.array(wrist_for(pose, d, n))
        wp = RM.weapon(pose, lm)
        err = float(np.linalg.norm(RM.mir(wp["axis"]) - d))
        tg = targets_from_pose(beat, pose, d, n, height)
        # reprojection RMS of the solved pose against the annotations (px)
        names = [k for k in bp.pts if k in ("head_top", "neck", "r_shoulder", "l_shoulder", "r_wrist", "l_wrist", "pelvis", "r_ankle", "l_ankle", "grip")]
        pr = cam.project(np.array([RM.mir(lm[POINT_MAP[k][0]]) for k in names]))
        rms = float(np.sqrt(np.mean([(pr[i][0] - bp.pts[k]["xy"][0]) ** 2 + (pr[i][1] - bp.pts[k]["xy"][1]) ** 2 for i, k in enumerate(names)])))
        solved.append((beat, pose, tg))
        if not a.no_write_view and not a.only:
            beat["view"] = view
        report["beats"].append({"id": beat["id"], "key_tick": beat["key_tick"], "cost": float(s.cost), "rms_px": round(rms, 1), "view": view,
                                "facing_deg": round(facing_of(pose, cam), 1), "blade_dir_err": err, "targets": tg,
                                "keys": {**body_keys_from_pose(pose), "wrist": [round(float(v), 1) for v in pose.wrist]}})
        print("%-26s rms %5.1f px  facing %6.1f  hips_h %.2f lean %5.1f twist %5.1f tip_side %7.1f tip_y %.2f  view %s" % (
            beat["id"], rms, facing_of(pose, cam), tg["hips_h"], tg["lean_deg"], tg["twist_deg"], tg["tip_side_deg"], tg["tip_y"], view))
    if a.only:
        return 0
    if not a.no_write_view:
        KP.save(a.skill, kpd)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    write_body(out / f"{body_id}.yaml", body0, body_id, solved, freezes, a.skill, kpd)
    write_rig(out / f"{rig_id}.yaml", rig_text, solved, freezes, a.skill)
    write_targets(out / f"{a.skill}.targets.yaml", a.skill, solved)
    bdir = ROOT / "build" / "pose-solve" / a.skill
    bdir.mkdir(parents=True, exist_ok=True)
    (bdir / "solve.json").write_text(json.dumps(report, indent=1))
    print("wrote", out / f"{body_id}.yaml", out / f"{rig_id}.yaml", out / f"{a.skill}.targets.yaml")
    return 0


def key_ticks(solved, freezes):
    return [rig_tick_for(int(b["key_tick"]), freezes) for b, _, _ in solved]


def write_body(path: Path, body0: dict, body_id: str, solved, freezes, skill, kpd):
    ticks = key_ticks(solved, freezes)
    hand = kpd.get("handback_skill_tick")
    rows = [(t, p) for t, (b, p, _) in zip(ticks, solved)]
    if hand is not None:
        rows.append((rig_tick_for(int(hand), freezes), RM.Pose()))
    length = int(body0["length_ticks"])
    lines = [
        f"# {body_id}: SOLVED from content/skill-lint/{skill}.keypoints.yaml by tools/pose-solve/solve.py. Do not hand-edit: edit the keypoints and rerun.",
        "# Pose to pose (ADR-0254): every key after the first is a CUT (`ease: step`). Keys are RIG ticks; the hit-stop freezes shift them to skill ticks.",
        "# beats (skill tick -> rig tick): " + ", ".join(f"{b['id']} {b['key_tick']}->{t}" for (b, _, _), t in zip(solved, ticks))
        + (f", hand-back {hand}->{rows[-1][0]}" if hand is not None else ""),
        f"id: {body_id}", f"length_ticks: {length}", f"loop: {str(body0.get('loop', False)).lower()}",
        f"lock_movement: {str(body0.get('lock_movement', False)).lower()}", "blend_in_ticks: 0", "blend_out_ticks: 0", "bones:"]

    def chan(bone, key, getter, default):
        lines.append(f"    {key}:")
        for i, (t, p) in enumerate(rows):
            e = "" if i == 0 else ", ease: step"
            lines.append(f"      - {{ t: {t}, v: {fmt(getter(p))}{e} }}")

    for bone in RM.BONE_NAMES:
        lines.append(f"  {bone}:")
        chan(bone, "rotation", lambda p, b=bone: p.bones[b]["rotation"], 0)
        if bone.endswith("_leg"):
            chan(bone, "scale", lambda p: [1, 1, 1], 1)
    lines.append("root:")
    lines.append("  rotation:")
    for i, (t, p) in enumerate(rows):
        lines.append(f"    - {{ t: {t}, v: {fmt(p.root_rot)}{'' if i == 0 else ', ease: step'} }}")
    lines.append("  position:")
    for i, (t, p) in enumerate(rows):
        lines.append(f"    - {{ t: {t}, v: {fmt(p.root_pos)}{'' if i == 0 else ', ease: step'} }}")
    path.write_text("\n".join(lines) + "\n")


def write_rig(path: Path, text: str, solved, freezes, skill):
    ticks = key_ticks(solved, freezes)
    kpd = KP.load(skill)
    hand = kpd.get("handback_skill_tick")
    rows = [(t, p.wrist) for t, (b, p, _) in zip(ticks, solved)]
    if hand is not None:
        rows.append((rig_tick_for(int(hand), freezes), np.zeros(3)))
    sw = ["  swing:"]
    for i, ((t, w), item) in enumerate(zip(rows, list(solved) + [None])):
        label = solved[i][0]["id"] if i < len(solved) else "hand-back"
        sw.append(f"  - {{ t: {t}, rotate: {fmt(w)}{'' if i == 0 else ', ease: step'} }}   # {label}")
    new = re.sub(r"  swing:\n(?:  - .*\n?)+", "\n".join(sw) + "\n", text)
    if new == text:
        raise SystemExit("could not find the swing: block of the rig file")
    head = f"# SOLVED wrist keys (tools/pose-solve/solve.py from content/skill-lint/{skill}.keypoints.yaml); everything outside `swing:` is the shipped rig.\n"
    path.write_text(head + new)


def write_targets(path: Path, skill: str, solved):
    src = yaml.safe_load((ROOT / "content/skill-lint" / f"{skill}.targets.yaml").read_text())
    byid = {b["id"]: b for b in src["beats"]}
    for beat, pose, tg in solved:
        t = byid.get(beat["id"])
        if t is None:
            continue
        conf = beat.get("confidence", "medium")
        t["confidence"] = conf
        if "blade" in t or beat.get("blade"):
            bl = {"tip_side_deg": [round(tg["tip_side_deg"]), tol("tip_side_deg", conf)], "tip_y": [round(tg["tip_y"], 2), tol("tip_y", conf)]}
            if (beat.get("blade") or {}).get("face") in ("up", "camera", "edge"):
                bl["face_normal"] = [round(v, 2) for v in tg["normal"]]
                bl["face_tol"] = tol("face_tol", conf)
            t["blade"] = bl
        t["body"] = {"hips_h": [round(tg["hips_h"], 2), tol("hips_h", conf)], "lean_deg": [round(tg["lean_deg"]), tol("lean_deg", conf)],
                     "twist_deg": [round(tg["twist_deg"]), tol("twist_deg", conf)]}
    head = (f"# SOLVED targets for {skill}: numbers measured from the solved pose (tools/pose-solve/solve.py), tolerances from the annotation confidence.\n"
            f"# Same format as {skill}.targets.yaml (docs/contributing/vfx-team/skill-lint.md). Copy over it once the solved keys replace the shipped ones.\n")
    path.write_text(head + yaml.safe_dump(src, sort_keys=False, allow_unicode=True, default_flow_style=None, width=140))


if __name__ == "__main__":
    sys.exit(main())
