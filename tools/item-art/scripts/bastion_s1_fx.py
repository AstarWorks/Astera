#!/usr/bin/env python3
"""Writes content/vfx/bastion-s1-fx-high.yaml and bastion-s1-fx-low.yaml (elements only) for the bastion-greatsword S1
pilot, and prints the display budget (alive entities per effect tick). Sprite blocks: width = font height / 40 * scale,
so a 256 px sprite is 6.4 blocks * scale and a 128 px sprite is 3.2 blocks * scale.

    python3 tools/item-art/scripts/bastion_s1_fx.py
"""
import os
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bastion_s1_sprites import SWEEP_HALF, SWEEPS, sweep_euler  # noqa: E402  (one geometry for art + placement)

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))


def s256(w):
    return w / 6.4


def s128(w):
    return w / 3.2


def sprite(id_, name, start, end, kfs, frame_ticks=1, color=None, loop=False):
    """Sprite keyframes cannot animate colour/alpha (only quads can), so a fade (alpha -> 0) becomes a collapse of
    the scale (thin sliver, then gone at end_tick); other alpha-only keyframes are dropped."""
    kind = {"type": "sprite", "sprite": name, "frame_ticks": frame_ticks, "loop": loop, "see_through": True}
    if color:
        kind["color"] = color
    out, last, cur = [], None, {}
    for k in kfs:
        k = dict(k)
        a = k.pop("alpha", None)
        for f in ("translate", "rotate", "scale"):  # a keyframe that omits a field resets it: carry the last value
            if f in k:
                cur[f] = k[f]
            elif f in cur and len(k) > 1:
                k[f] = cur[f]
        if "scale" in k:
            last = k["scale"]
        if a == 0.0 and last is not None:
            k["scale"] = [last[0] * 0.85, last[1] * 0.04, 1.0]
            k["ease"] = k.get("ease", "in_quad")
        if len(k) > 1:
            out.append(k)
    return {"id": id_, "kind": kind, "billboard": "center", "brightness": 15, "start_tick": start, "end_tick": end,
            "keyframes": out}, ("sprite", start, end)


def kf(t, translate=None, rotate=None, scale=None, ease=None, alpha=None):
    d = {"t": t}
    if translate is not None: d["translate"] = translate
    if rotate is not None: d["rotate"] = rotate
    if scale is not None: d["scale"] = scale
    if alpha is not None: d["alpha"] = alpha
    if ease: d["ease"] = ease
    return d


def quad(id_, color, start, origin, emit):
    el = {"id": id_, "kind": {"type": "quad", "color": color, "alpha": 1.0}, "brightness": 15, "start_tick": start,
          "keyframes": [{"t": 0, "translate": origin}], "emit": emit}
    return el, ("emit", start, emit)


def curve(values):
    """Evenly spaced list -> curve keys [{t, v}] (emit *_over_life format)."""
    n = len(values)
    return [{"t": round(i / (n - 1), 4) if n > 1 else 0, "v": v} for i, v in enumerate(values)]


def emit(count, rate, direction, spread, speed, life, size, gravity, colors, alphas, drag=0.04, sol=(1.0, 0.3), spin=0):
    d = {"count": count, "rate_per_tick": rate, "speed": speed, "direction": direction, "spread_deg": spread,
         "gravity": gravity, "drag": drag, "life_ticks": life, "size": size, "size_over_life": curve(list(sol))}
    if colors: d["color_over_life"] = curve(colors)
    d["alpha_over_life"] = curve(alphas)
    if spin: d["spin_over_life"] = [{"t": 0, "v": spin}]
    return d


SPARK_C = ["#FFC040", "#FF7A12", "#B03000"]


import math

ORANGE = "#FF8A1E"
DARK_CHIP = "#FF2A2218"
SMOKE_TINT = "#A0A8A8B0"


