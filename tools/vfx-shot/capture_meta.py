"""Stamp a completed capture with its case version and repeated-frame diagnostics.

Usage: capture_meta.py <case.json|-> <capture-directory> [camera-intent.json]
Also records provenance (branch, commit) so a comparison always states which build produced the frames.
Repeated frames request visual review; an idle scene alone is not proof of a stall.
"""
import datetime
import hashlib
import json
import re
from pathlib import Path
import subprocess
import sys

case_path, directory = map(Path, sys.argv[1:3])
case = json.loads(case_path.read_text()) if str(case_path) != "-" else {}
path = directory / "meta.json"
meta = json.loads(path.read_text())
meta.update({key: case[key] for key in ("referenceRevision", "contentRevision") if key in case})
frames = sorted(directory.glob("frame_*.png"))
longest = run = 0
previous = None
for frame in frames:
    digest = hashlib.sha256(frame.read_bytes()).digest()
    run = run + 1 if digest == previous else 1
    longest = max(longest, run)
    previous = digest
meta["captureQuality"] = {"maxRepeatedFrames": longest, "reviewRequired": longest >= 15}
timing = directory / "director-timing.log"
if timing.is_file():
    log = timing.read_text()
    start = re.search(r"^STAMP ([0-9.]+)$", log, re.M)
    cuts = re.findall(r"^CAMERA (\d+) SENT ([0-9.]+) ACK ([0-9.]+)$", log, re.M)
    if start and cuts:
        lateness = max(0.0, max((float(ack) - float(start[1])) * 1000 - int(t) for t, sent, ack in cuts))
        meta["captureQuality"]["cameraMaxLatenessMs"] = round(lateness, 1)
        if lateness > 100:
            meta["captureQuality"].update(invalid=True, reviewRequired=True,
                reason="Camera command response exceeded its planned time by more than 100 ms; recapture required.")
# viewer session: the client log written while recording and the viewer's position before/after.
# A defeat, world change or lobby return means the frames do not show the scene.
VIEWER_MOVE_LIMIT = 40.0
session_problem = None
viewer_log = directory / "viewer-client.log"
if viewer_log.is_file():
    hit = re.search(r"You were defeated|Returning to the lobby|Loading terrain|Respawning|Changing dimension",
                    viewer_log.read_text(errors="replace"), re.I)
    if hit:
        session_problem = f"Viewer client log shows '{hit[0]}' during recording; the viewer left the scene."
viewer_pos = directory / "viewer-pos.txt"
if session_problem is None and viewer_pos.is_file():
    lines = viewer_pos.read_text().splitlines()
    if len(lines) >= 2:
        coords = [[float(n) for n in re.findall(r"(-?\d+(?:\.\d+)?)d", line.split(" in ")[0])] for line in lines[:2]]
        dims = [line.split(" in ")[-1].strip() for line in lines[:2]]
        if dims[0] != dims[1]:
            session_problem = f"Viewer changed dimension during recording ({dims[0]} -> {dims[1]})."
        elif all(len(c) == 3 for c in coords):
            moved = sum((a - b) ** 2 for a, b in zip(*coords)) ** 0.5
            meta["captureQuality"]["viewerMovedBlocks"] = round(moved, 1)
            if moved > VIEWER_MOVE_LIMIT:
                session_problem = f"Viewer moved {moved:.0f} blocks from its teleport position during recording."
        else:
            session_problem = "Viewer position could not be read after recording."
if session_problem:
    meta["captureQuality"].update(invalid=True, reviewRequired=True, reason=session_problem + " Recapture required.")
if frames:
    meta["capturedAt"] = datetime.datetime.fromtimestamp(frames[-1].stat().st_mtime, datetime.timezone.utc).isoformat()
root = Path(__file__).resolve().parents[2]
meta["implementationCommit"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
if len(sys.argv) > 3 and Path(sys.argv[3]).is_file():
    meta["cameraRevision"] = hashlib.sha256(Path(sys.argv[3]).read_bytes()).hexdigest()
try:
    meta["branch"] = subprocess.check_output(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=root, text=True).strip()
except subprocess.CalledProcessError:
    pass
path.write_text(json.dumps(meta, indent=2) + "\n")
