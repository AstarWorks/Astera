"""Keypoint data (content/skill-lint/<skill>.keypoints.yaml), the reference frames and the per-beat camera.

File shape (human + agent edited; `view` is written by the solver):

    skill: bastion-greatsword-s1
    case: bastion-greatsword-s1          # tools/skill-compare/data/<case>/case.json -> videoId, start_s, intent file
    image: [1280, 720]
    blade_length_ref: 1.9                # blocks: the reference blade + hilt (about 1.05 H); the rig blade may be longer
    beats:
    - id: B0-coil
      tick: 3                            # reference tick of the annotated frame (t = start_s + 0.05 * tick)
      key_tick: 0                        # skill tick at which the solved pose becomes visible
      ticks: [0, 5]                      # hold range written into targets.yaml
      facing: -60                        # optional: 0 = torso faces the camera, 90 = screen right, 180 = away, -90 = screen left
      camera: {}                         # overrides on top of the intent camera: bearing, elevation, distance, fov, target
      view: [0, 0, 1]                    # [dx px, dy px, zoom] image-space fit of the camera estimate (solver output)
      blade: {depth: away, face: up}     # depth: toward | away | inplane (tip vs the grip, along the view ray); face: up | camera | edge | unknown; floor: true when the blade lies on the floor (picks the depth solution whose tip is at floor level)
      points:
        head_top: {xy: [x, y], c: 1, depth: mid}   # c = confidence 0..1 (0 = occluded: solved from priors); depth near|mid|far vs the pelvis
"""
from __future__ import annotations

import json
import math
import subprocess
import sys
from pathlib import Path

import numpy as np
import yaml

ROOT = Path(__file__).resolve().parents[2]
KEYPOINTS = ["head_top", "neck", "r_shoulder", "l_shoulder", "r_elbow", "l_elbow", "r_wrist", "l_wrist",
             "pelvis", "r_knee", "l_knee", "r_ankle", "l_ankle", "grip", "pommel", "blade_tip"]
DEPTHS = ("near", "mid", "far")
BLADE_DEPTHS = ("toward", "away", "inplane")
BLADE_FACES = ("up", "camera", "edge", "unknown")
IMG_W, IMG_H = 1280, 720


def kp_path(skill: str) -> Path:
    return ROOT / "content" / "skill-lint" / f"{skill}.keypoints.yaml"


def load(skill: str, path: Path | None = None) -> dict:
    d = yaml.safe_load((path or kp_path(skill)).read_text())
    validate(d)
    return d


def validate(d: dict) -> None:
    probs = []
    for k in ("skill", "case", "beats"):
        if k not in d:
            probs.append(f"missing {k}")
    for b in d.get("beats", []):
        bid = b.get("id", "?")
        for k in ("id", "tick", "key_tick", "ticks", "points"):
            if k not in b:
                probs.append(f"{bid}: missing {k}")
        for n, p in (b.get("points") or {}).items():
            if n not in KEYPOINTS:
                probs.append(f"{bid}: unknown point {n}")
            xy = p.get("xy")
            if not (isinstance(xy, list) and len(xy) == 2 and 0 <= xy[0] <= IMG_W and 0 <= xy[1] <= IMG_H):
                probs.append(f"{bid}.{n}: xy must be [x, y] inside {IMG_W}x{IMG_H}")
            if p.get("depth", "mid") not in DEPTHS:
                probs.append(f"{bid}.{n}: depth must be one of {DEPTHS}")
        bl = b.get("blade") or {}
        if bl.get("depth", "inplane") not in BLADE_DEPTHS:
            probs.append(f"{bid}: blade.depth must be one of {BLADE_DEPTHS}")
        if bl.get("floor") not in (None, True, False):
            probs.append(f"{bid}: blade.floor must be true or false")
        if bl.get("face", "unknown") not in BLADE_FACES:
            probs.append(f"{bid}: blade.face must be one of {BLADE_FACES}")
    if probs:
        raise ValueError("keypoints file invalid:\n  " + "\n  ".join(probs))


