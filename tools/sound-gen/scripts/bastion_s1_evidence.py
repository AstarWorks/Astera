#!/usr/bin/env python3
"""Evidence for bastion-s1 SFX: ffprobe format, volumedetect, ebur128 true peak, DC, sizes,
and one contact sheet PNG (waveform + spectrogram per file) per cue.

Usage: python3 bastion_s1_evidence.py <out_dir>
"""
import glob
import os
import re
import subprocess
import sys
import tempfile

import matplotlib
import numpy as np
from scipy.io import wavfile
from scipy.signal import spectrogram

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
files = sorted(glob.glob(os.path.join(ROOT, "content/sounds/weapons/bastion-s1-*.ogg")))
out = sys.argv[1]
os.makedirs(out, exist_ok=True)
lines = []
fig, axs = plt.subplots(len(files), 2, figsize=(14, 2.1 * len(files)))
for i, f in enumerate(files):
    n = os.path.basename(f)[:-4]
    pr = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_name,channels,sample_rate:format=duration", "-of", "default=nw=1", f], capture_output=True, text=True).stdout.split()
    vd = subprocess.run(["ffmpeg", "-nostats", "-i", f, "-af", "volumedetect,ebur128=peak=true", "-f", "null", "-"], capture_output=True, text=True).stderr
    mx = re.search(r"max_volume: (\S+) dB", vd).group(1)
    mean = re.search(r"mean_volume: (\S+) dB", vd).group(1)
    integ = re.findall(r"I:\s+(\S+) LUFS", vd)[-1]
    tp = re.findall(r"Peak:\s+(\S+) dBFS", vd)[-1]
    with tempfile.TemporaryDirectory() as d:
        w = os.path.join(d, "o.wav")
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", f, "-c:a", "pcm_f32le", w], check=True)
        sr, x = wavfile.read(w)
    pk_ms = np.argmax(np.abs(x)) / sr * 1000
    head_sil = np.argmax(np.abs(x) > 10 ** (-60 / 20) * np.abs(x).max()) / sr * 1000
    tail = len(x) - 1 - np.max(np.nonzero(np.abs(x) > 10 ** (-50 / 20) * np.abs(x).max())[0])
    lines.append(f"{n}: {' '.join(pr)} size={os.path.getsize(f)}B max={mx}dB mean={mean}dB I={integ}LUFS truepeak={tp}dBFS peak@{pk_ms:.0f}ms dc={x.mean():.5f} lead-silence={head_sil:.1f}ms tail-below-50dB={tail/sr*1000:.0f}ms")
    t = np.arange(len(x)) / sr
    axs[i, 0].plot(t * 1000, x, lw=0.5, color="k")
    axs[i, 0].axvline(pk_ms, color="r", lw=0.8)
    axs[i, 0].set_ylabel(n.replace("bastion-s1-", ""), fontsize=8)
    axs[i, 0].set_ylim(-1, 1)
    fr, tt, S = spectrogram(x, sr, nperseg=512, noverlap=448)
    axs[i, 1].pcolormesh(tt * 1000, fr, 10 * np.log10(S + 1e-12), vmin=-110, vmax=-30, shading="auto")
    axs[i, 1].set_yscale("symlog", linthresh=500)
    axs[i, 1].set_ylim(30, 16000)
axs[-1, 0].set_xlabel("ms (red = peak)")
axs[-1, 1].set_xlabel("ms (spectrogram, log freq)")
plt.tight_layout()
plt.savefig(os.path.join(out, "cues-wave-spectro.png"), dpi=70)
open(os.path.join(out, "ffprobe-and-levels.txt"), "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
