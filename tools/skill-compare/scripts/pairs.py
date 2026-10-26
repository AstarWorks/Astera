#!/usr/bin/env python3
"""Side-by-side video vs Minecraft frames at matched moments (one PNG, one row per moment).

Case mode (what `make skill-pairs` runs):
  python3 -I scripts/pairs.py --case <case-id> --skill <astera-skill> --out build/pairs/<case>.png  t1:label t2:label ...
    video path = <video dir>/<videoId>.mp4, t0 = case start_s, pre-ticks = cast_tick_ms/50 of
    build/vfx-shot/<skill>/compare/meta.json (MC dir override: --mc <dir>).
    Times t are absolute video seconds.
Raw mode:
  python3 -I scripts/pairs.py <video.mp4> <mc-compare-dir> <video_t0_s> <pre_ticks> <out.png> t:label ...
"""
import json, os, subprocess, sys, tempfile
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
SC = os.path.dirname(HERE)                       # tools/skill-compare
REPO = os.path.dirname(os.path.dirname(SC))
MC_FPS = 20                                      # capture fps; parse_case reads meta.json fps (FPS=60 = 3 frames per tick)


def video_dir():
    return os.environ.get("SKILL_COMPARE_VIDEO_DIR") or os.path.join(SC, "cache", "videos")


def parse_case(argv):
    opts, rest, i = {}, [], 0
    while i < len(argv):
        if argv[i] in ("--case", "--skill", "--out", "--mc"):
            opts[argv[i][2:]] = argv[i + 1]
            i += 2
        else:
            rest.append(argv[i])
            i += 1
    case = json.load(open(os.path.join(SC, "data", opts["case"], "case.json")))
    skill = opts.get("skill") or case["skill"]["asteraSkillId"]
    shot = os.environ.get("SKILL_COMPARE_VFX_SHOT") or os.path.join(REPO, "build", "vfx-shot")
    mc = opts.get("mc") or os.path.join(shot, skill, "compare")
    meta = os.path.join(mc, "meta.json")
    m = json.load(open(meta)) if os.path.exists(meta) else {}
    global MC_FPS
    MC_FPS = m.get("fps") or 20  # FPS=60 captures hold 3 frames per tick
    pre = round(m.get("cast_tick_ms", 0) * MC_FPS / 1000)
    out = opts.get("out") or os.path.join(REPO, "build", "pairs", opts["case"] + ".png")
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    return os.path.join(video_dir(), case["videoId"] + ".mp4"), mc, float(case["start_s"]), pre, out, rest


if len(sys.argv) > 1 and sys.argv[1].startswith("--"):
    video, mc, t0, pre, out, specs = parse_case(sys.argv[1:])
else:
    video, mc, t0, pre, out, specs = sys.argv[1], sys.argv[2], float(sys.argv[3]), int(sys.argv[4]), sys.argv[5], sys.argv[6:]
if not specs:
    sys.exit("no moments given (t_s:label ...)")
if not os.path.exists(video):
    sys.exit(f"video missing: {video} (make skill-compare-fetch VIDEO=<id>)")
moments = [(float(a.split(':')[0]), a.split(':', 1)[1] if ':' in a else '') for a in specs]
rows = []
tmp = tempfile.mkdtemp()
for t, label in moments:
    vp = os.path.join(tmp, 'v.png')
    subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-ss', str(t), '-i', video, '-frames:v', '1', '-vf', 'scale=640:-2', vp], check=True)
    tick = round((t - t0) * 20)
    mp = os.path.join(mc, f'frame_{round((t - t0) * MC_FPS) + pre:03d}.png')
    v = Image.open(vp).convert('RGB').resize((640, 360))
    m = Image.open(mp).convert('RGB').resize((640, 360)) if os.path.exists(mp) else Image.new('RGB', (640, 360), (60, 0, 60))
    row = Image.new('RGB', (1290, 380), (20, 20, 24))
    row.paste(v, (0, 20))
    row.paste(m, (650, 20))
    ImageDraw.Draw(row).text((4, 4), f'{label}  video {t:.2f}s  |  minecraft tick {tick}', fill=(255, 220, 0))
    rows.append(row)
img = Image.new('RGB', (1290, 380 * len(rows)))
for i, r in enumerate(rows):
    img.paste(r, (0, i * 380))
img.save(out)
print(out)
