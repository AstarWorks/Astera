"""Stamp a completed capture with its case version and repeated-frame diagnostics.

Usage: capture_meta.py <case.json> <capture-directory> [camera-intent.json]
Repeated frames request visual review; an idle scene alone is not proof of a stall.
"""
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import sys

case_path, directory = map(Path, sys.argv[1:3])
case = json.loads(case_path.read_text())
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
if frames:
    meta["capturedAt"] = datetime.datetime.fromtimestamp(frames[-1].stat().st_mtime, datetime.timezone.utc).isoformat()
root = Path(__file__).resolve().parents[2]
meta["implementationCommit"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
if len(sys.argv) > 3 and Path(sys.argv[3]).is_file():
    meta["cameraRevision"] = hashlib.sha256(Path(sys.argv[3]).read_bytes()).hexdigest()
path.write_text(json.dumps(meta, indent=2) + "\n")