def sweep(id_, name, start, end):
    """Round 7: swing-plane smear (fixed billboard) lying in the r7 blade plane, front + back pair (a TextDisplay is
    only trusted from its +Z side; the back strip is mirrored so both draw the same 3D arc). Flipbook = the wipe
    following the blade, then the fade; frame k shows at element tick k + 1 (frame 0 at spawn and spawn + 1)."""
    c = [round(float(v), 3) for v in SWEEPS[name]["centre"]]
    sc = 2 * SWEEP_HALF / 6.4
    out = []
    for suffix, back in (("", False), ("-b", True)):
        el, m = sprite(id_ + suffix, "bastion-s1-" + name + suffix, start, end, [
            kf(0, c, sweep_euler(name, back), [sc, sc, 1.0])])
        el["billboard"] = "fixed"
        out.append((el, m))
    return out


def spark(id_, start, p, theta, length, dist, life=4, color=ORANGE):
    """Orange spark streak (tinted white-hot streak sprite): flies along (0, sin, cos) of theta, shrinks to a needle."""
    r = math.radians(theta)
    d = [0.0, math.sin(r) * dist, math.cos(r) * dist]
    pos = lambda f: [round(p[0], 3), round(p[1] + d[1] * f, 3), round(p[2] + d[2] * f, 3)]
    return sprite(id_, "bastion-s1-streak", start, start + life, [
        kf(0, pos(0.0), [0, 0, theta], [s256(length * 0.35), 0.05, 1.0]),
        kf(1, pos(0.4), scale=[s256(length), 0.05, 1.0], ease="out_expo"),
        kf(life - 1, pos(1.0), scale=[s256(length * 0.45), 0.012, 1.0], ease="in_quad")], color=color)


def chip(id_, start, p, drift, roll, life=5):
    """Dark debris shard (sliver sprite tinted brown-black) that drifts and falls."""
    q = [round(p[0] + drift[0], 3), round(p[1] + drift[1], 3), round(p[2] + drift[2], 3)]
    return sprite(id_, "bastion-s1-sliver", start, start + life, [
        kf(0, p, [0, 0, roll], [s128(0.34), s128(0.34), 1.0]),
        kf(life - 1, q, [0, 0, roll + 70], [s128(0.3), s128(0.3), 1.0], ease="out_quad")], color=DARK_CHIP)


def smoke_up(id_, start, end, p, w0, w1, roll=0):
    return sprite(id_, "bastion-s1-smoke", start, end, [
        kf(0, p, [0, 0, roll], [s256(w0), s256(w0), 1.0]),
        kf(end - start, [p[0], p[1] + 0.35, p[2]], [0, 0, roll + 25], [s256(w1), s256(w1), 1.0], ease="out_quad")],
        frame_ticks=2, color=SMOKE_TINT)


