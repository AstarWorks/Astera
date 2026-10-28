#!/usr/bin/env python3
"""Offline rate-law fitter over shipped look.yaml tracks (ADR-0303 section 3, WP-A2).

Reads content/skills/*/look.yaml, finds every `op: track`, and for each channel (offset, turn, scale, alpha) decides:

  * a list of rate laws (move / spin / grow / fade) that the Kotlin `RateLawExpander` (kernel/domain/.../skillengine/motion/RateLaw*.kt)
    lowers to a key table which the runtime sampler (PresentationRuntime.sampleTrack) reads as the SAME per-tick value, or
  * the original keys, with the reason `CONVERT-KEYS-EXACT <reason>` (no silent approximation).

Merging: consecutive linear segments merge only while the longer law still reproduces every interior key within TOL (1e-4, blocks / scale
units / opacity / degrees). Eased segments stay single laws (ease shapes the segment total, 3.7). Nothing is rounded to a looser budget.

It does NOT edit content. It writes the coverage report (default /tmp/claude-1000/skill-engine/wpa/coverage.md). The sampler and expander
below are exact Python mirrors of the Kotlin ones; the Kotlin side is proven by RateLawParityScenarioTest.

Usage: python3 tools/skill-convert/ratefit.py [--content content/skills] [--out /tmp/claude-1000/skill-engine/wpa/coverage.md] [--laws-json PATH]
"""
import argparse
import collections
import glob
import json
import math
import os
import re
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rotvec  # read-only: EASING table / ease_key (identical to Easing.kt)

TOL = 1e-4
MAX_SPIN_CHUNK_DEG = 170.0
CHANNELS = ("offset", "turn", "scale", "alpha")
PREFIX = "CONVERT-KEYS-EXACT"


# ---------------------------------------------------------------- vectors / quaternions (w, x, y, z)

def vadd(a, b): return [x + y for x, y in zip(a, b)]
def vsub(a, b): return [x - y for x, y in zip(a, b)]
def vmul(a, s): return [x * s for x in a]
def vlen(a): return math.sqrt(sum(x * x for x in a))
def vnorm(a):
    n = vlen(a)
    return [x / n for x in a] if n > 1e-12 else [0.0, 0.0, 0.0]


def q_axis_angle(axis, deg):
    a = vnorm(axis)
    if vlen(a) == 0:
        return (1.0, 0.0, 0.0, 0.0)
    h = math.radians(deg) / 2.0
    s = math.sin(h)
    return (math.cos(h), a[0] * s, a[1] * s, a[2] * s)


def q_from_vec(v):
    n = vlen(v)
    return (1.0, 0.0, 0.0, 0.0) if n < 1e-12 else q_axis_angle(v, n)


def q_mul(a, b):
    return rotvec._q_mul(a, b)


def q_conj(a): return (a[0], -a[1], -a[2], -a[3])
def q_dot(a, b): return sum(x * y for x, y in zip(a, b))


def q_norm(a):
    n = math.sqrt(q_dot(a, a))
    return tuple(x / n for x in a)


def q_to_axis_angle(q):
    """(unit axis, degrees in [0, 180]) of the shortest arc."""
    q = q_norm(q)
    if q[0] < 0:
        q = tuple(-x for x in q)
    s = math.sqrt(q[1] ** 2 + q[2] ** 2 + q[3] ** 2)
    if s < 1e-12:
        return [0.0, 0.0, 0.0], 0.0
    return [q[1] / s, q[2] / s, q[3] / s], math.degrees(2.0 * math.atan2(s, q[0]))


def q_to_vec(q):
    ax, ang = q_to_axis_angle(q)
    return [c * ang for c in ax]


def q_slerp(a, b, t):
    d = q_dot(a, b)
    if d < 0:
        d = -d
        b = tuple(-x for x in b)
    if d > 0.9995:
        return q_norm(tuple(x + (y - x) * t for x, y in zip(a, b)))
    th = math.acos(max(-1.0, min(1.0, d)))
    st = math.sin(th)
    wa = math.sin((1.0 - t) * th) / st
    wb = math.sin(t * th) / st
    return tuple(x * wa + y * wb for x, y in zip(a, b))


