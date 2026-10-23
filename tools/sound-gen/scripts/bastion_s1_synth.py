#!/usr/bin/env python3
"""bastion-greatsword S1 SFX: free procedural synthesis (numpy + scipy + ffmpeg).

No API key, no network, no paid service (fal.ai route is forbidden by founder
policy). Deterministic: every cue has a fixed seed list in
tools/sound-gen/specs/bastion-s1.yaml; re-running reproduces the OGGs bit-for-bit
up to the Vorbis encoder version.

Usage:  python3 tools/sound-gen/scripts/bastion_s1_synth.py [outdir_for_wav]
Writes: content/sounds/weapons/bastion-s1-*.ogg  (mono, 44.1 kHz, Vorbis -q:a 5)

Loudness: NO loudnorm. Each cue is peak-normalised to the per-cue peak in
briefs/sound.md, encoded, decoded again and re-trimmed once so the DECODED peak
matches the target within 0.1 dB (Vorbis can overshoot the float peak).
"""
import os
import subprocess
import sys
import tempfile

import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, sosfilt

SR = 44100
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "content", "sounds", "weapons")


def T(n_s):
    return np.arange(int(round(n_s * SR))) / SR


def rng(seed):
    return np.random.default_rng(seed)


def bp(x, lo, hi, order=2):
    return sosfilt(butter(order, [lo, hi], btype="band", fs=SR, output="sos"), x)


def lp(x, fc, order=2):
    return sosfilt(butter(order, fc, btype="low", fs=SR, output="sos"), x)


def hp(x, fc, order=2):
    return sosfilt(butter(order, fc, btype="high", fs=SR, output="sos"), x)


def swept_bp(x, fc, q=2.5):
    """Time-varying band-pass (Chamberlin state-variable filter). fc: per-sample Hz array."""
    f = 2 * np.sin(np.pi * np.clip(fc, 20, SR / 6) / SR)
    d = 1.0 / q
    low = band = 0.0
    out = np.empty_like(x)
    for i in range(len(x)):
        low += f[i] * band
        high = x[i] - low - d * band
        band += f[i] * high
        out[i] = band
    return out


def env_ar(n, a, d_tau, t=None):
    t = T(n) if t is None else t
    e = np.where(t < a, 0.5 - 0.5 * np.cos(np.pi * np.clip(t / max(a, 1e-6), 0, 1)), np.exp(-(t - a) / d_tau))
    return e


def modal(t, base, ratios, taus, amps, detune=0.0, r=None):
    y = np.zeros_like(t)
    for k, (ra, ta, am) in enumerate(zip(ratios, taus, amps)):
        f = base * ra
        y += am * np.exp(-t / ta) * np.sin(2 * np.pi * f * t + (0 if r is None else r.uniform(0, 6.28)))
        if detune:
            y += 0.6 * am * np.exp(-t / ta) * np.sin(2 * np.pi * f * (1 + detune) * t)
    return y


def allpass(x, delay_s, g):
    d = int(delay_s * SR)
    y = np.zeros_like(x)
    buf = np.zeros(len(x) + d)
    buf[: len(x)] = x
    for i in range(len(x)):
        xd = buf[i - d] if i >= d else 0.0
        yd = y[i - d] if i >= d else 0.0
        y[i] = -g * x[i] + xd + g * yd
    return y


def comb(x, delay_s, g):
    d = int(delay_s * SR)
    y = x.copy()
    for i in range(d, len(x)):
        y[i] += g * y[i - d]
    return y


def fade(x, fin=0.001, fout=0.010):
    n = len(x)
    a = int(fin * SR)
    b = int(fout * SR)
    if a:
        x[:a] *= np.linspace(0, 1, a)
    if b:
        x[-b:] *= np.linspace(1, 0, b)
    return x


# ---------------------------------------------------------------- cues ----
def step_scuff(seed):
    r = rng(seed)
    t = T(0.35)
    n = r.standard_normal(len(t))
    # boot scrape: bandpassed noise with a rough amplitude texture, swells to 40 ms then dies
    scrape = bp(n, 500, 3000) * env_ar(0.35, 0.040, 0.075, t) * (0.6 + 0.4 * lp(r.standard_normal(len(t)), 90) * 6).clip(0.2, 1.4)
    # chest-plate / mail rattle: dense tiny metal ticks
    y = scrape * 0.8
    for k in range(9):
        t0 = 0.040 + abs(r.normal(0.07, 0.07)) if k else 0.040
        t0 = min(t0, 0.28)
        f = r.uniform(2200, 5200)
        tt = np.clip(t - t0, 0, None)
        tick = np.exp(-tt / r.uniform(0.006, 0.02)) * np.sin(2 * np.pi * f * tt) * (t >= t0)
        y += tick * (0.55 if k == 0 else r.uniform(0.12, 0.35))
    # cloth swish tail
    y += 0.25 * bp(n, 800, 2500) * env_ar(0.35, 0.10, 0.10, t)
    return y


