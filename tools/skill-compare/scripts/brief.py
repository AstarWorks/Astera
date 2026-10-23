#!/usr/bin/env python3
"""Browser-free comparison brief for AI agents: reference | in-game at the key moments of a case.

  python3 -I tools/skill-compare/scripts/brief.py --case <case-id> [options] [t_s:label ...]
  make skill-brief CASE=<case-id> [SKILL=..] [VIEW=compare] [AT="19.7:extra"]

Moments default to: start, every hit segment of the case, end (absolute video seconds; extra `t:label` args add more).
Output (default build/brief/<case>/):
  sheet-01.png ...   rows of two equal-scale panels (reference | in-game), labelled, <= ROWS_PER_SHEET rows each
  frames/            the individual frames used (reference_NN.png, minecraft_NN.png)
  manifest.json      machine-readable: moments (video s / master ms / MC tick / frame), files, provenance
                     (branch, commit, capture time, FOV, resolution, published revision), warnings
stdout: the manifest path, then a one-line summary. Read the PNGs to judge; read manifest.json for the facts.

Alignment is the page's own: master ms = (t - case.start_s) * 1000, cast ms = skill segment start + mc.offset_ms,
MC tick = floor((ms - castMs) / 50), MC frame = tick + cast_tick_ms / 50 (clamped to the capture, and warned).
"""
import argparse, datetime, json, math, os, subprocess, sys, tempfile
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
SC = os.path.dirname(HERE)                       # tools/skill-compare
REPO = os.path.dirname(os.path.dirname(SC))
PANEL_W, PANEL_H, HEAD_H, GAP = 560, 315, 22, 6  # 16:9 panels, identical scale for all three columns
ROWS_PER_SHEET = int(os.environ.get("ROWS_PER_SHEET", "3"))
COLUMNS = ("reference", "minecraft")
TITLES = {"reference": "REFERENCE (video)", "minecraft": "IN-GAME (Minecraft)"}


def git(*args):
    try:
        return subprocess.check_output(["git", *args], cwd=REPO, text=True, stderr=subprocess.DEVNULL).strip()
    except Exception:
        return None


def font(size):
    for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/dejavu/DejaVuSans.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def find_video(case):
    names = [case["videoId"] + ".mp4"]
    dirs = [os.environ.get("SKILL_COMPARE_VIDEO_DIR"), os.path.expanduser("~/.cache/astera-skill-compare/videos"), os.path.join(SC, "cache", "videos")]
    for d in dirs:
        for n in names:
            if d and os.path.exists(os.path.join(d, n)):
                return os.path.join(d, n)
    return None


def resolve_capture(skill, view, override):
    """-> (frame dir, meta dict, extension, published revision or None)."""
    if override:
        d = override
        meta = json.load(open(os.path.join(d, "meta.json"))) if os.path.exists(os.path.join(d, "meta.json")) else {}
        return d, meta, None, None
    pub = os.environ.get("SKILL_COMPARE_PUBLISHED") or os.path.join(REPO, "build", "skill-compare-published")
    cur = os.path.join(pub, skill, view, "current.json")
    if os.path.exists(cur):
        m = json.load(open(cur))
        return os.path.join(pub, skill, view, m["revision"]), m.get("meta", {}), m.get("extension"), m["revision"]
    shot = os.environ.get("SKILL_COMPARE_VFX_SHOT") or os.path.join(REPO, "build", "vfx-shot")
    d = os.path.join(shot, skill, view)
    meta = json.load(open(os.path.join(d, "meta.json"))) if os.path.exists(os.path.join(d, "meta.json")) else {}
    return d, meta, None, None


def frame_path(d, n, ext):
    for e in ([ext] if ext else []) + ["png", "webp"]:
        p = os.path.join(d, "frame_%03d.%s" % (n, e))
        if os.path.exists(p):
            return p
    return None


