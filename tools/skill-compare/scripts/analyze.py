#!/usr/bin/env python3
"""Audio waveform + onsets + scene cuts for one case range (port of the segmentation timeline script).

usage: analyze.py <video.mp4> <out.json> <start_s> <end_s>

Writes analysis.json:
  waveformStepMs  - ms per waveform bucket (10)
  waveform        - 0..1 peak per bucket, from start_s
  onsets          - [{t, s}] percussive onsets (absolute video seconds, flux strength) from spectral flux of the >1.5 kHz
                    band (hit SFX are bright transients; music sits lower)
  cuts            - [{t, s}] scene cuts (absolute video seconds) from ffmpeg scdet
"""
import json, os, subprocess, sys, tempfile, wave
import numpy as np

video, out = sys.argv[1], sys.argv[2]
start, end = float(sys.argv[3]), float(sys.argv[4])
rng = ["-ss", str(start), "-to", str(end)]

has_audio = bool(json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=index", "-of", "json", video]))["streams"])
sr, x = 22050, np.zeros(0, dtype=np.float32)
if has_audio:
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "a.wav")
        subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", *rng, "-i", video, "-vn", "-ac", "1", "-ar", "22050", wav], check=True)
        with wave.open(wav) as w:
            sr = w.getframerate()
            x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768

# --- waveform ---------------------------------------------------------------
step = int(sr * 0.010)
nb = len(x) // step
peaks = np.abs(x[: nb * step]).reshape(nb, step).max(axis=1) if nb else np.zeros(0)
peaks = peaks / (peaks.max() or 1) if len(peaks) else peaks

# --- onsets -----------------------------------------------------------------
hop, win = 256, 1024
onsets = []
if len(x) > win * 4:
    n = max(1, (len(x) - win) // hop)
    frames = np.lib.stride_tricks.sliding_window_view(x, win)[::hop][:n] * np.hanning(win)
    spec = np.abs(np.fft.rfft(frames, axis=1))
    freqs = np.fft.rfftfreq(win, 1 / sr)
    band = spec[:, freqs > 1500]
    flux = np.maximum(0, np.diff(np.log1p(band), axis=0)).sum(axis=1)
    flux = np.concatenate([[0], flux])
    k = 9
    med = np.convolve(flux, np.ones(k) / k, mode="same")
    score = flux - med
    thr = np.percentile(score, 97)
    t_of = lambda i: start + i * hop / sr
    last = -1e9
    for i in range(1, len(score) - 1):
        if score[i] > thr and score[i] >= score[i - 1] and score[i] >= score[i + 1] and t_of(i) - last > 0.07:
            onsets.append({"t": round(t_of(i), 3), "s": round(float(score[i]), 2)})
            last = t_of(i)

# --- scene cuts -------------------------------------------------------------
p = subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", *rng, "-i", video, "-vf", "scdet=threshold=12", "-an", "-f", "null", "-"],
                   capture_output=True, text=True)
cuts = []
for line in p.stderr.splitlines():
    if "lavfi.scd.time" in line:
        parts = dict(kv.split(": ") for kv in line.split("] ")[-1].split(", ") if ": " in kv)
        cuts.append({"t": round(start + float(parts["lavfi.scd.time"]), 3), "s": round(float(parts["lavfi.scd.score"]), 1)})

with open(out, "w") as f:
    json.dump({"start_s": start, "end_s": end, "waveformStepMs": 10, "waveform": [round(float(v), 3) for v in peaks],
               "onsets": onsets, "cuts": cuts}, f, separators=(",", ":"))
print(f"analysis: {len(peaks)} waveform buckets, {len(onsets)} onsets, {len(cuts)} cuts")