def _flow(o, indent=0) -> str:
    """Small YAML emitter: block mappings / lists, flow style for leaves like {xy: [1, 2], c: 1}."""
    pad = "  " * indent
    if isinstance(o, dict):
        if not o:
            return "{}"
        lines = []
        for k, v in o.items():
            if isinstance(v, (dict, list)) and v and _is_block(k, v):
                lines.append(f"{pad}{k}:\n{_flow(v, indent + 1)}")
            else:
                lines.append(f"{pad}{k}: {_leaf(v)}")
        return "\n".join(lines)
    if isinstance(o, list):
        lines = []
        for v in o:
            if isinstance(v, dict):
                body = _flow(v, indent + 1).split("\n")
                lines.append(f"{pad}- {body[0].lstrip()}")
                lines.extend(body[1:])
            else:
                lines.append(f"{pad}- {_leaf(v)}")
        return "\n".join(lines)
    return pad + _leaf(o)


def _is_block(k, v) -> bool:
    return k in ("beats", "points") or (isinstance(v, list) and v and isinstance(v[0], dict))


def _leaf(v) -> str:
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float):
        return ("%.4f" % v).rstrip("0").rstrip(".") if v != int(v) else str(int(v))
    if isinstance(v, (int, type(None))):
        return "null" if v is None else str(v)
    if isinstance(v, str):
        return json.dumps(v, ensure_ascii=False) if (v == "" or any(c in v for c in ":#{}[],&*!|>'\"%@`") or v != v.strip()) else v
    if isinstance(v, list):
        return "[" + ", ".join(_leaf(x) for x in v) + "]"
    if isinstance(v, dict):
        return "{" + ", ".join(f"{k}: {_leaf(x)}" for k, x in v.items()) + "}"
    return str(v)


def dump(d: dict, header: str = "") -> str:
    return header + _flow(d) + "\n"


def save(skill: str, d: dict, path: Path | None = None) -> None:
    validate(d)
    p = path or kp_path(skill)
    old = p.read_text() if p.is_file() else ""
    header = "".join(l + "\n" for l in old.splitlines() if l.startswith("#"))
    p.write_text(dump(d, header))


def round_pts(d: dict) -> dict:
    for b in d["beats"]:
        for p in b["points"].values():
            p["xy"] = [round(float(p["xy"][0]), 1), round(float(p["xy"][1]), 1)]
    return d


# ---- case / video / frames ------------------------------------------------------------------------------------------------------
def case_of(d: dict) -> dict:
    return json.loads((ROOT / "tools/skill-compare/data" / d["case"] / "case.json").read_text())


def intent_of(d: dict) -> dict:
    c = case_of(d)
    f = (c.get("intent") or {}).get("file", "intent.json")
    return json.loads((ROOT / "tools/skill-compare/data" / d["case"] / f).read_text())


def video_path(video_id: str) -> Path:
    import os
    base = Path(os.environ.get("ASTERA_SKILL_COMPARE_VIDEOS", Path.home() / ".cache/astera-skill-compare/videos"))
    return base / f"{video_id}.mp4"


def ref_frame(d: dict, tick: int, frames_dir: Path | None = None, cache_dir: Path | None = None):
    """PIL image (1280x720 RGB) of the reference at skill tick `tick` (t = start_s + 0.05 * tick, the skill-compare clock)."""
    from PIL import Image
    if frames_dir is not None and (frames_dir / f"t{tick:02d}.png").is_file():
        return Image.open(frames_dir / f"t{tick:02d}.png").convert("RGB")
    c = case_of(d)
    out = (cache_dir or ROOT / "build" / "pose-solve" / d["skill"] / "ref") / f"t{tick:02d}.png"
    if not out.is_file():
        v = video_path(c["videoId"])
        if not v.is_file():
            raise FileNotFoundError(f"reference video {v} missing (make skill-compare-fetch VIDEO={c['videoId']}) and no frames dir given")
        out.parent.mkdir(parents=True, exist_ok=True)
        t = c["start_s"] + 0.05 * tick
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{t:.3f}", "-i", str(v), "-frames:v", "1", "-vf", f"scale={IMG_W}:{IMG_H}", str(out)], check=True)
    return Image.open(out).convert("RGB")


