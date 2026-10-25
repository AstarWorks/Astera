#!/usr/bin/env python3
"""Overlay lint: project plan.json back onto each reference beat frame and measure the error in the reference's own pixels.

    python3 tools/pose-solve/overlay.py <skill-id> [--plan plan.json | --simulate] [--out build/skill-lint/<skill>/overlay] [--frames-dir DIR]
    (skill_lint.py calls the same code as check "1b overlay")

For every beat of content/skill-lint/<skill>.keypoints.yaml it takes the plan tick `beat.tick`, rebuilds the skeleton landmarks
(head top, neck, shoulders, elbows, wrists, pelvis, knees, ankles, grip, pommel, blade tip) from the plan dump, projects them
through the beat's camera (the compare camera + the fitted `view`), and compares with the annotated points:
  rms_px / rms_frac   RMS distance of the confident core joints, in pixels and as a fraction of the figure's height in the image
  blade_deg           angle between the annotated grip->tip line and the projected grip->tip line on screen
  tip_off_px          distance of the annotated blade tip from the projected blade LINE (the rig blade may be longer than the
                      reference blade, so the tip is judged across the line, not along it)
and writes <out>/<beat id>.png: the reference frame with the annotated points (green), the projected skeleton (red) and the blade (cyan).
Tolerances: keypoints file `overlay_tolerance: {rms_frac, blade_deg, tip_off_frac}` (defaults 0.12 / 14 / 0.06).
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import numpy as np
import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import kp as KP  # noqa: E402
import rigmodel as RM  # noqa: E402

ROOT = KP.ROOT
K = RM.PX * RM.PLAYER_SCALE
CORE = ("head_top", "neck", "r_shoulder", "l_shoulder", "r_wrist", "l_wrist", "pelvis", "r_ankle", "l_ankle", "grip")
SKELETON = [("head_top", "neck"), ("neck", "pelvis"), ("r_shoulder", "l_shoulder"), ("r_shoulder", "r_elbow"), ("r_elbow", "r_wrist"),
            ("l_shoulder", "l_elbow"), ("l_elbow", "l_wrist"), ("pelvis", "r_knee"), ("r_knee", "r_ankle"), ("pelvis", "l_knee"),
            ("l_knee", "l_ankle"), ("neck", "r_shoulder"), ("neck", "l_shoulder")]
DEFAULT_TOL = {"rms_frac": 0.12, "blade_deg": 14.0, "tip_off_frac": 0.06}


def _rot(q):
    from scipy.spatial.transform import Rotation as Rot
    return Rot.from_quat(q)


def landmarks_from_plan(tk: dict) -> dict:
    """Annotation landmarks (plan frame, blocks) from one plan.json tick. Only uses what the dump carries."""
    b = tk["bones"]
    pos = lambda n: np.array(b[n]["pos"], float)
    rot = lambda n: _rot(b[n]["rot"])
    out = {}
    out["head_top"] = pos("head") + rot("head").apply([0.0, 4.0 * K, 0.0])
    out["neck"] = pos("body") + rot("body").apply([0.0, 6.0 * K, 0.0])
    hips, hr = pos("hips"), rot("hips")
    out["pelvis"] = hips
    out["r_shoulder"] = hips + hr.apply([5.0 * K, 10.0 * K, 0.0])      # plan frame: the right arm is at +x
    out["l_shoulder"] = hips + hr.apply([-5.0 * K, 10.0 * K, 0.0])
    for s, bone in (("r", "right_arm"), ("l", "left_arm")):
        out[f"{s}_elbow"] = pos(bone)
        out[f"{s}_wrist"] = pos(bone) + rot(bone).apply([0.0, -6.0 * K, 0.0])
    for s, bone in (("r", "right_leg"), ("l", "left_leg")):
        out[f"{s}_knee"] = pos(bone)
        out[f"{s}_ankle"] = pos(bone) + rot(bone).apply([0.0, -6.0 * K, 0.0])
    w = tk.get("weapon")
    if w:
        g, t = np.array(w["pos"], float), np.array(w["tip"], float)
        out["grip"], out["blade_tip"] = g, t
        out["pommel"] = g - (t - g) / np.linalg.norm(t - g) * RM.POMMEL_BACK
    return out


def beat_metrics(beat: dict, cam: KP.Camera, lm: dict, tol: dict) -> dict:
    pts = {n: p for n, p in beat["points"].items() if p.get("c", 1.0) > 0}
    names = [n for n in lm if n in beat["points"]]
    proj = {n: cam.project(lm[n])[0] for n in lm}
    errs = {n: float(np.hypot(proj[n][0] - beat["points"][n]["xy"][0], proj[n][1] - beat["points"][n]["xy"][1])) for n in names if n in pts and n != "blade_tip"}
    core = [errs[n] for n in CORE if n in errs]
    # figure height in the image: head top to the lowest annotated ankle
    ys = [pts[n]["xy"][1] for n in ("r_ankle", "l_ankle") if n in pts]
    fig_h = (max(ys) - pts["head_top"]["xy"][1]) if ys and "head_top" in pts else 200.0
    fig_h = max(fig_h, 60.0)
    rms = float(np.sqrt(np.mean(np.square(core)))) if core else float("nan")
    out = {"rms_px": rms, "rms_frac": rms / fig_h, "fig_h": fig_h, "per_point": errs, "proj": {k: [float(v[0]), float(v[1])] for k, v in proj.items()}}
    if "grip" in pts and "blade_tip" in pts and "grip" in proj:
        ga, ta = np.array(pts["grip"]["xy"], float), np.array(pts["blade_tip"]["xy"], float)
        gp, tp = proj["grip"][:2], proj["blade_tip"][:2]
        da, dp = ta - ga, tp - gp
        ang = math.degrees(math.acos(max(-1.0, min(1.0, da @ dp / (np.linalg.norm(da) * np.linalg.norm(dp) + 1e-9)))))
        n = dp / (np.linalg.norm(dp) + 1e-9)
        rel = ta - gp
        along = float(rel @ n)
        off = float(abs(rel[0] * n[1] - rel[1] * n[0]))
        out.update(blade_deg=ang, tip_off_px=off, tip_off_frac=off / fig_h, tip_along_px=along, blade_len_px=float(np.linalg.norm(dp)), ref_len_px=float(np.linalg.norm(da)))
    out["ok"] = bool(core) and out["rms_frac"] <= tol["rms_frac"] and out.get("blade_deg", 0) <= tol["blade_deg"] and out.get("tip_off_frac", 0) <= tol["tip_off_frac"]
    return out


def draw(beat: dict, image, m: dict, path: Path):
    from PIL import ImageDraw
    im = image.copy()
    d = ImageDraw.Draw(im)
    proj = m["proj"]
    for a, b in SKELETON:
        if a in proj and b in proj:
            d.line([tuple(proj[a]), tuple(proj[b])], fill=(255, 60, 60), width=2)
    if "grip" in proj and "blade_tip" in proj:
        d.line([tuple(proj["pommel"]), tuple(proj["blade_tip"])], fill=(0, 220, 255), width=3)
    for n, v in proj.items():
        if n in beat["points"]:
            d.ellipse([v[0] - 3, v[1] - 3, v[0] + 3, v[1] + 3], outline=(255, 60, 60))
    for n, p in beat["points"].items():
        x, y = p["xy"]
        col = (60, 255, 60) if p.get("c", 1.0) > 0.5 else (255, 220, 60)
        d.ellipse([x - 4, y - 4, x + 4, y + 4], outline=col, width=2)
        d.text((x + 6, y - 6), n, fill=col)
        if n in proj and n != "blade_tip":
            d.line([(x, y), tuple(proj[n][:2])], fill=(255, 255, 255), width=1)
    # crop to the figure (annotated + projected points, margin) and enlarge so a human can read the 20 px errors
    xs = [p["xy"][0] for p in beat["points"].values()] + [v[0] for v in proj.values()]
    ys = [p["xy"][1] for p in beat["points"].values()] + [v[1] for v in proj.values()]
    x0, x1 = max(0, min(xs) - 40), min(im.width, max(xs) + 40)
    y0, y1 = max(0, min(ys) - 40), min(im.height, max(ys) + 40)
    im = im.crop((int(x0), int(y0), int(x1), int(y1)))
    z = max(1.0, min(3.0, 1000.0 / max(im.width, 1)))
    im = im.resize((int(im.width * z), int(im.height * z)))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, im.width, 14], fill=(0, 0, 0))
    d.text((4, 2), "%s t%d  rms %.1f px (%.0f%% of figure)  blade %.1f deg  tip off %.1f px  model blade x%.2f of the reference" % (
        beat["id"], beat["tick"], m["rms_px"], 100 * m["rms_frac"], m.get("blade_deg", float("nan")), m.get("tip_off_px", float("nan")),
        m.get("blade_len_px", 0) / max(m.get("ref_len_px", 1), 1)), fill=(255, 255, 255))
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path)


def fit_view(beat: dict, cam: KP.Camera, lm: dict) -> tuple:
    """Image-space pan + zoom that best lines the projected core joints up with the annotated ones (diagnostic: compares two pose sets fairly)."""
    from scipy.optimize import least_squares
    names = [n for n in CORE if n in beat["points"] and beat["points"][n].get("c", 1.0) > 0 and n in lm]
    P = np.array([lm[n] for n in names])
    A = np.array([beat["points"][n]["xy"] for n in names], float)

    def res(v):
        c = KP.Camera(cam.pos, cam.target, cam.fov, (v[0], v[1], 1.0 + v[2]))
        return np.concatenate([(c.project(P)[:, :2] - A).ravel() / 15.0, [v[0] / 250.0, v[1] / 250.0, v[2] / 0.3]])
    s = least_squares(res, [0.0, 0.0, 0.0], bounds=([-500, -500, -0.45], [500, 500, 0.45]))
    return (float(s.x[0]), float(s.x[1]), 1.0 + float(s.x[2]))


def run(skill: str, plan: dict, out_dir: Path | None, frames_dir: Path | None = None, kpd: dict | None = None, refit_view: bool = False):
    """-> list of (beat id, tick, ok, metrics). Writes overlay PNGs to out_dir when given and the reference frame is available."""
    kpd = kpd or KP.load(skill)
    intent = KP.intent_of(kpd)
    tol = {**DEFAULT_TOL, **(kpd.get("overlay_tolerance") or {})}
    by_tick = {int(t["tick"]): t for t in plan["ticks"]}
    res = []
    for beat in kpd["beats"]:
        tk = by_tick.get(int(beat["tick"]))
        if tk is None or not tk.get("weapon"):
            res.append((beat["id"], beat["tick"], False, {"error": "tick missing from the plan dump"}))
            continue
        cam = KP.camera_for(kpd, beat, intent)
        lm = landmarks_from_plan(tk)
        if refit_view:
            v = fit_view(beat, cam, lm)
            cam = KP.Camera(cam.pos, cam.target, cam.fov, v)
        m = beat_metrics(beat, cam, lm, tol)
        if out_dir is not None:
            try:
                draw(beat, KP.ref_frame(kpd, int(beat["tick"]), frames_dir), m, out_dir / f"{beat['id']}.png")
            except FileNotFoundError as e:
                m["overlay"] = f"not written: {e}"
        res.append((beat["id"], beat["tick"], m["ok"], m))
    return res, tol


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("skill")
    ap.add_argument("--plan")
    ap.add_argument("--simulate", action="store_true", help="build the plan with the numpy port of the planner from content/ (no JVM)")
    ap.add_argument("--body")
    ap.add_argument("--rig")
    ap.add_argument("--out")
    ap.add_argument("--frames-dir")
    ap.add_argument("--refit-view", action="store_true", help="refit the image-space pan/zoom to THIS plan before measuring (fair A/B of two pose sets)")
    a = ap.parse_args()
    if a.simulate:
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        import solve as SV
        rig_id, body_id, freezes = SV.load_content(a.skill)
        body = yaml.safe_load(Path(a.body or ROOT / "content/animations" / f"{body_id}.yaml").read_text())
        rig = yaml.safe_load(Path(a.rig or ROOT / "content/vfx" / f"{rig_id}.yaml").read_text())
        plan = RM.simulate_plan(body, rig, freezes, int(rig["duration_ticks"]))
    else:
        plan = json.loads(Path(a.plan or ROOT / "build/skill-lint" / a.skill / "plan.json").read_text())
    out = Path(a.out) if a.out else ROOT / "build/skill-lint" / a.skill / "overlay"
    res, tol = run(a.skill, plan, out, Path(a.frames_dir) if a.frames_dir else None, refit_view=a.refit_view)
    for bid, tick, ok, m in res:
        if "error" in m:
            print("[FAIL] %s t%s %s" % (bid, tick, m["error"]))
            continue
        print("[%s] %-26s t%-2d rms %5.1f px (%4.1f%% of figure %3.0f px)  blade %5.1f deg  tip off %5.1f px (%4.1f%%)" % (
            "PASS" if ok else "FAIL", bid, tick, m["rms_px"], 100 * m["rms_frac"], m["fig_h"], m.get("blade_deg", float("nan")),
            m.get("tip_off_px", float("nan")), 100 * m.get("tip_off_frac", float("nan"))))
    print("tolerance", tol, "overlays in", out)
    return 0 if all(r[2] for r in res) else 1


if __name__ == "__main__":
    sys.exit(main())