def fit(img, label=None):
    img = img.convert("RGB")
    s = min(PANEL_W / img.width, PANEL_H / img.height)
    out = Image.new("RGB", (PANEL_W, PANEL_H), (12, 14, 20))
    r = img.resize((max(1, round(img.width * s)), max(1, round(img.height * s))), Image.LANCZOS)
    out.paste(r, ((PANEL_W - r.width) // 2, (PANEL_H - r.height) // 2))
    return out


def placeholder(text):
    img = Image.new("RGB", (PANEL_W, PANEL_H), (60, 0, 60))
    ImageDraw.Draw(img).text((12, PANEL_H // 2 - 8), text, fill=(255, 200, 255), font=font(15))
    return img


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--case", required=True)
    ap.add_argument("--skill")
    ap.add_argument("--view", default="compare", choices=["compare", "input", "caster", "opponent", "side"])
    ap.add_argument("--mc", help="capture directory (frame_NNN.png + meta.json); default: published current, else build/vfx-shot")
    ap.add_argument("--out")
    ap.add_argument("--offset-ms", type=int, help="override case.mc.offset_ms")
    ap.add_argument("moments", nargs="*", help="extra moments t_s:label (absolute video seconds)")
    a = ap.parse_args()

    cdir = os.path.join(SC, "data", a.case)
    case = json.load(open(os.path.join(cdir, "case.json")))
    skill = a.skill or case["skill"]["asteraSkillId"]
    out = os.path.abspath(a.out or os.path.join(REPO, "build", "brief", a.case))
    fdir = os.path.join(out, "frames")
    os.makedirs(fdir, exist_ok=True)
    for old in os.listdir(out):
        if old.startswith("sheet-") and old.endswith(".png"):
            os.remove(os.path.join(out, old))
    warnings = []

    start_s, end_s = float(case["start_s"]), float(case["end_s"])
    segs = case.get("segments", [])
    skill_seg = next((s for s in segs if s.get("kind") == "skill"), None)
    cast_ms = round(((skill_seg["start_s"] if skill_seg else start_s) - start_s) * 1000) + (a.offset_ms if a.offset_ms is not None else case.get("mc", {}).get("offset_ms", 0))
    moments = [(start_s, "start")]
    moments += [(s["t_s"], s["id"]) for s in segs if s.get("kind") == "hit" and "t_s" in s]
    moments.append((max(start_s, end_s - 0.05), "end"))
    for spec in a.moments:
        t, _, lab = spec.partition(":")
        moments.append((float(t), lab or "extra"))
    moments.sort(key=lambda m: m[0])

    # --- sources
    video = find_video(case)
    if not video:
        warnings.append("reference video missing (make skill-compare-fetch VIDEO=%s)" % case["videoId"])
    mcdir, meta, ext, revision = resolve_capture(skill, a.view, a.mc)
    have_mc = os.path.isdir(mcdir)
    if not have_mc:
        warnings.append("no %s capture for %s (looked in %s)" % (a.view, skill, mcdir))
    cast_frame = round(meta.get("cast_tick_ms", 0) / 50)
    frames_total = meta.get("frames")
    for key in ("referenceRevision", "contentRevision"):
        if case.get(key) and meta.get(key) != case.get(key):
            warnings.append("%s of the capture (%s) does not match the case (%s): recapture before judging" % (key, (meta.get(key) or "none")[:8], case[key][:8]))
    if meta and not meta.get("fov"):
        warnings.append("capture has no FOV recorded (taken before FOV tracking; vanillaFOV unknown)")
    if meta.get("captureQuality", {}).get("invalid"):
        warnings.append("capture is marked invalid")
    elif meta.get("captureQuality", {}).get("reviewRequired"):
        warnings.append("capture has long runs of repeated frames: check for a stalled client")

    ms_list = [round((t - start_s) * 1000) for t, _ in moments]

    rows, entries = [], []
    sf, hf, tf = font(13), font(12), font(15)
    for i, ((t, label), ms) in enumerate(zip(moments, ms_list)):
        tick = math.floor((ms - cast_ms) / 50 + 1e-6)
        n = tick + cast_frame
        clamped = frames_total is not None and (n < 0 or n >= frames_total)
        n_used = min(max(n, 0), (frames_total or n + 1) - 1)
        files, panels = {}, {}
        # reference
        if video:
            rp = os.path.join(fdir, "reference_%02d.png" % i)
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", "%.3f" % t, "-i", video, "-frames:v", "1", rp], check=True)
            files["reference"] = rp
            panels["reference"] = fit(Image.open(rp))
        else:
            panels["reference"] = placeholder("reference video missing")
        # minecraft
        mp = frame_path(mcdir, n_used, ext) if have_mc else None
        if mp:
            dst = os.path.join(fdir, "minecraft_%02d.png" % i)
            Image.open(mp).convert("RGB").save(dst)
            files["minecraft"] = dst
            panels["minecraft"] = fit(Image.open(dst))
            if clamped:
                warnings.append("moment '%s': MC tick %d is outside the capture (frame %d/%d), showing the nearest frame" % (label, tick, n, frames_total))
        else:
            panels["minecraft"] = placeholder("no in-game frame")
        W = 2 * PANEL_W + 3 * GAP
        row = Image.new("RGB", (W, HEAD_H + PANEL_H + 2 * GAP), (22, 24, 32))
        d = ImageDraw.Draw(row)
        d.text((GAP, 4), "%s   video %.3fs  |  master %d ms  |  MC tick %d (frame %d)%s" % (label, t, ms, tick, n_used, "  [OUT OF RANGE]" if clamped else ""),
               fill=(255, 220, 0), font=tf)
        for c, col in enumerate(COLUMNS):
            x = GAP + c * (PANEL_W + GAP)
            row.paste(panels[col], (x, HEAD_H + GAP))
            d.text((x + 6, HEAD_H + GAP + 4), TITLES[col], fill=(255, 255, 255), font=hf, stroke_width=2, stroke_fill=(0, 0, 0))
        rows.append(row)
        entries.append({"index": i, "label": label, "videoS": round(t, 3), "masterMs": ms, "mcTick": tick, "mcFrame": n_used,
                        "mcFrameOutOfRange": bool(clamped), "files": files})

    prov = {"branch": meta.get("branch"), "commit": meta.get("implementationCommit"), "capturedAt": meta.get("capturedAt"),
            "fov": meta.get("fov"), "fovMode": meta.get("fovMode"), "resolution": meta.get("resolution"), "view": meta.get("view", a.view),
            "frames": frames_total, "castTickMs": meta.get("cast_tick_ms"), "published": revision is not None, "publishedRevision": revision, "dir": mcdir}
    header = "case %s  skill %s  view %s  |  capture: %s @ %s  FOV %s (%s)  %s  |  brief from %s %s" % (
        a.case, skill, a.view, prov["branch"] or "branch ?", (prov["commit"] or "commit ?")[:8], prov["fov"] or "?", prov["fovMode"] or "-",
        "published" if prov["published"] else "UNPUBLISHED", git("rev-parse", "--abbrev-ref", "HEAD"), (git("rev-parse", "--short", "HEAD") or ""))
    sheets = []
    for s0 in range(0, len(rows), ROWS_PER_SHEET):
        chunk = rows[s0:s0 + ROWS_PER_SHEET]
        sheet = Image.new("RGB", (chunk[0].width, 24 + sum(r.height for r in chunk)), (22, 24, 32))
        ImageDraw.Draw(sheet).text((GAP, 5), header, fill=(190, 200, 220), font=font(12))
        y = 24
        for r in chunk:
            sheet.paste(r, (0, y)); y += r.height
        p = os.path.join(out, "sheet-%02d.png" % (s0 // ROWS_PER_SHEET + 1))
        sheet.save(p)
        sheets.append(p)

    manifest = {"case": a.case, "skill": skill, "view": a.view, "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "briefRepo": {"branch": git("rev-parse", "--abbrev-ref", "HEAD"), "commit": git("rev-parse", "HEAD")},
                "reference": {"videoId": case["videoId"], "startS": start_s, "endS": end_s, "referenceRevision": case.get("referenceRevision"),
                              "videoFile": video},
                "caseContentRevision": case.get("contentRevision"), "castMs": cast_ms, "capture": prov,
                "sheets": sheets, "moments": entries, "warnings": sorted(set(warnings))}
    mpath = os.path.join(out, "manifest.json")
    json.dump(manifest, open(mpath, "w"), indent=2, ensure_ascii=False)
    print(mpath)
    print("%d moments, %d sheet(s): %s | %d warning(s)%s" % (len(entries), len(sheets), " ".join(os.path.basename(s) for s in sheets), len(manifest["warnings"]),
          "".join("\n  WARN " + w for w in manifest["warnings"])))
    return 0


sys.exit(main())