def blade_lift(seed):
    r = rng(seed)
    t = T(0.30)
    n = r.standard_normal(len(t))
    fc = 150 + 650 * np.clip(t / 0.25, 0, 1) ** 1.5
    scrape = swept_bp(n, fc, q=1.6) * env_ar(0.30, 0.060, 0.075, t)
    low = 0.5 * np.sin(2 * np.pi * (95 - 35 * t / 0.3) * t) * env_ar(0.30, 0.05, 0.09, t)
    tink = 0.35 * (np.exp(-np.clip(t - 0.055, 0, None) / 0.035) * (t >= 0.055)
                   * (np.sin(2 * np.pi * 3900 * t) + 0.6 * np.sin(2 * np.pi * 4850 * t)))
    return scrape * 1.0 + low + tink


def flash_sting(seed):
    r = rng(seed)
    t = T(0.12)
    n = r.standard_normal(len(t))
    # air tear: 3-9 kHz noise, 8 ms attack (peak at ~10 ms), fast decay, falling band edge
    s = bp(n, 3000, 9000, 3) * env_ar(0.12, 0.008, 0.022, t)
    s += 0.25 * hp(n, 6000) * np.exp(-t / 0.012)
    s[t > 0.095] *= np.linspace(1, 0, (t > 0.095).sum())  # near-silence at the end
    return s


def whoosh_heavy(seed):
    r = rng(seed)
    t = T(0.60)
    n = r.standard_normal(len(t))
    # centre frequency 400 -> 2500 (0-120 ms) -> 700 (600 ms)
    up = 400 * (2500 / 400) ** np.clip(t / 0.12, 0, 1)
    down = 2500 * (700 / 2500) ** np.clip((t - 0.12) / 0.48, 0, 1)
    fc = np.where(t < 0.12, up, down)
    amp = np.where(t < 0.12, 0.5 - 0.5 * np.cos(np.pi * t / 0.12), np.exp(-(t - 0.12) / 0.17))
    air = swept_bp(n, fc, q=1.4) * amp
    # body 90-200 Hz, Doppler-like pitch fall 200 -> 90 Hz
    f0 = 90 + 110 * np.exp(-t / 0.22)
    ph = 2 * np.pi * np.cumsum(f0) / SR
    body = (np.sin(ph) + 0.4 * np.sin(2 * ph)) * amp * 0.9
    body += lp(n, 220) * amp * 1.2
    return air / (np.abs(air).max()) * 0.8 + body / (np.abs(body).max()) * 0.55


def impact_steel(seed):
    r = rng(seed)
    t = T(0.45)
    n = r.standard_normal(len(t))
    thump_f = 60 + 60 * np.exp(-t / 0.012)
    thump = np.sin(2 * np.pi * np.cumsum(thump_f) / SR) * np.exp(-t / 0.030) * 1.0
    clang = modal(t, 520, [1, 2.76, 5.40, 8.93], [0.25, 0.20, 0.12, 0.08], [0.55, 0.45, 0.30, 0.18], detune=0.003, r=r)
    click = hp(n, 1500) * np.exp(-t / 0.0015) * 0.9
    ping = 0.12 * np.sin(2 * np.pi * 2400 * t) * np.exp(-t / 0.05)
    y = thump + clang + click + ping
    return y * np.minimum(1, t / 0.0004 + 0.001)  # attack < 3 ms


def cut_thud(seed):
    r = rng(seed)
    t = T(0.25)
    n = r.standard_normal(len(t))
    burst = lp(n, 900, 3) * np.exp(-t / 0.045)
    thud = np.sin(2 * np.pi * (150 - 50 * np.clip(t / 0.08, 0, 1)) * t) * np.exp(-t / 0.060)
    y = burst * 0.8 + thud
    return y * np.minimum(1, t / 0.002)


def n_(r, t):
    return r.standard_normal(len(t))


def armor_ring(seed):
    r = rng(seed)
    t = T(0.90)
    ratios = [1.0, 2.32, 3.87, 5.61]
    taus = [0.40, 0.30, 0.20, 0.12]  # ~T60 0.8 s for the fundamental
    amps = [0.8, 0.5, 0.32, 0.18]
    y = modal(t, 1100, ratios, taus, amps, detune=0.0025, r=r)
    y += 0.4 * modal(t, 1100 * 0.5, [1, 1.0], [0.25, 0.25], [0.5, 0.0])  # body hum
    y = 0.45 * y * (1 - np.exp(-t / 0.004))
    y += 2.2 * hp(n_(r, t), 2500) * np.exp(-t / 0.006) * np.minimum(1, t / 0.003)  # strike tick so the transient leads
    wet = allpass(allpass(y, 0.0071, 0.55), 0.0113, 0.5)
    return 0.7 * y + 0.5 * wet