def high():
    els = []
    # Round 7: the arch is the smear of the r7 overhead chop, IN its plane (vertical, contains forward, rolled 22 deg,
    # through the pivot), 154 deg from behind-overhead (alpha 137.5) to the forward-low contact (alpha -16.2: tip phi 6, pitch -15, y 0.11). Spawn fx 1
    # (skill 4): frame 0 ticks 4-5 (blade straight up at 5), frame 1 tick 6, frame 2 = full arc at contact tick 7, hold 8,
    # fade 9-13. The head curls inside 0.8 blocks of the opponent eye (z 2.4) instead of passing through it.
    els += sweep("s1h1-arch", "arch", 1, 11)
    # white-hot cut streak on the chop line (steep, the chop comes down through the target line): roll 80 is the
    # compromise between the side camera (chop tangent ~100) and first person (plane seen edge-on, ~68)
    els.append(sprite("s1h1-streak-main", "bastion-s1-streak", 4, 11, [
        kf(0, [-0.55, 1.3, 1.5], [0, 0, 80], [s256(0.6), 0.3, 1.0]),
        kf(1, scale=[s256(3.0), 0.3, 1.0], ease="out_expo"),
        kf(6, scale=[s256(2.3), 0.03, 1.0], ease="in_quad")]))
    # flash at the contact point on the chop plane (chop plane at y 1.15 is x -0.50 for pivot [-0.35, 0.77, 0.29]; z pulled to 1.8, the
    # opponent eye is at z 2.4); dark star under the glint
    els.append(sprite("s1h1-flash-dark", "bastion-s1-glint", 4, 8, [
        kf(0, [-0.5, 1.15, 1.83], [0, 0, 0], [s128(0.1), s128(0.1), 1.0]),
        kf(1, scale=[s128(1.5), s128(1.5), 1.0], ease="out_back"),
        kf(3, scale=[s128(1.0), s128(0.05), 1.0], ease="out_quad")], color="#8C000000"))
    els.append(sprite("s1h1-flash", "bastion-s1-glint", 4, 8, [
        kf(0, [-0.5, 1.15, 1.8], [0, 0, 0], [s128(0.1), s128(0.1), 1.0]),
        kf(1, scale=[s128(1.2), s128(1.2), 1.0], ease="out_back"),
        kf(3, scale=[s128(0.7), s128(0.04), 1.0], ease="out_quad")]))
    # orange spark streaks off the contact over ticks 4..10 (staggered pairs, distinct origins and angles)
    P = [-0.5, 1.15, 1.55]
    for i, (st, dx, dy, th, ln, ds) in enumerate([
            (4, 0.0, 0.0, 22, 1.0, 0.8), (4, 0.15, -0.1, 4, 1.3, 0.8), (5, -0.15, 0.1, 38, 0.8, 0.7),
            (5, 0.1, -0.2, 162, 0.9, 0.9), (6, -0.1, 0.05, -14, 1.1, 0.8), (6, 0.2, 0.15, 14, 0.7, 0.7)]):
        els.append(spark(f"s1h1-spark-{i + 1}", st, [P[0] + dx, P[1] + dy, P[2]], th, ln, ds))
    # dark debris mixed in
    for i, (st, dr, roll) in enumerate([(5, [0.2, 0.5, 0.35], 35), (5, [-0.2, 0.2, -0.3], -50), (6, [0.1, 0.4, 0.45], 120)]):
        els.append(chip(f"s1h1-chip-{i + 1}", st, [0.1 * i - 0.1, 1.15 - 0.1 * i, 1.7], dr, roll))
    # smoke mass after the flash + floor swirl
    els.append(smoke_up("s1h1-smoke-up", 5, 12, [0.0, 0.85, 1.7], 0.9, 1.4))
    els.append(sprite("s1h1-smoke-a", "bastion-s1-smoke", 5, 12, [
        kf(0, [0.3, 0.18, 1.5], [0, 0, 0], [s256(1.3), s256(1.3) * 0.25, 1.0]),
        kf(7, scale=[s256(1.9), s256(1.9) * 0.25, 1.0], ease="out_quad")], frame_ticks=2, color=SMOKE_TINT))
    return {"id": "bastion-s1-fx-high", "duration_ticks": 20, "orient": "yaw", "follow": False, "sample_ticks": 1,
            "elements": [e for e, _ in els]}, [m for _, m in els]


