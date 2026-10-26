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


def fade(x, fin=0.001, fout=0.0):
    n = len(x)
    a = int(fin * SR)
    b = int(max(fout, min(0.12, 0.4 * n / SR)) * SR)  # every tail gets a long cosine fade (>= 40% of the file, max 120 ms)
    if a:
        x[:a] *= np.linspace(0, 1, a)
    if b:
        x[-b:] *= 0.5 + 0.5 * np.cos(np.linspace(0, np.pi, b))
    return x


# ---------------------------------------------------------------- cues ----
# r2 (reference-matched): every cue is designed from the numbers in docs/contributing/vfx-team/sound.md
# "Reference profile" (events E1..E11), measured on the case video with bastion_s1_features.py.
# Nothing here is derived from game audio: all layers are synthesised from noise / sines / modal sums.
from scipy.ndimage import gaussian_filter  # noqa: E402
from scipy.signal import istft, stft  # noqa: E402


def wander(t, r, depth=0.04, rate=9.0):
    """Slow random pitch wander multiplier (1 +- depth) so tails do not hold a dead-flat frequency."""
    k = r.standard_normal(int(t[-1] * rate) + 4)
    return 1 + depth * np.interp(t, np.arange(len(k)) / rate, k / max(np.abs(k).max(), 1e-9))


def steel_partials(t, r, base, ratios, taus, amps, depth=0.012):
    """Inharmonic ringing partials: each with its own decay, slow detune wander and tiny beating partner."""
    y = np.zeros_like(t)
    for ra, ta, am in zip(ratios, taus, amps):
        f = base * ra * wander(t, r, depth, 7.0)
        ph = 2 * np.pi * np.cumsum(f) / SR + r.uniform(0, 6.28)
        y += am * np.exp(-t / ta) * (np.sin(ph) + 0.5 * np.sin(1.0037 * ph + r.uniform(0, 6.28)))
    return y


def spec_noise(dur, fc_fn, bw_oct, r, lo=0.0, hi=SR / 2):
    """White noise shaped by a time-varying log-Gaussian band (STFT mask). Works above SR/6, unlike swept_bp.
    fc_fn(t_seconds_array) -> centre Hz per frame; bw_oct = Gaussian sigma in octaves."""
    n = int(round(dur * SR))
    x = r.standard_normal(n)
    f, tf, Z = stft(x, SR, nperseg=512, noverlap=512 - 64)
    fc = np.asarray(fc_fn(tf))[None, :]
    ff = np.maximum(f, 1.0)[:, None]
    g = np.exp(-0.5 * (np.log2(ff / fc) / bw_oct) ** 2)
    # soft first-order roll-offs instead of a brick wall at lo / hi
    if lo > 0:
        g = g / np.sqrt(1 + (lo / ff) ** 3)
    if hi < SR / 2:
        g = g * 1 / np.sqrt(1 + (ff / hi) ** 3)
    # smooth random time-frequency texture (+-3 dB blobs) so the band edges and body are not a flat slab
    blob = gaussian_filter(r.standard_normal(g.shape), (3.0, 6.0))
    g = g * np.exp(0.9 * blob / max(blob.std(), 1e-9) * 0.35)
    _, y = istft(Z * g, SR, nperseg=512, noverlap=512 - 64)
    y = y[:n]
    return np.pad(y, (0, n - len(y)))


def rise_decay(t, t_pk, rise, tau, shape=1.0):
    """Raised-cosine rise ending at t_pk (length `rise`), exponential decay (tau) after it."""
    t0 = t_pk - rise
    up = np.where(t < t0, 0.0, 0.5 - 0.5 * np.cos(np.pi * np.clip((t - t0) / rise, 0, 1))) ** shape
    return np.where(t <= t_pk, up, np.exp(-(t - t_pk) / tau))


def lead(x, ms):
    return np.concatenate([np.zeros(int(ms / 1000 * SR)), x])


def step_scuff(seed):
    # E1: bright 6-12 kHz noise flick. 50% rise at ~5 ms, peak 45 ms, -12 dB by ~85 ms, centroid ~8-9 kHz drifting down.
    r = rng(seed)
    t = T(0.16)
    y = spec_noise(0.16, lambda tf: 6500 + 3500 * np.exp(-tf / 0.05), 0.55, r, lo=4500)
    env = np.minimum(1, t / 0.006) * (0.55 + 0.45 * np.clip(t / 0.045, 0, 1)) * np.where(t < 0.045, 1.0, np.exp(-(t - 0.045) / 0.030))
    return y * env


