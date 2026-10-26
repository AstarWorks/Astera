"""Stamp a completed capture with its case version and repeated-frame diagnostics.

Usage: capture_meta.py <case.json|-> <capture-directory> [camera-intent.json]
Also records provenance (branch, commit) so a comparison always states which build produced the frames.
Repeated frames request visual review; an idle scene alone is not proof of a stall.
"""
import datetime
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import sys

case_path, directory = map(Path, sys.argv[1:3])
case = json.loads(case_path.read_text()) if str(case_path) != "-" else {}
path = directory / "meta.json"
meta = json.loads(path.read_text())
# The case's own revisions are the keys skill-compare matches on; they say nothing about what was filmed.
meta.update({key: case[key] for key in ("referenceRevision",) if key in case})
if "contentRevision" in case:
    meta["caseContentRevision"] = case["contentRevision"]
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


def git(*args):
    return subprocess.check_output(["git", *args], cwd=root, text=True).strip()


def dirty_digest(pathspec):
    """sha256 over the uncommitted state (modified, deleted, untracked) under pathspec."""
    names = sorted({n for n in git("ls-files", "-m", "-d", "-o", "--exclude-standard", "-z", "--", pathspec).split("\0") if n})
    digest = hashlib.sha256()
    for name in names:
        file = root / name
        digest.update(name.encode() + b"\0")
        digest.update(file.read_bytes() if file.is_file() else b"<deleted>")
    return digest.hexdigest()[:12], bool(names)


head = git("rev-parse", "HEAD")
meta["implementationCommit"] = head
meta["codeRevision"] = head
# contentRevision fingerprints what was actually filmed: the git tree of content/ plus a digest of any
# uncommitted change under it. codeRevision is HEAD; codeDirty marks uncommitted changes outside content/.
content_digest, content_dirty = dirty_digest("content")
meta["contentRevision"] = git("rev-parse", "HEAD:content") + ("+dirty-" + content_digest if content_dirty else "")
meta["contentDirty"] = content_dirty
meta["codeDirty"] = bool(git("status", "--porcelain", "--", ".", ":(exclude)content"))
if len(sys.argv) > 3 and Path(sys.argv[3]).is_file():
    meta["cameraRevision"] = hashlib.sha256(Path(sys.argv[3]).read_bytes()).hexdigest()
try:
    meta["branch"] = subprocess.check_output(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=root, text=True).strip()
except subprocess.CalledProcessError:
    pass
# Warm-stack provenance: what the long-lived server actually served. The sync step in vfx-shot.sh verified the
# deployed content hash inside the container before filming; here the working tree is fingerprinted again, so an edit
# made while the capture was running (frames then show an older state than the tree) is flagged.
state_dir = os.environ.get("VFX_SHOT_STATE")
deployed_file = Path(state_dir) / "deployed.json" if state_dir else None
if deployed_file and deployed_file.is_file():
    sys.dont_write_bytecode = True  # a .pyc in the tree would make the next capture record codeDirty
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from shotstate import inputs
    deployed = json.loads(deployed_file.read_text())
    now = inputs(Path(__file__).resolve().parents[2])
    in_sync = (now["contentSourceHash"] == deployed.get("contentSourceHash") and now["packHash"] == deployed.get("packHash")
               and now["codeHash"] == deployed.get("codeHash"))
    meta["stack"] = {
        "mode": "cold" if os.environ.get("VFX_SHOT_STACK_COLD") == "1" else "warm",
        "deployedContentHash": deployed.get("deployedContentHash"),
        "contentSourceHash": deployed.get("contentSourceHash"),
        "contentVerifiedInContainer": deployed.get("contentVerified") == "true",
        "packHash": deployed.get("packHash"),
        "packSha1": deployed.get("deployedPackSha1"),
        "codeHash": deployed.get("codeHash"),
        "deployedCodeRevision": deployed.get("codeRevision"),
        "deployedCodeDirty": deployed.get("codeDirty") == "true",
        "deployedAt": deployed.get("deployedAt"),
        "workingTreeInSync": in_sync,
    }
    if not in_sync:
        meta["captureQuality"].update(invalid=True, reviewRequired=True,
            reason="content/, pack inputs or plugin code changed on disk while the capture ran; the frames show the previously "
                   "deployed state, not the working tree. Recapture required.")
stamp_file = directory / "framestamp.json"
if stamp_file.is_file():
    st = json.loads(stamp_file.read_text())
    meta["tickStamp"] = {k: v for k, v in st.items() if k not in ("map", "changeEvents")}
    # A tick the client never drew has no true frame; the nearest-stamp stand-in must not pass as exact. List them and
    # invalidate the capture (publish-capture refuses it), including any key beat that lands on one.
    missing = [m for m in st.get("map", []) if not m.get("exact", True)]
    if missing or st.get("skippedTicks", 0) > 0:
        ticks = sorted(m["tick"] for m in missing)
        gaps = []
        for t in ticks:
            if gaps and t == gaps[-1][1] + 1:
                gaps[-1][1] = t
            else:
                gaps.append([t, t])
        meta["tickStamp"]["skippedFrames"] = [m["n"] for m in missing]
        meta["tickStamp"]["skippedStamps"] = [m.get("stamp") for m in missing]
        meta["tickStamp"]["skippedTickList"] = ticks
        meta["tickStamp"]["gaps"] = gaps
        meta["captureQuality"].update(invalid=True, reviewRequired=True,
            reason=f"{st.get('skippedTicks', len(missing))} tick(s) were never drawn by the client (ticks {ticks[:12]}); "
                   "frames for them are nearest-stamp stand-ins, not exact. Every key beat on them is invalid; recapture "
                   "(stepped mode, STEPPED=1, never skips) required.")
else:
    meta["tickStamp"] = None
if os.environ.get("VFX_SHOT_BEAT_ONLY") == "1":
    meta["beatFramesOnly"] = True
    meta["beatFrames"] = [int(f.stem.split("_")[1]) for f in frames]
path.write_text(json.dumps(meta, indent=2) + "\n")
