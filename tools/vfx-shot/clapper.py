"""Measure the frame <-> server tick mapping of one recording from its tick clapperboard.

usage: python3 -I clapper.py <rec.mkv> <showinfo.log> <director.stderr> <cast_stamp> <pre_ticks> <out.json>

The server's clapper datapack (tools/vfx-shot/clapper-pack) shows `game time mod 40` as the fill of a red boss bar. The
recording starts and ends with the HUD visible, so its first and last seconds contain the bar. Every bar frame gives
(frame wall time, server game time mod 40); unwrapping and fitting G(t) = rate * t + g0 over both windows gives the
video time at which any server tick is on screen. The cast tick comes from the game-time marks director.py read on the
same RCON connection right before and right after the cast.

Prints `OFFSET=<s>` (seconds into the recording at which frame 0, = cast tick - pre_ticks, is shown) plus a summary
line per measure, and writes <out.json>. Exit 1 when the bar cannot be read (no HUD frames, fit does not hold).
"""
import json
import re
import subprocess
import sys

import numpy as np

MOD = 40.0
BAND = 64            # top rows searched for the bar
BRIGHT_R = 150       # fill colour (236,53,0) vs background (73,16,0)


def first_frame_epoch(log_path):
    """Epoch seconds of the first recorded frame (showinfo pts is wall-clock microseconds; the mkv itself is rebased to 0)."""
    m = re.search(r" pts:\s*(\d+)", open(log_path, errors="replace").read())
    return int(m.group(1)) / 1e6