def blade_lift(seed):
    # E2: low thunk (150-500 Hz body) then a second bright flick (rising 4.6k -> 8.4k, peak 66 ms after the thunk, dur ~55 ms)
    r = rng(seed)
    t = T(0.24)
    f0 = 190 + 150 * np.exp(-t / 0.02)
    ph = 2 * np.pi * np.cumsum(f0) / SR
    thunk = (np.sin(ph) + 0.35 * lp(r.standard_normal(len(t)), 900)) * np.minimum(1, t / 0.003) * np.exp(-t / 0.016)
    fl = spec_noise(0.24, lambda tf: 4600 + 3800 * np.clip((tf - 0.045) / 0.03, 0, 1) - 1400 * np.clip((tf - 0.075) / 0.1, 0, 1), 0.5, r, lo=4000)
    flick = fl * rise_decay(t, 0.066, 0.030, 0.045, shape=0.8)
    return thunk * 0.9 + flick * 1.0 / max(np.abs(fl).max(), 1e-9) * np.abs(thunk).max() * 1.1


def whoosh_heavy(seed):
    # E3 (swing 1): mid whoosh, noise swept 900 -> 3500 Hz (rise 95 ms to the peak), centroid ~2.6 kHz, dur6 ~75 ms, decay12 ~50 ms.
    r = rng(seed)
    t = T(0.30)
    fc_fn = lambda tf: np.where(tf < 0.095, 700 * (2900 / 700) ** np.clip(tf / 0.095, 0, 1), 2900 * (1800 / 2900) ** np.clip((tf - 0.095) / 0.12, 0, 1))
    y = spec_noise(0.30, fc_fn, 0.45, r)
    return y * rise_decay(t, 0.095, 0.095, 0.046, shape=0.9)


def whoosh_sweep(seed):
    # E7 (swing 2): bright falling sweep, 12.5 kHz -> 3.3 kHz in ~100 ms, loudest SFX in the reference. 24 ms of silence first
    # so the 50% rise lands at +0.48 tick and the peak at +0.88 tick when played on a tick boundary.
    r = rng(seed)
    t = T(0.22)
    y = spec_noise(0.22, lambda tf: 11000 * (2800 / 11000) ** np.clip(tf / 0.15, 0, 1), 0.5, r, lo=2000)
    env = rise_decay(t, 0.020, 0.020, 0.062) * np.where(t > 0.15, np.clip(1 - (t - 0.15) / 0.06, 0, 1), 1.0)
    return lead(y * env, 24)


def _body(r, dur, f_hi, f_lo, tone_tau, hold, tau, noise_cut=1100):
    # short thump: pitch drops f_hi -> ~0.55 f_lo within ~50 ms and wanders; noise cutoff falls with the envelope.
    dur = min(dur, 0.16)
    t = T(dur)
    f0 = (0.55 * f_lo + (f_hi - 0.55 * f_lo) * np.exp(-t / 0.022)) * wander(t, r, 0.05, 11.0)
    ph = 2 * np.pi * np.cumsum(f0) / SR
    tone = np.sin(ph)
    nz = lp(r.standard_normal(len(t)), noise_cut, 2)
    nz2 = lp(r.standard_normal(len(t)), noise_cut * 0.45, 2)
    mixn = np.exp(-t / 0.03)
    nz = nz * mixn + nz2 * (1 - mixn)
    env = np.minimum(1, t / 0.004) * np.where(t < 0.004 + hold, 1.0, np.exp(-(t - 0.004 - hold) / tau))
    return t, (tone * 1.0 + nz * 0.9) * env


def impact_steel(seed):
    # E4/E8 body: dull 150-500 Hz thud (centre ~300 Hz), a touch of 500-2k, tiny click; dur6 ~75 ms.
    r = rng(seed)
    t, y = _body(r, 0.16, 640, 400, 0.02, 0.022, 0.030, noise_cut=1500)
    click = hp(r.standard_normal(len(t)), 2000) * np.exp(-t / 0.0015) * 0.12
    return y + click


