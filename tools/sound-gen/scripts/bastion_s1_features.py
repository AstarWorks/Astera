#!/usr/bin/env python3
"""Feature extractor for the bastion-greatsword-s1 reference-vs-ours comparison.

Analysis only. The reference audio (case video Svaaq9F7Ty8, 11.633-14.233 s) is fetched by
the caller into a scratch dir; nothing derived from it is written into the repo except numbers.

An EVENT is (name, band_lo_hz, band_hi_hz, tick_lo, tick_hi). For each event this measures, on a
signal whose t=0 is the skill cast tick (50 ms ticks):
  onset_tick   first time the band envelope (5 ms RMS) reaches 50% of its in-window peak amplitude
  peak_tick    time of the band envelope maximum
  decay_ms     time from the peak until the band envelope has fallen by 12 dB (or window end)
  dur_ms       contiguous time around the peak with the band envelope >= 50% of the peak (-6 dB)
  centroid_hz  spectral centroid inside the band of the EXCESS spectrum around the peak (window power
               minus the median spectrum of the 5 ticks before onset: removes steady music under the SFX)
  shares       excess power share (percent) inside the band, split at 150 / 500 / 2000 / 6000 Hz
  flatness     spectral flatness of the window power in max(band_lo,1k)..min(band_hi,12k) (1 = white noise, 0 = tonal)
  level_db     band RMS at the peak (dBFS; the reference is music-contaminated, use differences only)
  side_mid     side/mid RMS ratio around the peak (stereo width; ours is mono = 0)

Usage: python3 bastion_s1_features.py ref|ours <wav> <t0_offset_s> [json_out]
"""
import json
import sys

import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, sosfiltfilt, stft

TICK = 0.05

# name, band lo, band hi, tick lo, tick hi
EVENTS = [
    ("E1 lift flick A",       4500, 16000, -1.0, 2.5),
    ("E2 lift flick B",       4500, 16000,  2.5, 5.0),
    ("E3 swing-1 whoosh",     1200,  6000,  4.2, 7.0),
    ("E4 impact-1 body",       100,  2000,  7.0, 9.3),
    ("E5 ring shimmer 1",     2000, 16000,  8.6, 12.2),
    ("E6 body-reversal flick", 4500, 16000, 22.4, 24.6),
    ("E7 swing-2 whoosh",     1200, 16000, 25.6, 29.0),
    ("E8 impact-2 body",       100,  2000, 30.2, 32.6),
    ("E9 ring shimmer 2",     2000, 16000, 32.4, 36.5),
    ("E10 settle 1",           500,  6000, 18.8, 21.2),
    ("E11 settle 2",           500,  6000, 40.6, 43.0),
]


def load(path):
    sr, x = wavfile.read(path)
    if x.dtype.kind == "i":
        x = x.astype(np.float64) / 32768.0
    else:
        x = x.astype(np.float64)
    if x.ndim == 1:
        x = np.stack([x, x], 1)
    return sr, x


def band(x, sr, lo, hi):
    return sosfiltfilt(butter(4, [lo, min(hi, sr / 2 * 0.95)], "bandpass", fs=sr, output="sos"), x)


def features(sr, st, off):
    m = st.mean(1)
    side = st[:, 0] - st[:, 1]
    res = []
    f, tt, Z = stft(m, sr, nperseg=1024, noverlap=1024 - 220)
    P = np.abs(Z) ** 2
    ts = tt - off
    for name, lo, hi, a, b in EVENTS:
        xb = band(m, sr, lo, hi)
        h = int(0.005 * sr)
        n = len(xb) // h
        env = np.sqrt((xb[: n * h].reshape(n, h) ** 2).mean(1))
        te = (np.arange(n) + 0.5) * h / sr - off
        sel = (te >= a * TICK) & (te <= b * TICK)
        e = env[sel]
        t = te[sel]
        ip = int(np.argmax(e))
        pk = e[ip]
        k = np.nonzero(e[: ip + 1] >= 0.5 * pk)[0][0]
        on = t[k]
        after = np.nonzero(e[ip:] <= pk * 10 ** (-12 / 20))[0]
        dec = (t[ip + after[0]] - t[ip]) if len(after) else (t[-1] - t[ip])
        i0 = ip
        while i0 > 0 and e[i0 - 1] >= 0.5 * pk:
            i0 -= 1
        i1 = ip
        while i1 < len(e) - 1 and e[i1 + 1] >= 0.5 * pk:
            i1 += 1
        dur = t[i1] - t[i0] + h / sr
        pre = (ts >= on - 5 * TICK) & (ts < on - 0.5 * TICK)
        win = (ts >= t[ip] - 1.5 * TICK) & (ts <= t[ip] + 1.5 * TICK)
        bg = np.median(P[:, pre], axis=1) if pre.sum() >= 2 else np.zeros(P.shape[0])
        ex = np.clip(P[:, win].mean(1) - bg, 0, None)
        ok = (f >= lo) & (f <= hi)
        cen = float((ex[ok] * f[ok]).sum() / (ex[ok].sum() + 1e-30))
        edges = [0, 150, 500, 2000, 6000, 24000]
        tot = ex[ok].sum() + 1e-30
        sh = [100 * ex[ok & (f >= edges[i]) & (f < edges[i + 1])].sum() / tot for i in range(5)]
        fk = (f >= max(lo, 1000)) & (f <= min(hi, 12000)) if hi > 2000 else ok
        g = P[:, win].mean(1)[fk] + 1e-30
        flat = float(np.exp(np.log(g).mean()) / g.mean())
        s0, s1 = int((t[ip] - 0.05 + off) * sr), int((t[ip] + 0.05 + off) * sr)
        sm = float(np.sqrt((side[s0:s1] ** 2).mean()) / (np.sqrt((m[s0:s1] ** 2).mean()) + 1e-12))
        res.append(dict(name=name, band=f"{lo}-{hi}", onset_tick=on / TICK, peak_tick=t[ip] / TICK,
                        decay_ms=dec * 1000, dur_ms=dur * 1000, centroid_hz=cen, shares=sh, flatness=flat,
                        level_db=20 * np.log10(pk + 1e-12), side_mid=sm))
    return res


def main():
    kind, path, off = sys.argv[1], sys.argv[2], float(sys.argv[3])
    sr, st = load(path)
    r = features(sr, st, off)
    for e in r:
        print(f"{e['name']:24s} on {e['onset_tick']:5.2f} pk {e['peak_tick']:5.2f} dec {e['decay_ms']:5.0f}ms dur6 {e['dur_ms']:4.0f}ms "
              f"cen {e['centroid_hz']:6.0f}Hz flat {e['flatness']:.2f} S/M {e['side_mid']:.2f} lvl {e['level_db']:6.1f} "
              f"shares " + "/".join(f"{s:.0f}" for s in e['shares']))
    if len(sys.argv) > 4:
        json.dump(r, open(sys.argv[4], "w"), indent=1)


if __name__ == "__main__":
    main()