# ---- camera ---------------------------------------------------------------------------------------------------------------------
class Camera:
    """Look-at pinhole camera in the CASTER-LOCAL plan frame (+x right, +y up, +z forward; a left-handed labelling, so screen right = up x forward)."""

    def __init__(self, pos, target, fov_deg, view=(0.0, 0.0, 1.0), size=(IMG_W, IMG_H)):
        self.pos = np.array(pos, float)
        self.target = np.array(target, float)
        self.fov = float(fov_deg)
        self.view = tuple(float(x) for x in view)
        self.W, self.H = size
        f = self.target - self.pos
        self.f = f / np.linalg.norm(f)
        r = np.cross([0.0, 1.0, 0.0], self.f)
        self.r = r / np.linalg.norm(r)
        self.u = np.cross(self.f, self.r)
        self.focal = (self.H / 2) / math.tan(math.radians(self.fov) / 2)

    def project(self, p):
        """-> (px, py, depth) for one point or an (N, 3) array."""
        p = np.atleast_2d(np.asarray(p, float))
        d = p - self.pos
        z = d @ self.f
        z = np.where(np.abs(z) < 1e-6, 1e-6, z)
        x = self.W / 2 + self.focal * (d @ self.r) / z
        y = self.H / 2 - self.focal * (d @ self.u) / z
        dx, dy, zoom = self.view
        x = self.W / 2 + (x - self.W / 2) * zoom + dx
        y = self.H / 2 + (y - self.H / 2) * zoom + dy
        return np.stack([x, y, z], axis=1)

    def ray(self, px, py):
        """(origin, unit direction) of the viewing ray through pixel (px, py) (view fit undone)."""
        dx, dy, zoom = self.view
        x = self.W / 2 + (px - dx - self.W / 2) / zoom
        y = self.H / 2 + (py - dy - self.H / 2) / zoom
        d = self.f + self.r * ((x - self.W / 2) / self.focal) - self.u * ((y - self.H / 2) / self.focal)
        return self.pos, d / np.linalg.norm(d)


def intent_key(intent: dict, tick: float) -> dict:
    keys = sorted(intent["camera"], key=lambda k: k["t"])
    cur = keys[0]
    for k in keys:
        if k["t"] <= tick:
            cur = k
    return cur


def camera_for(d: dict, beat: dict, intent: dict | None = None) -> Camera:
    """The beat's camera: the compare-camera key active at the beat's tick, expressed around the caster, plus the beat's overrides."""
    intent = intent or intent_of(d)
    k = intent_key(intent, beat["tick"])
    cx = float(beat.get("caster_x", 0.0))   # caster position along the intent x axis (toward the enemy)
    # intent (x toward enemy, y up, z toward the side camera) -> local (x right = intent z, y up, z forward = intent x - caster x)
    def loc(p):
        return np.array([p[2], p[1], p[0] - cx])
    P, T = loc(k["pos"]), loc(k["target"])
    h = P - T
    horiz = math.hypot(h[0], h[2])
    o = beat.get("camera") or {}
    bearing = float(o.get("bearing", math.degrees(math.atan2(h[0], h[2]))))          # 0 = in front of the caster, 90 = at its right, 180 = behind
    elev = float(o.get("elevation", math.degrees(math.atan2(h[1], horiz))))
    dist = float(o.get("distance", np.linalg.norm(h)))
    fov = float(o.get("fov", k.get("fov", 45)))
    tgt = np.array(o.get("target", T), float)
    hb, he = math.radians(bearing), math.radians(elev)
    pos = tgt + dist * np.array([math.cos(he) * math.sin(hb), math.sin(he), math.cos(he) * math.cos(hb)])
    return Camera(pos, tgt, fov, beat.get("view") or (0, 0, 1))


if __name__ == "__main__":
    # `python3 kp.py read <skill>` / `write <skill> < json` / `frame <skill> <tick>`  (the skill-compare annotate page's storage helper)
    cmd, skill = sys.argv[1], sys.argv[2]
    if cmd == "read":
        print(json.dumps(load(skill)))
    elif cmd == "frame":      # prints the path of the reference frame PNG at skill tick argv[3] (extracted with ffmpeg on first use)
        k = load(skill)
        t = int(sys.argv[3])
        ref_frame(k, t)
        print(ROOT / "build" / "pose-solve" / skill / "ref" / f"t{t:02d}.png")
    elif cmd == "write":
        data = json.load(sys.stdin)
        save(skill, round_pts(data))
        print("ok")
    else:
        sys.exit("usage: kp.py read|write|frame <skill> [tick]")