def q_angle_deg(a, b):
    return math.degrees(2.0 * math.acos(max(0.0, min(1.0, abs(q_dot(q_norm(a), q_norm(b)))))))


# ---------------------------------------------------------------- sampler mirror (PresentationRuntime.channel)

def sample(keys, elapsed, lerp):
    """keys: [(tick, value, ease_name_or_None)] sorted by tick."""
    if elapsed <= keys[0][0]:
        return keys[0][1]
    if elapsed >= keys[-1][0]:
        return keys[-1][1]
    hi = next(i for i, k in enumerate(keys) if k[0] >= elapsed)
    a, b = keys[hi - 1], keys[hi]
    raw = (elapsed - a[0]) / max(1, b[0] - a[0])
    e = rotvec.ease(b[2], raw) if b[2] else raw
    return lerp(a[1], b[1], e)


def lerp_vec(a, b, f): return [x + (y - x) * f for x, y in zip(a, b)]
def lerp_num(a, b, f): return a + (b - a) * f
def lerp_turn(a, b, f): return q_slerp(q_from_vec(a), q_from_vec(b), f)  # returns a quaternion


# ---------------------------------------------------------------- expander mirror (RateLawExpander)

def _ease(name, u):
    return rotvec.ease(name, u) if name else u


def expand_law(law):
    """law dict -> [(tick, value, ease)] of ONE channel, exactly as the Kotlin expander lowers it."""
    k, f, n, ez = law["kind"], law["from"], law["for"], law.get("ease")
    if k == "move":
        s, v, acc = law["start"], law["velocity"], law.get("accel", 0.0)
        if not acc:
            return [(f, s, None), (f + n, vadd(s, vmul(v, n)), ez)]
        d = vnorm(v)
        return [(f + t, vadd(vadd(s, vmul(v, t)), vmul(d, acc * t * t / 2.0)), None) for t in range(n + 1)]
    if k == "grow":
        s, v, acc = law["start"], law["per_tick"], law.get("accel", [0.0, 0.0, 0.0])
        if not any(acc):
            return [(f, s, None), (f + n, vadd(s, vmul(v, n)), ez)]
        return [(f + t, vadd(vadd(s, vmul(v, t)), vmul(acc, t * t / 2.0)), None) for t in range(n + 1)]
    if k == "fade":
        s, to, ex = law["start"], law["to"], law.get("exponent", 1.0)
        if ex == 1.0:
            return [(f, s, None), (f + n, to, ez)]
        return [(f + t, to + (s - to) * (1.0 - _ease(ez, t / n)) ** ex, None) for t in range(n + 1)]
    if k == "spin":
        axis = vnorm(law["axis"])
        start = law.get("start", [0.0, 0.0, 0.0])
        zero = vlen(start) < 1e-9
        q0 = q_from_vec(start)
        rate, acc = law["deg_per_tick"], law.get("accel", 0.0)

        def vec(deg):
            return vmul(axis, deg) if zero else q_to_vec(q_mul(q_axis_angle(axis, deg), q0))
        total = rate * n
        if not acc and not ez:
            if abs(total) <= MAX_SPIN_CHUNK_DEG:
                return [(f, vec(0.0), None), (f + n, vec(total), None)]
            chunk = max(1, int(math.floor(MAX_SPIN_CHUNK_DEG / abs(rate))))
            ticks = list(range(0, n, chunk)) + [n]
            return [(f + t, vec(rate * t), None) for t in ticks]
        if not acc and abs(total) <= MAX_SPIN_CHUNK_DEG:
            return [(f, vec(0.0), None), (f + n, vec(total), ez)]
        angles = [total * _ease(ez, t / n) if ez else rate * t + acc * t * t / 2.0 for t in range(n + 1)]
        for a, b in zip(angles, angles[1:]):
            if abs(b - a) >= 180.0:
                raise ValueError("spin turns %.1f deg in one tick" % abs(b - a))
        return [(f + t, vec(a), None) for t, a in enumerate(angles)]
    raise ValueError(k)


def expand_laws(laws):
    return sorted((x for law in laws for x in expand_law(law)), key=lambda x: x[0])


# ---------------------------------------------------------------- reading tracks