def low():
    els = []
    # ONE connected ragged ink mass of horizontal brush tongues (reference e00-e02), camera-facing (billboard center),
    # hip-to-chest height (0.0-1.5) so it reads as a mass from eye level instead of a flat floor slab; tips translucent.
    # Round 8 flipbook: f0 splash on fx 3-4 (contact), full fx 5-6, dissolve fx 7-9 (3 frames eroding tips first).
    # r9 (real client r6: ~40% of the compare close-up and of the first person t31-37): 4.0 -> 4.4 wide (was 5.0 -> 5.6),
    # ratio 0.42, centre y 0.5 / z 1.35 (was 0.75 / 1.2). Perspective coverage (alpha > 0.1, 16:9): caster FP pitch 0
    # <= ~12%, pitch -10 <= ~25% (was 30 / 43); compare close-up (intent t30 key, fov 45) <= ~12% (was 20); opponent
    # eye (z 1.7) sees it below its frame. Scratch tool: inkcov.py in the r9 report.
    els.append(sprite("s1h2-ink", "bastion-s1-ink", 3, 9, [
        kf(0, [0, 0.5, 1.35], [0, 0, 0], [s256(4.0), s256(4.0) * 0.42, 1.0]),
        kf(2, scale=[s256(4.4), s256(4.4) * 0.42, 1.0], ease="out_quad")]))
    # Round 7b: the low ring is the smear of the r7 ring swing, IN its plane (10 deg tilt, lowest toward phi 60 = left-
    # front at the floor pitch -15 / y 0.11, centre on the pivot axis at tip pitch -5, radius 2.535 = tip; fitted to the
    # r7 keys 24-30 within 0.12 blocks), turning the blade's way (left-back -> back -> right -> front), 221 deg from
    # alpha 158 to the contact alpha 379.1. Spawn fx 0 (skill 27): frame 0 ticks 27-28
    # (blade at 212 on 28), 265 on 29, 321 on 30, full at contact 31, hold 32, fade 33-38. The front curls to z <= 2.3.
    els += sweep("s1h2-ring", "ring", 0, 12)
    els.append(sprite("s1h2-streak-main", "bastion-s1-streak", 4, 10, [
        kf(0, [0, 1.3, 1.1], [0, 0, -4], [s256(0.6), 0.32, 1.0]),
        kf(1, scale=[s256(4.6), 0.32, 1.0], ease="out_expo"),
        kf(6, scale=[s256(3.6), 0.04, 1.0], ease="in_quad")]))
    for i, (start, x, y, z, w, col) in enumerate([
            (3, 0.5, 0.6, 0.8, 2.4, None), (4, -0.5, 1.05, 1.0, 2.0, "#FFE070")]):
        els.append(sprite(f"s1h2-line-{i + 1}", "bastion-s1-streak", start, start + 3, [
            kf(0, [x, y, z + 0.4], [0, 0, 0], [s256(0.5), 0.1, 1.0]),
            kf(1, scale=[s256(w), 0.1, 1.0], ease="out_expo"),
            kf(2, scale=[s256(w * 0.7), 0.02, 1.0], ease="in_quad")], color=col))
    els.append(sprite("s1h2-flash-dark", "bastion-s1-glint", 4, 8, [
        kf(0, [0, 1.2, 1.18], [0, 0, 0], [s128(0.1), s128(0.1), 1.0]),
        kf(1, scale=[s128(2.0), s128(2.0), 1.0], ease="out_back"),
        kf(3, scale=[s128(1.4), s128(0.06), 1.0], ease="out_quad")], color="#8C000000"))
    els.append(sprite("s1h2-flash", "bastion-s1-glint", 4, 8, [
        kf(0, [0, 1.2, 1.15], [0, 0, 0], [s128(0.1), s128(0.1), 1.0]),
        kf(1, scale=[s128(1.6), s128(1.6), 1.0], ease="out_back"),
        kf(3, scale=[s128(0.9), s128(0.05), 1.0], ease="out_quad")]))
    P = [0.0, 1.2, 0.85]  # r7: hit-2 dash stops 1.7 short of the target = the opponent eye; sparks fly <= 0.9 forward
    for i, (st, dx, dy, th, ln, ds) in enumerate([
            (4, 0.0, 0.0, 24, 1.2, 0.8), (4, 0.15, -0.15, 6, 1.4, 0.8), (5, -0.15, 0.1, 40, 0.9, 0.7),
            (5, 0.1, 0.2, 165, 1.0, 0.9), (6, -0.1, -0.1, -12, 1.2, 0.8), (6, 0.2, 0.05, 18, 0.8, 0.7)]):
        els.append(spark(f"s1h2-spark-{i + 1}", st, [P[0] + dx, P[1] + dy, P[2]], th, ln, ds))
    for i, (st, dr, roll) in enumerate([(5, [0.2, 0.5, 0.35], 35), (6, [-0.2, 0.2, -0.3], -50)]):  # r7: 2 chips (budget: the ring is a front + back pair)
        els.append(chip(f"s1h2-chip-{i + 1}", st, [0.1 * i - 0.1, 1.1 - 0.1 * i, 1.1], dr, roll))
    els.append(smoke_up("s1h2-smoke-up", 6, 13, [0.0, 0.85, 1.1], 1.0, 1.6, roll=180))
    els.append(sprite("s1h2-smoke", "bastion-s1-smoke", 6, 14, [
        kf(0, [0.3, 0.18, 1.1], [0, 0, 0], [s256(1.5), s256(1.5) * 0.25, 1.0]),
        kf(7, scale=[s256(2.2), s256(2.2) * 0.25, 1.0], ease="out_quad")], frame_ticks=2, color=SMOKE_TINT))
    return {"id": "bastion-s1-fx-low", "duration_ticks": 18, "orient": "yaw", "follow": False, "sample_ticks": 1,
            "elements": [e for e, _ in els]}, [m for _, m in els]


