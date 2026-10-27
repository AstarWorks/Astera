"""Frame <-> server tick mapping of one recording, read from the tick stamp the client itself rendered into every frame.

usage: python3 -I framestamp.py <rec.mkv> <showinfo.log> <director.stderr> <cast_stamp> <pre_ticks> <ticks> <fps> <gui_scale> <survival 0|1> <out_dir> <out.json> [stepped 0|1]

stepped=1 (director.py DIRECTOR_SLOW_RATE: the server ran at a low tick rate, every tick stays on screen several frames
until the client had drawn it): ticks are unwrapped against the cast tick (no wall-clock), and the LAST recorded frame of a tick
is taken (the settled one). skippedTicks is then expected to be 0 whatever the render rate.

The server's stamp datapack (tools/vfx-shot/stamp-pack) renders the game time into a boss bar title on every tick: 12 block
glyphs, cell 0 magenta + cell 1 cyan (start marker, calibrates the cell pitch), cells 2..11 = game time mod 1024, LSB
first, magenta 0 / cyan 1. The viewer client's own resource pack (overlay-pack) blanks every other HUD sprite, so the
HUD stays on for the whole recording and EVERY recorded frame carries the tick of the world it shows (the boss bar packet
and the entity packets of a tick are processed in the same client tick). There is no wall-clock fit: client bursts, render
load and tick-rate wobble cannot move a frame to another tick.

Output frame n (fps 20 = 1/tick, 60 = 3/tick) shows stamp  T0 + n * 20 / fps  with  T0 = cast gametime - 1 - pre_ticks:
plan tick a (age a of an effect, `cast + a` in a VfxPlayer trace) is the picture stamped cast gametime + a - 1 (see "Plan tick 0" in main()). It is taken
from the recorded frames that carry stamp floor(T) (the k-th third of them at 60 fps); a tick the client never drew falls back
to the nearest stamped frame and is counted in `skippedTicks`. The stamp rectangle (and, for a survival viewer, the hotbar)
is overwritten by a mirror of the rows next to it in the saved PNGs.

Writes frame_%03d.png into <out_dir> and <out.json>; prints one summary line. Exit 1 when the stamp cannot be read.
"""
import json
import os
import re
import subprocess
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I does not add the script dir
from stampcodec import MOD, CELLS, STAMP_HALF_W, STAMP_H, MASK_HALF_W, MASK_TOP_H, MASK_BOTTOM_H, decode_stamp


def parse_marks(path):
    text = open(path).read()
    mark = lambda tag: re.search(rf"^{tag} ([0-9.]+) .*?(-?\d+)\D*$", text, re.M)
    before, after = mark("MARK_BEFORE"), mark("MARK_AFTER")
    if not (before and after):
        raise SystemExit("director.stderr has no MARK_BEFORE/MARK_AFTER (stamp datapack missing?)")
    cuts = [(int(m[1]), int(m[3])) for m in re.finditer(r"^CUT_MARK (\d+) ([0-9.]+) .*?(-?\d+)\D*$", text, re.M)]
    return (float(before[1]), int(before[2])), (float(after[1]), int(after[2])), cuts


def first_frame_epoch(log_path):
    m = re.search(r" pts:\s*(\d+)", open(log_path, errors="replace").read())
    return int(m.group(1)) / 1e6