def walk_tracks(node, out):
    if isinstance(node, dict):
        if node.get("op") == "track" and isinstance(node.get("keys"), list):
            out.append(node)
        for v in node.values():
            walk_tracks(v, out)
    elif isinstance(node, list):
        for v in node:
            walk_tracks(v, out)


def as_vec3(v):
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        return [float(v)] * 3
    if isinstance(v, list) and len(v) == 3 and all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in v):
        return [float(x) for x in v]
    return None


def is_linear(name):
    return name in (None, "linear")


class Keep(Exception):
    pass


def channel_keys(track, ch):
    """[(tick, value, ease)] for one channel, or raises Keep(reason)."""
    out = []
    for k in track["keys"]:
        if ch not in k or k[ch] is None:
            continue
        v = k[ch]
        val = float(v) if ch == "alpha" and isinstance(v, (int, float)) and not isinstance(v, bool) else None if ch == "alpha" else as_vec3(v)
        if val is None:
            raise Keep("non-literal %s value %r" % (ch, v))
        e = k.get("ease")
        if e is not None and rotvec.ease_key(str(e)) is None:
            raise Keep("unknown ease '%s'" % e)
        out.append((int(k["t"]), val, rotvec.ease_key(str(e)) if e is not None else None))
    out.sort(key=lambda x: x[0])
    if len({k[0] for k in out}) != len(out):
        raise Keep("duplicate tick in channel")
    return out


def segs_zero(ch, a, b):
    if ch == "alpha":
        return abs(b - a) < 1e-12
    if ch == "turn":
        return q_angle_deg(q_from_vec(a), q_from_vec(b)) < 1e-9
    return vlen(vsub(b, a)) < 1e-12


def seg_value_error(ch, x, y):
    if ch == "alpha":
        return abs(x - y)
    if ch == "turn":
        return q_angle_deg(x, y)
    return max(abs(p - q) for p, q in zip(x, y))


def to_cmp(ch, v):
    return q_from_vec(v) if ch == "turn" else v


def make_law(ch, ks, i, j):
    """Law covering keys i..j (j > i) as one constant-rate run, or raises Keep. ks entries: (tick, value, ease)."""
    ta, va, _ = ks[i]
    tb, vb, ez = ks[j]
    n = tb - ta
    eased = (j == i + 1) and not is_linear(ez)
    ease = ez if eased else None
    base = {"from": ta, "for": n}
    if ease:
        base["ease"] = ease
    if ch == "offset":
        d = vmul(vsub(vb, va), 1.0 / n)
        return dict(base, kind="move", start=va, velocity=d)
    if ch == "scale":
        d = vmul(vsub(vb, va), 1.0 / n)
        return dict(base, kind="grow", start=va, per_tick=d)
    if ch == "alpha":
        return dict(base, kind="fade", start=va, to=vb)
    # turn: axis and rate from the FIRST segment of the run
    t1, v1, _ = ks[i + 1]
    ax, ang = q_to_axis_angle(q_mul(q_from_vec(v1), q_conj(q_from_vec(va))))
    if ang > MAX_SPIN_CHUNK_DEG:
        raise Keep("slerp segment %.1f deg (>%d) at tick %d: axis ambiguous near 180" % (ang, MAX_SPIN_CHUNK_DEG, ta))
    rate = ang / (t1 - ta)
    if ease:
        rate = ang / n
    return dict(base, kind="spin", axis=ax, deg_per_tick=rate, start=va)


def run_fits(ch, ks, i, j):
    """Does ONE constant-rate law over keys i..j reproduce every interior key within TOL?"""
    try:
        law = make_law(ch, ks, i, j)
    except Keep:
        return False
    if j == i + 1:
        return True
    ex = expand_law(law)
    lerp = lerp_turn if ch == "turn" else lerp_num if ch == "alpha" else lerp_vec
    for t, v, _ in ks[i:j + 1]:
        got = sample(ex, t, lerp)
        if ch == "turn" and not isinstance(got, tuple):
            got = q_from_vec(got)
        if seg_value_error(ch, got, to_cmp(ch, v)) > TOL:
            return False
    return True