def budget(meta, duration):
    rows = []
    for t in range(duration):
        n = {"sprite": 0, "emit": 0}
        for m in meta:
            if m[0] == "sprite":
                if m[1] <= t <= m[2]:  # conservative: end tick counted as alive
                    n["sprite"] += 1
            else:
                _, start, e = m
                lo, hi = e["life_ticks"]
                for slot in range(e["count"]):
                    spawn = start + slot // e["rate_per_tick"]
                    if spawn <= t <= spawn + hi:  # conservative: longest life
                        n["emit"] += 1
        rows.append((t, n["sprite"], n["emit"], n["sprite"] + n["emit"]))
    return rows


class Dumper(yaml.SafeDumper):
    pass


def _float(d, v):
    t = f"{v:.4g}"
    if "." not in t and "e" not in t:
        t += ".0"
    return d.represent_scalar("tag:yaml.org,2002:float", t)


Dumper.add_representer(float, _float)
Dumper.ignore_aliases = lambda self, data: True


def write(path, header, doc):
    with open(os.path.join(ROOT, path), "w") as f:
        f.write(header)
        yaml.dump(doc, f, Dumper=Dumper, sort_keys=False, default_flow_style=None, width=140)


if __name__ == "__main__":
    for fn, name, header in ((high, "bastion-s1-fx-high", "# Bastion S1 hit 1 (video 11.933-12.5 s): gold chop-plane arch smear (front + back) + cut streak + orange spark-streak sprites + dark chips + smoke. Played at skill tick 3, contact = effect tick 4.\n# Generated by tools/item-art/scripts/bastion_s1_fx.py; sprites from tools/item-art/scripts/bastion_s1_sprites.py. Elements only (rig is the animator's).\n"),
                             (low, "bastion-s1-fx-low", "# Bastion S1 hit 2 (video 13.1-13.7 s): yellow ink backdrop, low ring smear in the swing plane (front + back), streak + speed lines, flash, particles. Played at skill tick 27, contact = effect tick 4.\n# Generated by tools/item-art/scripts/bastion_s1_fx.py. Elements only.\n")):
        doc, meta = fn()
        write(f"content/vfx/{name}.yaml", header, doc)
        print(name, "elements:", len(doc["elements"]))
        rows = budget(meta, doc["duration_ticks"])
        print(" tick sprites particles total:", " | ".join(f"e{t}:{a}+{b}={c}" for t, a, b, c in rows))
        print(" peak:", max(r[3] for r in rows))
