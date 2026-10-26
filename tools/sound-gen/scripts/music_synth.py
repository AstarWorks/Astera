#!/usr/bin/env python3
"""BGM for content/music/: free procedural synthesis (numpy + scipy + ffmpeg).

No samples, no network, no paid service (ADR-0267). Deterministic (fixed noise seeds). Loops are rendered with a
tail that is folded back onto the start, so the file is seamless when repeated every `duration_ticks`.

Usage:  python3 tools/sound-gen/scripts/music_synth.py [track-id ...]    (no args = all)
Writes: content/music/<id>.ogg  (mono, 44.1 kHz, Vorbis -q:a 3) and prints each track's length in ticks
        (the value `duration_ticks` in content/music/<id>.yaml must match for looping tracks).
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
OUT = os.path.join(ROOT, "content", "music")
RNG = np.random.default_rng(20261027)


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12.0)


def lp(x, f, order=2):
    return sosfilt(butter(order, f, "low", fs=SR, output="sos"), x)


def hp(x, f, order=2):
    return sosfilt(butter(order, f, "high", fs=SR, output="sos"), x)


class Track:
    def __init__(self, seconds, tail=4.0):
        self.n = int(round(seconds * SR))
        self.buf = np.zeros(self.n + int(tail * SR))

    def add(self, t0, sig, gain=1.0):
        i = int(round(t0 * SR))
        if i < 0 or i >= len(self.buf):
            return
        j = min(len(self.buf), i + len(sig))
        self.buf[i:j] += sig[: j - i] * gain

    def finish(self, loop=True):
        out = self.buf[: self.n].copy()
        if loop:
            tail = self.buf[self.n:]
            out[: len(tail)] += tail[: len(out)]
        else:
            out = self.buf[: self.n + int(1.0 * SR)].copy()
        return out


def env(n, a, d, s, r_total):
    """attack a, decay d to level s, held, exponential release over the last r_total seconds."""
    t = np.arange(n) / SR
    e = np.minimum(t / max(a, 1e-4), 1.0) * (s + (1 - s) * np.exp(-t / max(d, 1e-4)))
    dur = n / SR
    rel = np.clip((dur - t) / max(r_total, 1e-4), 0, 1)
    return e * rel


def tone(freq, dur, harm, a=0.01, d=0.2, s=0.6, r=0.1, vib=0.0):
    n = int(dur * SR)
    t = np.arange(n) / SR
    ph = 2 * np.pi * freq * t + (vib * np.sin(2 * np.pi * 5.2 * t) if vib else 0)
    x = sum(g * np.sin(k * ph) for k, g in harm)
    return x * env(n, a, d, s, r)


SINE = [(1, 1.0)]
SOFT = [(1, 1.0), (2, 0.35), (3, 0.12)]
SAW = [(k, 1.0 / k) for k in range(1, 9)]
SQUARE = [(k, 1.0 / k) for k in (1, 3, 5, 7)]
BELL = [(1, 1.0), (2.76, 0.4), (5.4, 0.2)]


def pad(freq, dur, g=0.3):
    # two slightly detuned soft saws, low-passed: a slow string-ish pad
    x = tone(freq, dur, SAW, a=0.5, d=1.0, s=0.8, r=0.6) + tone(freq * 1.004, dur, SAW, a=0.6, d=1.0, s=0.8, r=0.6)
    return lp(x, 1400) * g


def pluck(freq, dur=0.6, g=0.3):
    return tone(freq, dur, SOFT, a=0.004, d=0.18, s=0.0, r=0.05) * g


def bass(freq, dur, g=0.4, harm=SAW, cutoff=500):
    return lp(tone(freq, dur, harm, a=0.01, d=0.15, s=0.7, r=0.04), cutoff) * g


def kick(g=0.9):
    n = int(0.28 * SR)
    t = np.arange(n) / SR
    f = 45 + 90 * np.exp(-t * 28)
    ph = 2 * np.pi * np.cumsum(f) / SR
    return np.sin(ph) * np.exp(-t * 11) * g


def snare(g=0.5):
    n = int(0.22 * SR)
    t = np.arange(n) / SR
    noise = hp(RNG.standard_normal(n), 1500) * np.exp(-t * 20)
    return (noise * 0.8 + np.sin(2 * np.pi * 190 * t) * np.exp(-t * 28) * 0.5) * g


def hat(g=0.18, open_=False):
    n = int((0.18 if open_ else 0.05) * SR)
    t = np.arange(n) / SR
    return hp(RNG.standard_normal(n), 7000) * np.exp(-t * (14 if open_ else 70)) * g


def taiko(g=0.9):
    n = int(0.6 * SR)
    t = np.arange(n) / SR
    f = 70 + 40 * np.exp(-t * 18)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 6) * g


def triad(root, minor=True):
    return [root, root + (3 if minor else 4), root + 7]


# ------------------------------------------------------------------------------------------------------
def lobby():
    bpm, bars = 80, 16
    beat = 60 / bpm
    bar = 4 * beat
    tr = Track(bars * bar)
    prog = [(57, True), (53, False), (48, False), (55, False)]  # Am F C G
    for b in range(bars):
        root, minor = prog[b % 4]
        t0 = b * bar
        notes = [n + 12 for n in triad(root, minor if root == 57 else False)]
        for n in notes:
            tr.add(t0, pad(hz(n), bar + 0.3), 0.16)
        tr.add(t0, bass(hz(root - 12), bar, 0.5, SOFT, 300), 1.0)
        arp = [notes[0], notes[1], notes[2] + 0, notes[1]]
        for k in range(8):
            tr.add(t0 + k * beat / 2, pluck(hz(arp[k % 4] + 12), 0.7), 0.5)
        if b >= 8 and b % 2 == 0:  # soft bell melody in the second half
            tr.add(t0 + 2 * beat, tone(hz(notes[2] + 12), 1.6, BELL, 0.003, 0.5, 0.0, 0.3), 0.18)
    return tr.finish(), bpm


def match():
    bpm, bars = 120, 16
    beat = 60 / bpm
    bar = 4 * beat
    tr = Track(bars * bar)
    prog = [38, 38, 34, 33]  # Dm Dm Bb A  (roots)
    qual = [True, True, False, False]
    for b in range(bars):
        root, minor = prog[b % 4], qual[b % 4]
        t0 = b * bar
        for n in triad(root + 24, minor):
            tr.add(t0, pad(hz(n), bar + 0.3), 0.13)
        for k in range(8):
            tr.add(t0 + k * beat / 2, bass(hz(root), beat / 2 * 0.9, 0.5, SAW, 420 + 80 * (b % 4)), 1.0)
        tr.add(t0, kick(0.6), 1.0)
        tr.add(t0 + 2 * beat, kick(0.5), 1.0)
        for k in range(4):
            tr.add(t0 + k * beat + beat / 2, hat(0.14), 1.0)
        if b % 4 == 3:
            tr.add(t0 + 3 * beat, snare(0.35), 1.0)
        if b >= 8:  # tense high stab line
            for k in (1, 3, 5):
                tr.add(t0 + k * beat / 2 * 1.0, lp(tone(hz(root + 36 + (7 if k == 5 else 0)), 0.3, SQUARE, 0.005, 0.1, 0.3, 0.1), 2500), 0.12)
    return tr.finish(), bpm


def high_intensity():
    bpm, bars = 150, 16
    beat = 60 / bpm
    bar = 4 * beat
    tr = Track(bars * bar)
    prog = [(40, True), (36, False), (43, False), (38, False)]  # Em C G D
    for b in range(bars):
        root, minor = prog[b % 4]
        t0 = b * bar
        ch = triad(root + 12, minor)
        for k in range(4):
            tr.add(t0 + k * beat, kick(0.85), 1.0)
        tr.add(t0 + beat, snare(0.5), 1.0)
        tr.add(t0 + 3 * beat, snare(0.5), 1.0)
        for k in range(16):
            tr.add(t0 + k * beat / 4, hat(0.12 if k % 2 else 0.2), 1.0)
        tr.add(t0 + 3.5 * beat, hat(0.25, True), 1.0)
        for k in range(8):
            tr.add(t0 + k * beat / 2, bass(hz(root), beat / 2 * 0.85, 0.55, SAW, 600), 1.0)
        arp = [ch[0] + 12, ch[1] + 12, ch[2] + 12, ch[1] + 12]
        for k in range(16):
            tr.add(t0 + k * beat / 4, lp(tone(hz(arp[k % 4]), beat / 4 * 0.9, SAW, 0.004, 0.08, 0.4, 0.03), 3200), 0.13)
        for n in ch:
            tr.add(t0, pad(hz(n + 12), bar + 0.2), 0.08)
    return tr.finish(), bpm


def boss():
    bpm, bars = 100, 16
    beat = 60 / bpm
    bar = 4 * beat
    tr = Track(bars * bar)
    prog = [(38, True), (39, False), (38, True), (34, False)]  # Dm Eb Dm Bb  (phrygian colour)
    for b in range(bars):
        root, minor = prog[b % 4]
        t0 = b * bar
        tr.add(t0, pad(hz(root), bar + 0.4), 0.3)
        tr.add(t0, pad(hz(root + 7), bar + 0.4), 0.2)
        tr.add(t0, taiko(0.9), 1.0)
        tr.add(t0 + 2.5 * beat, taiko(0.6), 1.0)
        if b % 2 == 1:
            tr.add(t0 + 3 * beat, taiko(0.7), 1.0)
            tr.add(t0 + 3.5 * beat, taiko(0.7), 1.0)
        for k in (1, 2, 3):  # offbeat brass stabs
            if (b + k) % 2 == 0:
                for n in triad(root + 24, minor):
                    tr.add(t0 + k * beat + beat / 2, lp(tone(hz(n), 0.35, SAW, 0.02, 0.1, 0.5, 0.1), 1800), 0.12)
        for k in range(4):
            tr.add(t0 + k * beat, hat(0.1), 1.0)
    return tr.finish(), bpm


def reveal():
    dur = 12.0
    tr = Track(dur, tail=2.0)
    n = int(dur * SR)
    t = np.arange(n) / SR
    for root, g in ((38, 0.5), (45, 0.4), (50, 0.35), (53, 0.3), (57, 0.25)):
        f = hz(root) * (1 + 0.5 * (t / dur) ** 2)  # slow rise
        x = sum(np.sin(2 * np.pi * np.cumsum(f * k) / SR) / k for k in (1, 2, 3, 4))
        x = lp(x, 1800) * (t / dur) ** 1.6 * g
        tr.add(0, x * 0.5)
    tr.add(10.0, taiko(1.0), 1.0)
    tr.add(10.0, lp(tone(hz(38), 2.0, SAW, 0.01, 0.4, 0.3, 1.0), 900), 0.5)
    tr.add(10.0, hp(RNG.standard_normal(int(1.5 * SR)), 3000) * np.exp(-np.arange(int(1.5 * SR)) / SR * 4) * 0.15)
    return tr.finish(loop=False), 0


def victory():
    bpm = 120
    beat = 60 / bpm
    tr = Track(8.0, tail=3.0)
    chords = [(0.0, 60, True), (1.0, 65, True), (2.0, 67, True), (3.0, 72, True)]
    for tb, root, _ in chords:
        for n in (root, root + 4 if root != 65 else root + 4, root + 7):
            tr.add(tb * 1.0, lp(tone(hz(n), 1.0 if tb < 3 else 5.0, SAW, 0.02, 0.15, 0.7, 0.8 if tb >= 3 else 0.1), 2400), 0.16)
        tr.add(tb, bass(hz(root - 24), 1.0 if tb < 3 else 4.0, 0.5, SAW, 400), 1.0)
    for k, n in enumerate((84, 88, 91, 96)):
        tr.add(3.0 + k * 0.12, tone(hz(n), 3.0, BELL, 0.003, 0.7, 0.0, 0.5), 0.14)
    tr.add(0, kick(0.8))
    tr.add(3.0, taiko(0.9))
    tr.add(3.0, snare(0.4))
    out = tr.finish(loop=False)
    return out, bpm


TRACKS = {
    "lobby-loop": (lobby, True),
    "match-default": (match, True),
    "high-intensity-drums": (high_intensity, True),
    "boss-theme": (boss, True),
    "boss-reveal-strings": (reveal, False),
    "victory-fanfare": (victory, False),
}


def encode(name, x, loop):
    x = x - np.mean(x)
    if loop:  # do not touch the seam: gentle tanh only
        pass
    rms = np.sqrt(np.mean(x ** 2)) + 1e-9
    x = x * (10 ** (-20 / 20) / rms)  # -20 dBFS RMS
    x = np.tanh(x * 1.2) / 1.2
    if not loop:
        fade = int(0.4 * SR)
        x[-fade:] *= np.linspace(1, 0, fade)
    x = x * (10 ** (-1.5 / 20) / max(np.max(np.abs(x)), 1e-6))
    with tempfile.TemporaryDirectory() as d:
        wav = os.path.join(d, name + ".wav")
        wavfile.write(wav, SR, (x * 32767).astype(np.int16))
        dst = os.path.join(OUT, name + ".ogg")
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-fflags", "+bitexact", "-i", wav, "-ac", "1",
                        "-ar", str(SR), "-c:a", "libvorbis", "-q:a", "3", "-flags:a", "+bitexact", dst], check=True)
    ticks = round(len(x) / SR * 20)
    print(f"ok: {name} {len(x) / SR:.3f}s = {ticks} ticks, {os.path.getsize(dst)} bytes")


def main():
    names = sys.argv[1:] or list(TRACKS)
    os.makedirs(OUT, exist_ok=True)
    for name in names:
        fn, loop = TRACKS[name]
        x, _ = fn()
        encode(name, x, loop)


if __name__ == "__main__":
    main()