def fit_channel(track, ch):
    """-> dict(kind='laws', laws=[...], err=float, keys_in=int) | dict(kind='static') | dict(kind='keys', reason=..., keys_in)."""
    ks = channel_keys(track, ch)
    if not ks:
        return None
    if len(ks) == 1:
        return {"kind": "static", "keys_in": 1, "err": 0.0}
    if any(k[2] == "step" for k in ks):
        raise Keep("step ease (pose cut)")
    laws = []
    i = 0
    nseg = len(ks) - 1
    while i < nseg:
        a, b = ks[i], ks[i + 1]
        if segs_zero(ch, a[1], b[1]):
            j = i + 1  # hold: no law; extend while holding
            while j < nseg and segs_zero(ch, ks[j][1], ks[j + 1][1]):
                j += 1
            i = j
            continue
        if not is_linear(b[2]):
            laws.append(make_law(ch, ks, i, i + 1))
            i += 1
            continue
        j = i + 1
        while j < nseg and is_linear(ks[j + 1][2]) and not segs_zero(ch, ks[j][1], ks[j + 1][1]) and run_fits(ch, ks, i, j + 1):
            j += 1
        laws.append(make_law(ch, ks, i, j))
        i = j
    if not laws:
        return {"kind": "static", "keys_in": len(ks), "err": 0.0}
    if nseg >= 6 and len(laws) > 0.6 * nseg:
        raise Keep("irregular: %d laws for %d segments, no equal-rate runs (sampled / hitstop-baked swing)" % (len(laws), nseg))
    # round trip through the expander mirror and the sampler mirror
    ex = expand_laws(laws)
    lerp = lerp_turn if ch == "turn" else lerp_num if ch == "alpha" else lerp_vec
    err = 0.0
    for t in range(ks[0][0] - 1, ks[-1][0] + 2):
        want = sample(ks, t, lerp)
        have = sample(ex, t, lerp)
        if ch == "turn":
            want = want if isinstance(want, tuple) else q_from_vec(want)
            have = have if isinstance(have, tuple) else q_from_vec(have)
        err = max(err, seg_value_error(ch, have, want))
    if err >= TOL:
        raise Keep("round-trip error %.2e >= %.0e" % (err, TOL))
    return {"kind": "laws", "laws": laws, "err": err, "keys_in": len(ks)}


def fit_track(track):
    res = {}
    for ch in CHANNELS:
        try:
            r = fit_channel(track, ch)
        except Keep as e:
            r = {"kind": "keys", "reason": str(e), "keys_in": sum(1 for k in track["keys"] if k.get(ch) is not None), "err": 0.0}
        except ValueError as e:
            r = {"kind": "keys", "reason": str(e), "keys_in": sum(1 for k in track["keys"] if k.get(ch) is not None), "err": 0.0}
        if r is not None:
            res[ch] = r
    extra = [k for k in track["keys"] if k.get("color") is not None or k.get("block") is not None]
    if extra:
        res["_extra"] = {"kind": "keys", "reason": "color/block keys are not rate-lawable (steps / colour lerp)", "keys_in": len(extra), "err": 0.0}
    return res


# ---------------------------------------------------------------- report

def fmt_law(l):
    def r(v):
        return "[" + ", ".join("%.5g" % x for x in v) + "]" if isinstance(v, list) else "%.5g" % v
    keys = ("from", "for") + tuple(k for k in ("start", "velocity", "per_tick", "axis", "deg_per_tick", "to", "ease") if k in l)
    return "%s {%s}" % (l["kind"], ", ".join("%s: %s" % (k, r(l[k]) if k != "ease" else l[k]) for k in keys))