def stream_frames(rec, w, h, vf=None):
    cmd = ["ffmpeg", "-nostdin", "-loglevel", "info", "-i", rec]
    if vf:
        cmd += ["-vf", f"showinfo,{vf}"]
    else:
        cmd += ["-vf", "showinfo"]
    cmd += ["-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
    return subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def read_all(proc, size):
    """Yield frames of `size` bytes; stderr (showinfo) is collected by a drain thread so the pipes cannot deadlock."""
    import threading
    err = []
    t = threading.Thread(target=lambda: err.append(proc.stderr.read()), daemon=True)
    t.start()
    while True:
        buf = proc.stdout.read(size)
        if len(buf) < size:
            break
        yield buf
    proc.wait()
    t.join()
    yield err[0].decode(errors="replace")


def mirror_fill(img, y0, y1):
    """Overwrite rows [y0, y1) of a full-width slice with the mirror of the rows beyond y1 (top) / before y0 (bottom)."""
    h = img.shape[0]
    if y0 == 0:
        n = y1
        img[0:n] = img[n:2 * n][::-1]
    else:
        n = h - y0
        img[y0:h] = img[y0 - n:y0][::-1]


def main():
    rec, log_path, dstderr, cast_stamp, pre_ticks, ticks, fps, gs, survival, out_dir, out_path = sys.argv[1:12]
    stepped = len(sys.argv) > 12 and sys.argv[12] == "1"
    cast_stamp, pre_ticks, ticks, fps, gs, survival = float(cast_stamp), int(pre_ticks), int(ticks), int(fps), int(gs), survival == "1"
    (t_b, g_b), (t_a, g_a), cut_marks = parse_marks(dstderr)
    first = first_frame_epoch(log_path)
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                            "-of", "csv=p=0", rec], check=True, capture_output=True, text=True).stdout.strip().split(",")
    W, H = int(probe[0]), int(probe[1])

    # ---- pass 1: the stamp of every recorded frame (decode only the top-centre crop)
    sx0 = max(0, W // 2 - STAMP_HALF_W * gs)
    sw = min(W - sx0, 2 * STAMP_HALF_W * gs)
    sh = STAMP_H * gs
    proc = stream_frames(rec, sw, sh, f"crop={sw}:{sh}:{sx0}:0")
    raw = []
    for item in read_all(proc, sw * sh * 3):
        if isinstance(item, str):
            rel = [float(x) for x in re.findall(r"pts_time:\s*([0-9.]+)", item)]
        else:
            raw.append(decode_stamp(np.frombuffer(item, np.uint8).reshape(sh, sw, 3)))
    n_frames = min(len(raw), len(rel))
    raw, rel = raw[:n_frames], rel[:n_frames]
    wall = np.array([first + t for t in rel])

    # absolute game time: the multiple of 1024 nearest to the wall-clock prediction from the RCON marks (good to a few
    # ticks over the whole recording, 512 ticks away from any ambiguity)
    pred = (lambda t: g_b) if stepped else (lambda t: g_b + (t - t_b) * 20.0)
    stamp = [None if v is None else int(v + MOD * round((pred(wall[i]) - v) / MOD)) for i, v in enumerate(raw)]
    undecoded = sum(v is None for v in stamp)
    known = [i for i, v in enumerate(stamp) if v is not None]
    if len(known) < 20:
        raise SystemExit(f"only {len(known)} of {n_frames} frames carry a readable tick stamp")
    # drop decode glitches: a stamp further than 4 ticks from the median of its neighbours
    glitches = 0
    vals = np.array([stamp[i] for i in known], float)
    for k, i in enumerate(known):
        lo, hi = max(0, k - 5), min(len(known), k + 6)
        if abs(vals[k] - np.median(vals[lo:hi])) > 4:
            stamp[i] = None
            glitches += 1
    known = [i for i, v in enumerate(stamp) if v is not None]
    # packets are processed in order: a stamp can never go back
    regressions = sum(1 for a, b in zip(known, known[1:]) if stamp[b] < stamp[a])
    kt = np.array([wall[i] for i in known]); kg = np.array([stamp[i] for i in known], float)
    rate = None if stepped else float(np.polyfit(kt - kt[0], kg, 1)[0])

    g_cast = g_b if g_b == g_a else (g_b + g_a) / 2.0
    # Plan tick 0 is the cast. The cast command runs on the RCON connection BETWEEN two server ticks: `time query gametime`
    # already returns the value the NEXT tick's stamp function will write (g_cast), but the effects the cast spawns (age 0)
    # reach the client before that stamp does. The plugin scheduler then advances every effect one age per tick in the
    # heartbeat, which runs BEFORE the stamp function of the same tick: age a spawns in the tick stamped g_cast + a - 1.
    # So plan tick a is the picture stamped g_cast + a - 1 (frame 0 = the last picture of stamp g_cast - 1); labelling it
    # g_cast + a showed every effect one tick early.
    base = g_cast - 1
    t0 = base - pre_ticks
    frames_total = (ticks + pre_ticks + 1) * fps // 20
    by_tick = {}
    for i in known:
        by_tick.setdefault(stamp[i], []).append(i)
    ticks_sorted = sorted(by_tick)

    mapping, skipped = [], 0
    for n in range(frames_total):
        t = t0 + n * 20.0 / fps
        k = int(np.floor(t + 1e-9))
        group = by_tick.get(k)
        exact = group is not None
        if not exact:
            k = min(ticks_sorted, key=lambda s: (abs(s - t), s))
            group = by_tick[k]
            skipped += 1
        frac = t - np.floor(t + 1e-9) if exact else 0.0
        src = group[-1] if stepped else group[min(int(frac * len(group)), len(group) - 1)]
        mapping.append({"n": n, "tick": round(t - base, 3), "src": src, "stamp": stamp[src], "exact": exact})
    skipped_ticks = len({int(np.floor(t0 + n * 20.0 / fps + 1e-9)) for n, m in enumerate(mapping) if not m["exact"]})

    # ---- pass 2: full frames -> masked PNGs + a small grey copy of every frame for the change trace
    need = {m["src"] for m in mapping}
    src_to_n = {}
    for m in mapping:
        src_to_n.setdefault(m["src"], []).append(m["n"])
    gw, gh = 160, 90
    proc = stream_frames(rec, W, H)
    grey = []
    idx = 0
    for item in read_all(proc, W * H * 3):
        if isinstance(item, str):
            break
        img = np.frombuffer(item, np.uint8).reshape(H, W, 3)
        small = img[:H // 9 * 9, :W // 8 * 8].reshape(gh, (H // 9 * 9) // gh, gw, (W // 8 * 8) // gw, 3).mean(axis=(1, 3, 4))
        top = (slice(0, MASK_TOP_H * gs), slice(W // 2 - MASK_HALF_W * gs, W // 2 + MASK_HALF_W * gs))
        small[:int(np.ceil(MASK_TOP_H * gs * gh / H)), int(gw * (W / 2 - MASK_HALF_W * gs) / W):int(np.ceil(gw * (W / 2 + MASK_HALF_W * gs) / W))] = 0
        if survival:
            small[int(gh * (H - MASK_BOTTOM_H * gs) / H):, int(gw * (W / 2 - MASK_HALF_W * gs) / W):int(np.ceil(gw * (W / 2 + MASK_HALF_W * gs) / W))] = 0
        grey.append(small)
        if idx in need:
            out = img.copy()
            band = out[:, W // 2 - MASK_HALF_W * gs:W // 2 + MASK_HALF_W * gs]
            mirror_fill(band[:MASK_TOP_H * gs * 2], 0, MASK_TOP_H * gs)
            if survival:
                mirror_fill(band[H - MASK_BOTTOM_H * gs * 2:], MASK_BOTTOM_H * gs, MASK_BOTTOM_H * gs * 2)
            for n in src_to_n[idx]:
                Image.fromarray(out).save(f"{out_dir}/frame_{n:03d}.png", compress_level=3)
        idx += 1
    grey = grey[:n_frames]
    diffs = [0.0] + [float(np.abs(grey[i] - grey[i - 1]).mean()) for i in range(1, len(grey))]

    # ---- change trace: first visible change after the cast, in ticks after the cast tick
    pre = [diffs[i] for i in known if stamp[i] is not None and stamp[i] <= base - 1 and i > 0]
    noise = max(pre) if pre else 0.0
    thr = max(0.05, 3.0 * noise)
    post = [i for i in known if stamp[i] >= np.floor(base) and diffs[i] > thr]
    cut_ticks = [g for _t, g in cut_marks]
    events = [{"tick": stamp[i] - base, "diff": round(diffs[i], 3), "t": round(rel[i], 3)} for i in post[:12]]
    fx = [e for e in events if not any(-1 <= e["tick"] + base - c <= 3 for c in cut_ticks)]
    # cross-check with the camera hard cuts (real world changes with a known server tick)
    cut_check = []
    for t_ms, g in cut_marks:
        if t_ms <= 0:
            continue
        win = [i for i in known if g - 10 <= stamp[i] <= g + 12]
        if win:
            peak = max(win, key=lambda i: diffs[i])
            if diffs[peak] > 3.0:
                cut_check.append({"cutMs": t_ms, "markTick": g, "stampOfChangeFrame": stamp[peak], "deltaTicks": stamp[peak] - g})

    t_stamp_cast = None
    near = by_tick.get(int(np.floor(g_cast)))
    if near:
        t_stamp_cast = float(wall[near[0]])
    result = {
        "castTick": g_cast, "planTickZeroStamp": base, "castTickSpan": [g_b, g_a], "castTickExact": g_b == g_a,
        "firstFrameTick": t0, "frames": frames_total, "fps": fps,
        "recordedFrames": n_frames, "undecodedFrames": undecoded, "glitchFrames": glitches, "regressions": regressions,
        "skippedTicks": skipped_ticks, "stampRateHz": None if rate is None else round(rate, 3),
        "mode": "stepped" if stepped else "realtime",
        "minFramesPerTick": min(len(by_tick[k]) for k in range(int(t0), int(g_cast) + ticks + 1) if k in by_tick) if stepped else None,
        "castStampToStampedFrameMs": None if (t_stamp_cast is None or stepped) else round((t_stamp_cast - cast_stamp) * 1000.0, 1),
        "changeNoise": round(noise, 4), "changeThreshold": round(thr, 4),
        "firstChangeTickAfterCast": events[0]["tick"] if events else None,
        "firstFxTickAfterCast": fx[0]["tick"] if fx else None,
        "changeEvents": events, "cutCheck": cut_check, "mask": {"top": True, "bottom": survival, "guiScale": gs},
        "map": mapping,
    }
    json.dump(result, open(out_path, "w"), indent=1)
    print(f"cast tick {g_cast:g}{'' if g_b == g_a else f' (span {g_b}..{g_a})'}, {len(known)}/{n_frames} frames stamped "
          f"({undecoded} unreadable, {glitches} glitches, {regressions} regressions), {skipped_ticks} ticks never drawn, "
          f"first change +{result['firstChangeTickAfterCast']} ticks, first fx +{result['firstFxTickAfterCast']} ticks")


main()
