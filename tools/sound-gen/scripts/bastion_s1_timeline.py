#!/usr/bin/env python3
"""Timeline checker for bastion-greatsword-s1: places every cue of deliver/sound-cues.yaml
at its tick/pitch/volume (pitch = playback-rate resample), mixes the hit1 and hit2 timelines
(0-52 ticks), prints per-cue placed peak time/level and the per-tick peak envelope,
saves a labelled waveform PNG. Agents cannot listen: this checks timing and relative level only.

Usage: python3 bastion_s1_timeline.py <out_dir>
"""
import os
import subprocess
import sys
import tempfile

import matplotlib
import numpy as np
import yaml
from scipy.io import wavfile

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

SR = 44100
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
CUES = os.path.join(ROOT, "tools/skill-compare/data/bastion-greatsword-s1/deliver/sound-cues.yaml")
SND = os.path.join(ROOT, "content/sounds/weapons")
TICKS = 52
CONTACT = {"hit1": 8, "hit2": 31}  # r9 rig: hit-1 contact skill tick 8


def decode(name):
    with tempfile.TemporaryDirectory() as d:
        w = os.path.join(d, "o.wav")
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", os.path.join(SND, name + ".ogg"),
                        "-ac", "1", "-ar", "44100", "-c:a", "pcm_f32le", w], check=True)
        return wavfile.read(w)[1].astype(np.float64)


def place(buf, x, tick, vol, pitch):
    n = int(len(x) / pitch)
    y = np.interp(np.arange(n) * pitch, np.arange(len(x)), x) * vol
    s = int(tick * 0.05 * SR)
    e = min(len(buf), s + n)
    buf[s:e] += y[: e - s]
    return s, y


def db(v):
    return 20 * np.log10(max(v, 1e-9))


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    cues = yaml.safe_load(open(CUES))["cues"]
    n = int((TICKS + 12) * 0.05 * SR)
    mix = {"hit1": np.zeros(n), "hit2": np.zeros(n)}
    report = []
    for c in cues:
        x = decode(c["file"])
        for h in ("hit1", "hit2"):
            if h not in c:
                continue
            p = c[h]
            s, y = place(mix[h], x, p["tick"], p["volume"], p["pitch"])
            pk = int(np.argmax(np.abs(y)))
            report.append((h, c["file"], (s + pk) / SR / 0.05, db(np.abs(y).max()), c["layer"]))
    lines = []
    for h in ("hit1", "hit2"):
        imp = max(r[3] for r in report if r[0] == h and r[1].endswith(("impact-steel", "cut-thud")))
        lines.append(f"== {h} (contact tick {CONTACT[h]}); placed peaks, rel = dB vs loudest impact-layer cue ({imp:.1f} dBFS) ==")
        for r in report:
            if r[0] == h:
                lines.append(f"  {r[1]:28s} peak at tick {r[2]:5.2f}  {r[3]:6.1f} dBFS  rel {r[3]-imp:+5.1f} dB  [{r[4]}]")
        m = mix[h]
        lines.append(f"  mixed timeline peak {db(np.abs(m).max()):.1f} dBFS at tick {np.argmax(np.abs(m))/SR/0.05:.2f}")
        env = [db(np.abs(m[int(t * 0.05 * SR):int((t + 1) * 0.05 * SR)]).max()) for t in range(TICKS)]
        lines.append("  per-tick peak dBFS: " + " ".join(f"{t}:{env[t]:.0f}" for t in range(TICKS)))
    pk1 = db(np.abs(mix["hit1"][int(6.5 * 0.05 * SR):int(9 * 0.05 * SR)]).max())
    pk2 = db(np.abs(mix["hit2"][int(30.5 * 0.05 * SR):int(33 * 0.05 * SR)]).max())
    lines.append(f"contact-window peak hit1 {pk1:.1f} dBFS, hit2 {pk2:.1f} dBFS, hit2-hit1 = {pk2-pk1:+.1f} dB")
    open(os.path.join(out, "timeline-report.txt"), "w").write("\n".join(lines) + "\n")
    print("\n".join(l for l in lines if "per-tick" not in l))
    fig, axs = plt.subplots(2, 1, figsize=(15, 7))
    for ax, h in zip(axs, ("hit1", "hit2")):
        m = mix[h]
        ax.plot(np.arange(len(m)) / SR / 0.05, m, lw=0.4, color="k")
        for t in range(0, TICKS + 1):
            ax.axvline(t, color="#ddd", lw=0.4)
        ax.axvline(CONTACT[h], color="r", lw=1.5, label=f"contact tick {CONTACT[h]}")
        for c in cues:
            if h in c:
                ax.text(c[h]["tick"], 0.95, c["file"].replace("bastion-s1-", ""), rotation=90, fontsize=6, va="top", color="b")
        ax.set_xlim(0, TICKS)
        ax.set_ylim(-1, 1)
        ax.set_xlabel("skill tick")
        ax.set_title(f"{h} timeline (mix of placed cues)")
        ax.legend(loc="upper right")
    plt.tight_layout()
    plt.savefig(os.path.join(out, "timeline.png"), dpi=90)


if __name__ == "__main__":
    main()