def cut_thud(seed):
    # 500-1800 Hz edge-on-armour scrape layer: ~20% of the contact body energy sits in 500-2k.
    r = rng(seed)
    t = T(0.18)
    y = bp(r.standard_normal(len(t)), 500, 1800, 2) * np.exp(-t / 0.028) * np.minimum(1, t / 0.003)
    f = (520 + 260 * np.exp(-t / 0.025)) * wander(t, r, 0.05, 10.0)
    return y + 0.4 * np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.018)


def armor_ring(seed):
    # E5 plateau: soft 2-8 kHz metallic hiss, onset after a 32 ms lead, slow rise (80 ms), hold, 65 ms decay; light inharmonic partials.
    r = rng(seed)
    t = T(0.36)
    y = spec_noise(0.36, lambda tf: 4300 + 800 * np.sin(2 * np.pi * 4 * tf), 0.55, r, lo=2000, hi=9000)
    env = np.where(t < 0.085, np.clip(t / 0.085, 0, 1) ** 1.5, np.where(t < 0.15, 1.0, np.exp(-(t - 0.15) / 0.05)))
    # steel: inharmonic bar-like ratios, each partial with its own decay, wandering and beating
    partials = steel_partials(t, r, 2150, [1.0, 1.83, 2.61, 3.47, 4.52], [0.085, 0.060, 0.045, 0.034, 0.024],
                              [0.07, 0.06, 0.05, 0.035, 0.025])
    return lead((y / max(np.abs(y).max(), 1e-9)) * env + partials * env, 8)


def ground_slam(seed):
    r = rng(seed)
    t = T(0.30)
    f = (45 + 30 * np.exp(-t / 0.08)) * wander(t, r, 0.04, 8.0)
    sub = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.07)
    grit = bp(r.standard_normal(len(t)), 200, 900, 2) * np.exp(-t / 0.04) * 0.5
    return (sub + grit) * np.minimum(1, t / 0.002)


def ring_whirl(seed):
    # E5/E9 shimmer: noisy (flatness ~0.6) 3-11 kHz sheen, centroid ~6.7 kHz, 32 ms lead, peak ~1.1 tick after onset, 12 dB down in ~140 ms.
    r = rng(seed)
    t = T(0.40)
    y = spec_noise(0.40, lambda tf: 6200 + 1200 * np.clip(tf / 0.15, 0, 1), 0.60, r, lo=2500, hi=12000)
    y = y + 0.45 * comb(y, 1 / 3300.0, 0.5)
    return lead(y * rise_decay(t, 0.050, 0.045, 0.085), 8)


def sword_settle(seed):
    # E10/E11 settle: quiet 500-1.5 kHz rattle plateau (centroid ~650 Hz), dur6 ~70 ms, decay12 ~45 ms.
    r = rng(seed)
    t = T(0.20)
    y = spec_noise(0.20, lambda tf: 750 + 0 * tf, 0.50, r, lo=450, hi=2500) * np.where(t < 0.04, rise_decay(t, 0.04, 0.025, 0.03), np.where(t < 0.075, 1.0, np.exp(-(t - 0.075) / 0.028)))
    tt = np.clip(t - 0.02, 0, None)
    tink = (t >= 0.02) * 0.9 * steel_partials(tt, r, 2900, [1.0, 1.47, 2.09, 2.83], [0.022, 0.016, 0.011, 0.008],
                                              [0.05, 0.04, 0.03, 0.02], depth=0.02)
    return y / max(np.abs(y).max(), 1e-9) + tink


# file, fn, seed, peak_dBFS (all -1.5: relative levels live in sound-cues.yaml volumes)
CUES = [
    ("bastion-s1-step-scuff", step_scuff, 11, -1.5),
    ("bastion-s1-blade-lift", blade_lift, 21, -1.5),
    ("bastion-s1-whoosh-heavy", whoosh_heavy, 41, -1.5),
    ("bastion-s1-whoosh-sweep", whoosh_sweep, 45, -1.5),
    ("bastion-s1-impact-steel", impact_steel, 51, -1.5),
    ("bastion-s1-cut-thud", cut_thud, 61, -1.5),
    ("bastion-s1-armor-ring", armor_ring, 71, -1.5),
    ("bastion-s1-ground-slam", ground_slam, 81, -1.5),
    ("bastion-s1-ring-whirl", ring_whirl, 91, -1.5),
    ("bastion-s1-sword-settle", sword_settle, 101, -1.5),
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
        for _ in range(6):  # re-trim so the decoded peak hits the target
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