def read_bar_values(rec, width):
    """Per decoded frame: (seconds since the first frame, bar value in ticks 0..40 or None when no bar is visible)."""
    cmd = ["ffmpeg", "-nostdin", "-loglevel", "info", "-i", rec, "-vf", f"showinfo,crop={width}:{BAND}:0:0",
           "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
    run = subprocess.run(cmd, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    raw = run.stdout
    rel = [float(m) for m in re.findall(r"pts_time:\s*([0-9.]+)", run.stderr.decode(errors="replace"))]
    frame_bytes = width * BAND * 3
    n = min(len(raw) // frame_bytes, len(rel))
    out = []
    for i in range(n):
        img = np.frombuffer(raw, np.uint8, frame_bytes, i * frame_bytes).reshape(BAND, width, 3).astype(int)
        r, g, b = img[..., 0], img[..., 1], img[..., 2]
        # bar palette: blue 0, green about 0.22 * red, red >= 30 (sky, concrete and sprites fail one of these)
        mask = (b < 8) & (r >= 30) & (g * 3.2 <= r + 8)
        counts = mask.sum(axis=1)
        if counts.max() < width * 0.25:
            out.append((rel[i], None))
            continue
        row = int(np.argmax(counts))          # first row with the longest run: the bar's bright top band
        xs = np.nonzero(mask[row])[0]
        left, right = int(xs.min()), int(xs.max())
        total = right - left + 1
        bright = np.nonzero(mask[row] & (r[row] >= BRIGHT_R))[0]
        fill = 0 if len(bright) == 0 else int(bright.max()) - left + 1
        out.append((rel[i], MOD * fill / total))
    return out


def unwrap(times, values):
    """Sequential unwrap inside each window (gap > 0.5 s starts a new window). Returns [(window, t, v)]."""
    out, window, prev_t, prev_v, add = [], 0, None, None, 0.0
    for t, v in zip(times, values):
        if v is None:
            continue
        if prev_t is not None and t - prev_t > 0.5:
            window, add, prev_v = window + 1, 0.0, None
        if prev_v is not None and v + add < prev_v - MOD / 2:
            add += MOD
        out.append((window, t, v + add))
        prev_t, prev_v = t, v + add
    return out


def cut_frame_times(rec):
    """(seconds since the first frame, mean abs difference to the previous frame) per frame, from a small grey decode."""
    cmd = ["ffmpeg", "-nostdin", "-loglevel", "info", "-i", rec, "-vf", "showinfo,scale=160:90", "-fps_mode", "passthrough",
           "-f", "rawvideo", "-pix_fmt", "gray", "-"]
    run = subprocess.run(cmd, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    rel = [float(m) for m in re.findall(r"pts_time:\s*([0-9.]+)", run.stderr.decode(errors="replace"))]
    n = min(len(run.stdout) // (160 * 90), len(rel))
    frames = np.frombuffer(run.stdout, np.uint8, n * 160 * 90).reshape(n, 90, 160).astype(int)
    return [(rel[i], float(np.abs(frames[i] - frames[i - 1]).mean())) for i in range(1, n)]


def cut_check(rec, dstderr, to_video_time, first):
    """Camera hard cuts are real world changes whose server tick is known (CUT_MARK = game time right before the tp):
    compare the frame in which each cut appears with the bar's prediction for that tick."""
    text = open(dstderr).read()
    marks = [(int(m[1]), int(m[3])) for m in re.finditer(r"^CUT_MARK (\d+) ([0-9.]+) .*?(-?\d+)\D*$", text, re.M)]
    marks = [m for m in marks if m[0] > 0]            # the t=0 cut coincides with the cast frame itself
    if not marks:
        return None
    diffs = cut_frame_times(rec)
    errors = []
    for _t_ms, g in marks:
        predicted = to_video_time(g)
        window = [(t, d) for t, d in diffs if predicted - 0.30 <= t <= predicted + 0.30 and d > 3.0]
        if window:
            seen = max(window, key=lambda x: x[1])[0]
            errors.append(round((seen - predicted) * 1000.0, 1))
    return {"cuts": len(marks), "matched": len(errors), "cutMinusBarMs": errors} if errors else {"cuts": len(marks), "matched": 0}


def parse_marks(path):
    text = open(path).read()
    mark = lambda tag: re.search(rf"^{tag} ([0-9.]+) .*?(-?\d+)\D*$", text, re.M)
    before, after = mark("MARK_BEFORE"), mark("MARK_AFTER")
    if not (before and after):
        raise SystemExit("director.stderr has no MARK_BEFORE/MARK_AFTER (is the clapper datapack missing?)")
    return (float(before[1]), int(before[2])), (float(after[1]), int(after[2]))


def main():
    rec, log_path, dstderr, cast_stamp, pre_ticks, out_path = sys.argv[1:7]
    cast_stamp, pre_ticks = float(cast_stamp), int(pre_ticks)
    (t_b, g_b), (t_a, g_a) = parse_marks(dstderr)
    first = first_frame_epoch(log_path)
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width",
                            "-of", "csv=p=0", rec], check=True, capture_output=True, text=True).stdout.strip()
    bars = read_bar_values(rec, int(probe))
    wall = [first + t for t, _ in bars]
    values = [v for _, v in bars]
    raw = [(t, v) for t, v in zip(wall, values) if v is not None]
    if len(raw) < 8:
        raise SystemExit(f"only {len(raw)} frames show the clapperboard")
    # Absolute game time per sample: the multiple of 40 nearest to a running estimate. The first estimate is the wall-clock
    # prediction from the marks (good to well under 20 ticks); each pass refits and re-unwraps against the fit.
    g_pred = lambda t: g_b + (t - t_b) * 20.0
    t_all = np.array([t for t, _ in raw])
    v_all = np.array([v for _, v in raw])
    est = np.array([g_pred(t) for t in t_all])
    for _ in range(3):
        g_all = v_all + np.round((est - v_all) / MOD) * MOD
        rate0, g00 = np.polyfit(t_all - t_all[0], g_all, 1)
        est = rate0 * (t_all - t_all[0]) + g00
    ts, gs = t_all, v_all + np.round((est - v_all) / MOD) * MOD
    by_window = {}
    window = 0
    for i, t in enumerate(ts):
        if i and t - ts[i - 1] > 0.5:
            window += 1
        by_window.setdefault(window, []).append((t, gs[i]))
    # The client draws in bursts under software GL, so a frame can show a state a few ticks old (a sample then lies BELOW
    # the line). Fit, drop samples further than 3 MAD (at least 2 ticks) from the line, refit; the dropped count is reported.
    keep = np.ones(len(ts), bool)
    for _ in range(4):
        rate, g0 = np.polyfit(ts[keep] - ts[0], gs[keep], 1)
        resid_all = gs - (rate * (ts - ts[0]) + g0)
        mad = float(np.median(np.abs(resid_all[keep] - np.median(resid_all[keep]))))
        new_keep = np.abs(resid_all) <= max(2.0, 3.0 * 1.4826 * mad)
        if new_keep.sum() < 8 or (new_keep == keep).all():
            break
        keep = new_keep
    resid = resid_all[keep]
    dropped = int((~keep).sum())
    g_cast = (g_b + g_a) / 2.0
    to_video_time = lambda g: float(ts[0] - first + (g - g0) / rate)
    cuts = cut_check(rec, dstderr, to_video_time, first)
    # The vanilla boss bar eases toward each new value over 100 ms (LerpingBossEvent), so the bar reads that much later
    # than the world. The camera hard cuts are real world changes with known ticks: when they matched, their median
    # difference IS the correction; otherwise the vanilla 100 ms is applied.
    lerp_ms = -100.0
    if cuts and cuts.get("matched", 0) >= 2:
        lerp_ms = float(np.median(cuts["cutMinusBarMs"]))
    bar_lag_ms = (ts[0] + (g_cast - g0) / rate - cast_stamp) * 1000.0
    lag_ms = bar_lag_ms + lerp_ms                          # world change of the cast tick -> frame showing it
    t_cast = cast_stamp + lag_ms / 1000.0                  # epoch time of the frame that shows the cast tick
    offset = t_cast - first - pre_ticks / 20.0
    ok = abs(rate - 20.0) < 1.0 and float(np.abs(resid).max()) <= 2.0 and len(resid) >= 8
    result = {
        "castTick": g_cast, "castTickSpan": [g_b, g_a], "castTickExact": g_b == g_a,
        "measuredCastLagMs": round(lag_ms, 1), "barLagMs": round(bar_lag_ms, 1), "barLerpCorrectionMs": lerp_ms, "tickRateHz": round(float(rate), 3),
        "maxResidualTicks": round(float(np.abs(resid).max()), 2), "samples": int(len(resid)), "droppedSamples": dropped,
        "windows": {str(w): [round(items[0][0] - first, 3), round(items[-1][0] - first, 3)] for w, items in by_window.items()},
        "offsetSeconds": round(offset, 4), "fitHolds": bool(ok),
        "barSamples": [[round(float(t - first), 3), round(float(g), 2), bool(k)] for t, g, k in zip(ts, gs, keep)],
    }
    result["cutCheck"] = cuts
    json.dump(result, open(out_path, "w"), indent=2)
    print(f"cast tick {g_cast:g}{'' if g_b == g_a else f' (+-0.5, span {g_b}..{g_a})'}, measured lag {lag_ms:.0f} ms, "
          f"rate {rate:.2f} Hz, residual <= {np.abs(resid).max():.2f} ticks over {len(resid)} frames ({dropped} lagging frames dropped)")
    print(f"OFFSET={offset:.4f}")
    if not ok:
        raise SystemExit("clapper fit does not hold (tick rate or residual out of range)")


main()