def ground_slam(seed):
    r = rng(seed)
    t = T(0.60)
    n = r.standard_normal(len(t))
    f = 38 + 32 * np.exp(-t / 0.09)  # 70 -> 38 Hz over ~250 ms
    sub = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.17)
    # gravel: crackly grains in 200-1200 Hz low-passed noise over 0.4 s
    grains = (r.random(len(t)) < (900 * np.exp(-t / 0.15)) / SR).astype(float)
    grit = lp(bp(n, 200, 1200), 1200) * (0.35 + 0.65 * lp(grains * 40 + 0.0, 800)) * np.exp(-t / 0.14)
    grit *= (t < 0.4)
    crack = hp(n, 800) * np.exp(-t / 0.006) * 0.6
    y = sub * 1.0 + grit * 1.1 + crack
    return y * np.minimum(1, t / 0.001)


def ring_whirl(seed):
    r = rng(seed)
    t = T(0.90)
    n = r.standard_normal(len(t))
    env = np.where(t < 0.25, np.sin(0.5 * np.pi * t / 0.25) ** 2, np.exp(-(t - 0.25) / 0.22))
    # comb-filtered noise = metallic shimmer
    sh = bp(n, 2000, 6000, 3)
    sh = comb(sh, 1 / 2700.0, 0.7) + comb(sh, 1 / 4100.0, 0.6)
    # rising partials 2 -> 6 kHz
    y = np.zeros_like(t)
    for k, (f0, f1) in enumerate([(2000, 3100), (2900, 4400), (3800, 5600), (4700, 6000)]):
        f = f0 + (f1 - f0) * np.clip(t / 0.45, 0, 1)
        y += np.sin(2 * np.pi * np.cumsum(f) / SR + r.uniform(0, 6)) * (0.35 / (1 + 0.3 * k)) * (1 + 0.3 * np.sin(2 * np.pi * (11 + 3 * k) * t))
    return (sh / np.abs(sh).max() * 0.7 + y) * env


def sword_settle(seed):
    r = rng(seed)
    t = T(0.25)
    n = r.standard_normal(len(t))
    tink = np.exp(-np.clip(t - 0.030, 0, None) / 0.045) * (t >= 0.03) * (np.sin(2 * np.pi * 3300 * t) + 0.5 * np.sin(2 * np.pi * 4510 * t) + 0.3 * np.sin(2 * np.pi * 6100 * t))
    y = tink
    for k in range(4):  # leather / cloth rattle
        t0 = 0.045 + abs(r.normal(0.05, 0.05))
        y = y + 0.28 * bp(n, 900, 3200) * np.exp(-np.clip(t - t0, 0, None) / 0.018) * (t >= t0) * r.uniform(0.5, 1)
    return y + 0.15 * lp(n, 400) * env_ar(0.25, 0.03, 0.05, t)


# file, fn, seed, peak_dBFS
CUES = [
    ("bastion-s1-step-scuff", step_scuff, 11, -14.0),
    ("bastion-s1-blade-lift", blade_lift, 21, -16.0),
    ("bastion-s1-flash-sting", flash_sting, 31, -8.0),
    ("bastion-s1-whoosh-heavy", whoosh_heavy, 41, -9.0),
    ("bastion-s1-impact-steel", impact_steel, 51, -1.5),
    ("bastion-s1-cut-thud", cut_thud, 61, -6.0),
    ("bastion-s1-armor-ring", armor_ring, 71, -12.0),
    ("bastion-s1-ground-slam", ground_slam, 81, -2.0),
    ("bastion-s1-ring-whirl", ring_whirl, 91, -12.0),
    ("bastion-s1-sword-settle", sword_settle, 101, -16.0),
]


def encode(x, path):
    with tempfile.TemporaryDirectory() as d:
        w = os.path.join(d, "in.wav")
        wavfile.write(w, SR, x.astype(np.float32))
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", w, "-ac", "1", "-ar", "44100",
                        "-c:a", "libvorbis", "-q:a", "5", path], check=True)


def decode(path):
    with tempfile.TemporaryDirectory() as d:
        w = os.path.join(d, "o.wav")
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", path, "-ac", "1", "-ar", "44100", "-c:a", "pcm_f32le", w], check=True)
        return wavfile.read(w)[1].astype(np.float64)


def main():
    wav_dir = sys.argv[1] if len(sys.argv) > 1 else None
    os.makedirs(OUT, exist_ok=True)
    for name, fn, seed, peak_db in CUES:
        x = fn(seed)
        x = x - x.mean()  # DC
        x = fade(x)
        target = 10 ** (peak_db / 20)
        x = x / np.abs(x).max() * target
        path = os.path.join(OUT, name + ".ogg")
        for _ in range(3):  # re-trim so the decoded peak hits the target
            encode(x, path)
            got = np.abs(decode(path)).max()
            err = 20 * np.log10(got / target)
            if abs(err) < 0.1:
                break
            x = x * 10 ** (-err / 20)
        if wav_dir:
            os.makedirs(wav_dir, exist_ok=True)
            wavfile.write(os.path.join(wav_dir, name + ".wav"), SR, x.astype(np.float32))
        print(f"{name}: {len(x)/SR:.3f}s peak@{np.argmax(np.abs(x))/SR*1000:.0f}ms decoded-err {err:+.2f} dB")


if __name__ == "__main__":
    main()