def main():
    ap = argparse.ArgumentParser()
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    ap.add_argument("--content", default=os.path.join(root, "content", "skills"))
    ap.add_argument("--out", default="/tmp/claude-1000/skill-engine/wpa/coverage.md")
    ap.add_argument("--laws-json", default=None, help="optional: dump the fitted laws per skill/track (for A3 review); content is never edited")
    args = ap.parse_args()

    per_skill = collections.OrderedDict()
    reasons = collections.Counter()
    reason_examples = {}
    dump = {}
    samples = []
    max_err = (0.0, "")
    tot = collections.Counter()
    for path in sorted(glob.glob(os.path.join(args.content, "*", "look.yaml"))):
        skill = os.path.basename(os.path.dirname(path))
        with open(path) as fh:
            doc = yaml.safe_load(fh)
        tracks = []
        walk_tracks(doc, tracks)
        if not tracks:
            continue
        row = collections.Counter()
        for tr in tracks:
            row["tracks"] += 1
            res = fit_track(tr)
            tid = tr.get("id") or tr.get("target")
            for ch, r in res.items():
                row["channels"] += 1
                if r["kind"] == "static":
                    row["static"] += 1
                    row["keys_static"] += r["keys_in"]
                elif r["kind"] == "laws":
                    row["law_channels"] += 1
                    row["laws"] += len(r["laws"])
                    row["keys_to_laws"] += r["keys_in"]
                    if r["err"] > max_err[0]:
                        max_err = (r["err"], "%s/%s/%s" % (skill, tid, ch))
                    row["_err"] = max(row["_err"], r["err"])
                    if len(samples) < 24 and (len(r["laws"]) >= 2 or r["laws"][0].get("ease")):
                        samples.append("%s / %s / %s: %s" % (skill, tid, ch, "; ".join(fmt_law(l) for l in r["laws"])))
                    dump.setdefault(skill, {}).setdefault(str(tid), {})[ch] = r["laws"]
                else:
                    row["kept_channels"] += 1
                    row["keys_kept"] += r["keys_in"]
                    key = re.sub(r"-?\d+(\.\d+)?(e-?\d+)?", "N", r["reason"].split(" at tick")[0].split("(")[0]).strip()
                    reasons[key] += 1
                    reason_examples.setdefault(key, "%s / %s / %s: %s %s" % (skill, tid, ch, PREFIX, r["reason"]))
        per_skill[skill] = row
        for k, v in row.items():
            if k != "_err":
                tot[k] += v

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    lines = ["# Rate-law coverage (tools/skill-convert/ratefit.py)", "",
             "Source: %s, %d skills with `op: track`. TOL = %.0e (blocks / scale / opacity / degrees). Content is not edited." % (args.content, len(per_skill), TOL), "",
             "## Totals", "",
             "- tracks: %d, channels (offset/turn/scale/alpha + colour/block extras): %d" % (tot["tracks"], tot["channels"]),
             "- channels as laws: %d (%d keys -> %d laws)" % (tot["law_channels"], tot["keys_to_laws"], tot["laws"]),
             "- channels single-key static (no law needed): %d" % tot["static"],
             "- channels kept as keys (CONVERT-KEYS-EXACT): %d (%d keys)" % (tot["kept_channels"], tot["keys_kept"]),
             "- max round-trip error over all emitted laws: %.3e (%s)" % max_err, "",
             "## Reasons for kept keys", "", "| count | reason | example |", "|---|---|---|"]
    for r, n in reasons.most_common():
        lines.append("| %d | %s | %s |" % (n, r.strip(), reason_examples[r].replace("|", "/")))
    lines += ["", "## Per skill", "",
              "| skill | tracks | channels | law channels | laws | keys->laws | kept channels | kept keys | static | max err |",
              "|---|---|---|---|---|---|---|---|---|---|"]
    for s, r in per_skill.items():
        lines.append("| %s | %d | %d | %d | %d | %d | %d | %d | %d | %.1e |" % (
            s, r["tracks"], r["channels"], r["law_channels"], r["laws"], r["keys_to_laws"], r["kept_channels"], r["keys_kept"], r["static"], r["_err"]))
    lines += ["", "## Sample laws (multi-law or eased channels)", ""] + ["- " + s for s in samples]
    with open(args.out, "w") as fh:
        fh.write("\n".join(lines) + "\n")
    if args.laws_json:
        with open(args.laws_json, "w") as fh:
            json.dump(dump, fh, indent=1)
    print("wrote %s: %d law channels (%d laws from %d keys), %d kept channels, %d static, max err %.2e" % (
        args.out, tot["law_channels"], tot["laws"], tot["keys_to_laws"], tot["kept_channels"], tot["static"], max_err[0]))


if __name__ == "__main__":
    main()
