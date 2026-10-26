#!/usr/bin/env python3
"""Evidence for the S1 sound round: spectrogram + waveform pairs (reference vs ours) and a feature table.

  python3 bastion_s1_sound_evidence.py <ref_clip.wav> <ref_offset_s> <ours_padded.wav> <ours_offset_s> <ref_feat.json> <ours_feat.json> <out_dir>

Images: spec-full.png (whole 2.6 s), spec-hit1.png / spec-hit2.png (zoom per coin), wave-hit1.png / wave-hit2.png.
Table: feature-table.md (ref / ours / delta per event). The reference is analysis input only; only plots and numbers are written.
"""
import json
import os
import sys

import matplotlib
import numpy as np
from scipy.io import wavfile
from scipy.signal import stft

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

ref_wav, ref_off, our_wav, our_off, ref_js, our_js, out = sys.argv[1:8]
ref_off, our_off = float(ref_off), float(our_off)
os.makedirs(out, exist_ok=True)


def mono(p):
    sr, x = wavfile.read(p)
    x = x.astype(np.float64) / (32768.0 if x.dtype.kind == "i" else 1.0)
    return sr, (x.mean(1) if x.ndim > 1 else x)


def spec(ax, sr, x, off, t0, t1, title):
    f, t, Z = stft(x, sr, nperseg=1024, noverlap=1024 - 128)
    S = 20 * np.log10(np.abs(Z) + 1e-7)
    ax.pcolormesh((t - off) / 0.05, f, S, vmin=-110, vmax=-40, shading="auto", cmap="magma")
    ax.set_yscale("symlog", linthresh=500)
    ax.set_ylim(80, 20000)
    ax.set_xlim(t0, t1)
    ax.set_title(title, fontsize=9)
    ax.set_xlabel("skill tick")


def wave(ax, sr, x, off, t0, t1, title):
    t = np.arange(len(x)) / sr - off
    m = (t >= t0 * 0.05) & (t <= t1 * 0.05)
    ax.plot(t[m] / 0.05, x[m], lw=0.5)
    ax.set_title(title, fontsize=9)
    ax.set_xlim(t0, t1)
    ax.set_xlabel("skill tick")


sr1, r = mono(ref_wav)
sr2, o = mono(our_wav)
r = r / np.abs(r).max()
for name, t0, t1 in (("full", -1, 52), ("hit1", -1, 13), ("hit2", 21, 44)):
    fig, ax = plt.subplots(2, 1, figsize=(11, 6), sharex=True)
    spec(ax[0], sr1, r, ref_off, t0, t1, "reference (music + voice underneath; HF events are reliable)")
    spec(ax[1], sr2, o, our_off, t0, t1, "ours")
    for a in ax:
        for k in range(int(t0), int(t1) + 1):
            if k % 5 == 0:
                a.axvline(k, color="c", lw=0.4, alpha=0.6)
    plt.tight_layout()
    plt.savefig(os.path.join(out, f"spec-{name}.png"), dpi=70)
    plt.close()
for name, t0, t1 in (("hit1", -1, 13), ("hit2", 21, 44)):
    fig, ax = plt.subplots(2, 1, figsize=(11, 4), sharex=True)
    wave(ax[0], sr1, r, ref_off, t0, t1, "reference waveform (normalised)")
    wave(ax[1], sr2, o / np.abs(o).max(), our_off, t0, t1, "ours waveform (normalised)")
    plt.tight_layout()
    plt.savefig(os.path.join(out, f"wave-{name}.png"), dpi=70)
    plt.close()

R = {e["name"]: e for e in json.load(open(ref_js))}
O = {e["name"]: e for e in json.load(open(our_js))}
b = R["E4 impact-1 body"]["level_db"] - 0, O["E4 impact-1 body"]["level_db"]
rows = ["| event | metric | ref | ours | delta |", "|---|---|---|---|---|"]
for n in R:
    r_, o_ = R[n], O[n]
    for lab, key, fmt in (("onset tick", "onset_tick", "{:.2f}"), ("peak tick", "peak_tick", "{:.2f}"),
                          ("decay -12 dB ms", "decay_ms", "{:.0f}"), ("centroid Hz", "centroid_hz", "{:.0f}")):
        d = o_[key] - r_[key]
        rel = f" ({100 * d / r_[key]:+.0f}%)" if key in ("centroid_hz", "decay_ms") else ""
        rows.append(f"| {n} | {lab} | {fmt.format(r_[key])} | {fmt.format(o_[key])} | {d:+.2f}{rel} |")
    lr = r_["level_db"] - R["E4 impact-1 body"]["level_db"]
    lo = o_["level_db"] - O["E4 impact-1 body"]["level_db"]
    rows.append(f"| {n} | level vs E4 dB | {lr:+.1f} | {lo:+.1f} | {lo - lr:+.1f} |")
open(os.path.join(out, "feature-table.md"), "w").write("\n".join(rows) + "\n")
print("\n".join(rows[:2] + rows[2:8]))
