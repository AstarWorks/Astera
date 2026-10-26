#!/usr/bin/env python3
"""Mix + calibrate the bastion-greatsword-s1 cue sheet against the reference profile.

  python3 bastion_s1_mix.py mix <out_dir>          render the single-cast timeline (both coins) -> ours-s1-mix.wav/.ogg + features
  python3 bastion_s1_mix.py calibrate [--write]    solve per-group volumes so ours' event levels match the reference (relative to E4)

The cue sheet (deliver/sound-cues.yaml) is the source of truth for tick / volume / pitch. play_sound pitch is a playback-rate
multiplier (resampled here the same way). The mix is mono (play_sound is mono-positional); the reference is stereo, S/M is
reported for it only. The reference feature JSON is produced by bastion_s1_features.py (reference audio is fetched by hand into
a scratch dir and never stored in the repo).
"""
import json
import os
import subprocess
import sys
import tempfile

import numpy as np
import yaml
from scipy.io import wavfile

sys.path.insert(0, os.path.dirname(__file__))
import bastion_s1_features as F  # noqa: E402

SR = 44100
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
SHEET = os.path.join(ROOT, "tools/skill-compare/data/bastion-greatsword-s1/deliver/sound-cues.yaml")
SND = os.path.join(ROOT, "content/sounds/weapons")
PAD_TICKS = 4      # silence before tick 0 so band filters / pre-roll see a clean floor
TOTAL_TICKS = 60
REF = os.environ.get("BASTION_REF_FEAT", "/tmp/claude-1000/ana/ref_feat.json")

# calibration groups: reference event -> (hit key, cue files whose volumes scale together)
GROUPS = {
    "E1 lift flick A": ("hit1", ["bastion-s1-step-scuff"]),
    "E2 lift flick B": ("hit1", ["bastion-s1-blade-lift"]),
    "E3 swing-1 whoosh": ("hit1", ["bastion-s1-whoosh-heavy"]),
    "E4 impact-1 body": ("hit1", ["bastion-s1-impact-steel", "bastion-s1-cut-thud"]),
    "E5 ring shimmer 1": ("hit1", ["bastion-s1-ring-whirl", "bastion-s1-armor-ring"]),
    "E6 body-reversal flick": ("hit2", ["bastion-s1-blade-lift"]),
    "E7 swing-2 whoosh": ("hit2", ["bastion-s1-whoosh-sweep"]),
    "E8 impact-2 body": ("hit2", ["bastion-s1-impact-steel", "bastion-s1-cut-thud", "bastion-s1-ground-slam"]),
    "E9 ring shimmer 2": ("hit2", ["bastion-s1-ring-whirl", "bastion-s1-armor-ring"]),
    "E10 settle 1": ("hit1", ["bastion-s1-sword-settle"]),
    "E11 settle 2": ("hit2", ["bastion-s1-sword-settle"]),
}


def decode(name):
    with tempfile.TemporaryDirectory() as d:
        w = os.path.join(d, "o.wav")
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", os.path.join(SND, name + ".ogg"),
                        "-ac", "1", "-ar", "44100", "-c:a", "pcm_f32le", w], check=True)
        return wavfile.read(w)[1].astype(np.float64)


def render(cues):
    buf = np.zeros(int((PAD_TICKS + TOTAL_TICKS) * 0.05 * SR))
    for c in cues:
        x = decode(c["file"])
        for h in ("hit1", "hit2"):
            if h not in c:
                continue
            p = c[h]
            n = int(len(x) / p["pitch"])
            y = np.interp(np.arange(n) * p["pitch"], np.arange(len(x)), x) * p["volume"]
            s = int((p["tick"] + PAD_TICKS) * 0.05 * SR)
            e = min(len(buf), s + n)
            buf[s:e] += y[: e - s]
    return buf


def load_sheet():
    return yaml.safe_load(open(SHEET))


def feats(buf):
    st = np.stack([buf, buf], 1)
    return F.features(SR, st, PAD_TICKS * 0.05)


def db(v):
    return 20 * np.log10(max(v, 1e-12))


def dump_sheet(doc, header):
    lines = [header.rstrip("\n"), "cues:"]
    for c in doc["cues"]:
        parts = [f"file: {c['file']}", f"layer: {c['layer']}", f"peak_ms: {c['peak_ms']}"]
        for h in ("hit1", "hit2"):
            if h in c:
                p = c[h]
                parts.append(f"{h}: {{tick: {p['tick']}, volume: {p['volume']:.2f}, pitch: {p['pitch']}}}")
        lines.append("  - {" + ", ".join(parts) + "}")
    return "\n".join(lines) + "\n"


def calibrate(write):
    doc = load_sheet()
    ref = {e["name"]: e for e in json.load(open(REF))}
    ref_rel = {k: v["level_db"] - ref["E4 impact-1 body"]["level_db"] for k, v in ref.items()}
    by = {c["file"]: c for c in doc["cues"]}
    for it in range(6):
        buf = render(doc["cues"])
        ours = {e["name"]: e for e in feats(buf)}
        base = ours["E4 impact-1 body"]["level_db"]
        worst = 0
        for ev, (hit, files) in GROUPS.items():
            d = ref_rel[ev] - (ours[ev]["level_db"] - base)
            worst = max(worst, abs(d))
            for f in files:
                if hit in by[f]:
                    by[f][hit]["volume"] = float(np.clip(by[f][hit]["volume"] * 10 ** (d * 0.8 / 20), 0.02, 1.0))
        print(f"iter {it}: worst level delta {worst:.2f} dB")
        mx = max(p["volume"] for c in doc["cues"] for h, p in c.items() if h in ("hit1", "hit2"))
        for c in doc["cues"]:
            for h in ("hit1", "hit2"):
                if h in c:
                    c[h]["volume"] *= 0.9 / mx
    buf = render(doc["cues"])
    print(f"mix peak {db(np.abs(buf).max()):.2f} dBFS")
    if write:
        hdr = "".join(l for l in open(SHEET) if l.startswith("#"))
        open(SHEET, "w").write(dump_sheet(doc, hdr))


def mix(out):
    os.makedirs(out, exist_ok=True)
    doc = load_sheet()
    buf = render(doc["cues"])
    pk = np.abs(buf).max()
    wav = os.path.join(out, "ours-s1-mix.wav")
    wavfile.write(wav, SR, buf[int(PAD_TICKS * 0.05 * SR):].astype(np.float32))
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "5", os.path.join(out, "ours-s1-mix.ogg")], check=True)
    wavfile.write(os.path.join(out, "ours_padded.wav"), SR, buf.astype(np.float32))
    print(f"mix peak {db(pk):.2f} dBFS; length {len(buf)/SR - PAD_TICKS*0.05:.2f}s")
    r = feats(buf)
    json.dump(r, open(os.path.join(out, "ours_feat.json"), "w"), indent=1)
    return r


if __name__ == "__main__":
    if sys.argv[1] == "calibrate":
        calibrate("--write" in sys.argv)
    else:
        for e in mix(sys.argv[2]):
            print(f"{e['name']:24s} on {e['onset_tick']:5.2f} pk {e['peak_tick']:5.2f} dec {e['decay_ms']:5.0f}ms dur6 {e['dur_ms']:4.0f}ms "
                  f"cen {e['centroid_hz']:6.0f}Hz flat {e['flatness']:.2f} lvl {e['level_db']:6.1f} shares " + "/".join(f"{s:.0f}" for s in e['shares']))
