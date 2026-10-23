#!/usr/bin/env python3
"""Writes content/vfx/bastion-s1-fx-high.yaml and bastion-s1-fx-low.yaml (elements only) for the bastion-greatsword S1
pilot, and prints the display budget (alive entities per effect tick). Sprite blocks: width = font height / 40 * scale,
so a 256 px sprite is 6.4 blocks * scale and a 128 px sprite is 3.2 blocks * scale.

    python3 tools/item-art/scripts/bastion_s1_fx.py
"""
import os

import yaml

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


def emit(count, rate, direction, spread, speed, life, size, gravity, colors, alphas, drag=0.04, sol=(1.0, 0.3), spin=0):
    d = {"count": count, "rate_per_tick": rate, "speed": speed, "direction": direction, "spread_deg": spread,
         "gravity": gravity, "drag": drag, "life_ticks": life, "size": size, "size_over_life": list(sol)}
    if colors: d["color_over_life"] = colors
    d["alpha_over_life"] = alphas
    if spin: d["spin_deg_per_tick"] = spin
    return d


SPARK_C = ["#FFC040", "#FF7A12", "#B03000"]


def high():
    els = []
    # core: crescents (frame f2 = the peak on e4)
    els.append(sprite("s1h1-cres-a", "bastion-s1-crescent", 2, 10, [
        kf(0, [0, 1.9, 2.7], [0, 0, 20], [s256(3.6), s256(3.6), 1.0]),
        kf(2, scale=[s256(4.4), s256(4.4), 1.0], ease="out_quad"),
        kf(6, alpha=1.0), kf(8, alpha=0.0, ease="in_quad")]))
    els.append(sprite("s1h1-cres-b", "bastion-s1-crescent", 3, 9, [
        kf(0, [0.4, 1.4, 3.1], [0, 0, -20], [s256(2.6), s256(2.6), 1.0]),
        kf(2, scale=[s256(3.2), s256(3.2), 1.0], ease="out_quad"),
        kf(4, alpha=1.0), kf(6, alpha=0.0, ease="in_quad")]))
    # low ring: one smooth ellipse around the target, frames grow then thin (6 ticks)
    els.append(sprite("s1h1-ring", "bastion-s1-ring", 4, 10, [
        kf(0, [0, 0.85, 2.5], [0, 0, 8], [s256(5.0), s256(5.0), 1.0]),
        kf(6, alpha=0.0, ease="in_quad")]))
    # core: white-hot streaks, grow 2+ blocks per tick
    els.append(sprite("s1h1-streak-main", "bastion-s1-streak", 4, 12, [
        kf(0, [0, 1.1, 2.8], [0, 0, -6], [s256(0.6), 0.3, 1.0], alpha=1.0),
        kf(1, scale=[s256(5.0), 0.3, 1.0], ease="out_expo"),
        kf(3, alpha=1.0),
        kf(5, scale=[s256(4.2), 0.1, 1.0], alpha=0.0, ease="in_quad")]))
    els.append(sprite("s1h1-streak-2", "bastion-s1-streak", 5, 10, [
        kf(0, [0, 1.3, 2.6], [0, 0, -6], [s256(0.6), 0.14, 1.0], alpha=1.0),
        kf(1, scale=[s256(3.2), 0.14, 1.0], ease="out_expo"),
        kf(4, scale=[s256(2.6), 0.06, 1.0], alpha=0.0, ease="in_quad")], color="#FFE070"))
    # flash: black star under the glint, both popping on the damage tick
    els.append(sprite("s1h1-flash-dark", "bastion-s1-glint", 4, 8, [
        kf(0, [0, 1.2, 2.43], [0, 0, 0], [s128(0.1), s128(0.1), 1.0], alpha=0.55),
        kf(1, scale=[s128(1.5), s128(1.5), 1.0], ease="out_back"),
        kf(3, scale=[s128(1.0), s128(1.0), 1.0], alpha=0.0, ease="out_quad")], color="#8C000000"))
    els.append(sprite("s1h1-flash", "bastion-s1-glint", 4, 8, [
        kf(0, [0, 1.2, 2.4], [0, 0, 0], [s128(0.1), s128(0.1), 1.0], alpha=1.0),
        kf(1, scale=[s128(1.2), s128(1.2), 1.0], ease="out_back"),
        kf(3, scale=[s128(0.7), s128(0.7), 1.0], alpha=0.0, ease="out_quad")]))
    # spark: slivers flicked off the left crescent tip, staggered e3 e4 e5, each lives 3 ticks (frames flip f0 f1 f2)
    for i, (start, p, r) in enumerate([(3, [1.3, 1.7, 2.7], 30), (4, [1.5, 1.9, 2.8], -20)]):
        q = [p[0] + 0.18, p[1] + 0.12, p[2] + 0.2]
        els.append(sprite(f"s1h1-sliver-{i + 1}", "bastion-s1-sliver", start, start + 3, [
            kf(0, p, [0, 0, r], [s128(0.3), s128(0.3), 1.0], alpha=1.0),
            kf(2, scale=[s128(0.3), s128(0.3), 1.0], ease="out_quad", alpha=0.0)]))
    # smoke: floor swirl hand-over after the flash
    els.append(sprite("s1h1-smoke-a", "bastion-s1-smoke", 4, 12, [
        kf(0, [0.5, 0.18, 2.2], [0, 0, 0], [s256(2.4), s256(2.4) * 0.2, 1.0], alpha=0.6),
        kf(7, scale=[s256(3.6), s256(3.6) * 0.2, 1.0], alpha=0.0, ease="out_quad")], frame_ticks=2))
    els.append(sprite("s1h1-smoke-b", "bastion-s1-smoke", 7, 14, [
        kf(0, [0.2, 0.18, 1.2], [0, 0, 180], [s256(2.0), s256(2.0) * 0.2, 1.0], alpha=0.6),
        kf(7, scale=[s256(3.2), s256(3.2) * 0.2, 1.0], alpha=0.0, ease="out_quad")], frame_ticks=2))
    # display particles: orange sparks (staggered pairs) and dark specks mixed in
    els.append(quad("s1h1-sparks", "#FFC040", 4, [0, 1.0, 2.2],
                    emit(4, 2, "forward", 28, [0.35, 0.6], [6, 8], [0.04, 0.09], 0.02, SPARK_C, [1.0, 0.9, 0.0])))
    els.append(quad("s1h1-specks", "#1A1206", 5, [0, 1.1, 2.1],
                    emit(2, 1, "outward", 90, [0.06, 0.18], [6, 8], [0.05, 0.1], 0.03, None, [0.9, 0.9, 0.0])))
    return {"id": "bastion-s1-fx-high", "duration_ticks": 20, "orient": "yaw", "follow": False, "sample_ticks": 1,
            "elements": [e for e, _ in els]}, [m for _, m in els]


def low():
    els = []
    ink_kf = lambda pos, w, h, a0: [
        kf(0, pos, [0, 0, 0], [s256(w), s256(h), 1.0]), kf(a0, alpha=0.0, ease="in_quad")]
    els.append(sprite("s1h2-ink-a", "bastion-s1-ink", 2, 6, ink_kf([0, 1.1, 3.4], 9.5, 3.4, 4)))
    els.append(sprite("s1h2-ink-b", "bastion-s1-ink", 3, 6, ink_kf([0.3, 0.8, 3.0], 7.0, 2.4, 3)))
    # hit 2 is a LOW thick ring (reference e05/e06), not hit 1's high crescent
    els.append(sprite("s1h2-ring-a", "bastion-s1-ring", 3, 10, [
        kf(0, [0, 0.5, 2.6], [0, 0, 0], [s256(7.0), s256(7.0), 1.0]),
        kf(7, alpha=0.0, ease="in_quad")]))
    els.append(sprite("s1h2-ring-b", "bastion-s1-ring", 4, 9, [
        kf(0, [0.2, 0.8, 3.0], [0, 0, -6], [s256(4.8), s256(4.8), 1.0]),
        kf(5, alpha=0.0, ease="in_quad")]))
    els.append(sprite("s1h2-streak-main", "bastion-s1-streak", 4, 10, [
        kf(0, [0, 1.3, 3.05], [0, 0, -4], [s256(0.6), 0.32, 1.0], alpha=1.0),
        kf(1, scale=[s256(5.5), 0.32, 1.0], ease="out_expo"),
        kf(4, alpha=1.0),
        kf(6, scale=[s256(4.8), 0.1, 1.0], alpha=0.0, ease="in_quad")]))
    # speed lines: staggered e3 e4 e7, each lives 3 ticks, each lives 3 ticks
    for i, (start, x, y, z, w, col) in enumerate([
            (3, 1.0, 0.55, 0.8, 2.4, None), (4, -1.0, 1.05, 1.1, 2.0, "#FFE070")]):
        els.append(sprite(f"s1h2-line-{i + 1}", "bastion-s1-streak", start, start + 3, [
            kf(0, [x, y, z + 1.0], [0, 0, 0], [s256(0.5), 0.1, 1.0], alpha=1.0),
            kf(1, scale=[s256(w), 0.1, 1.0], ease="out_expo"),
            kf(2, scale=[s256(w), 0.1, 1.0], alpha=0.0, ease="in_quad")], color=col))
    els.append(sprite("s1h2-flash-dark", "bastion-s1-glint", 4, 8, [
        kf(0, [0, 1.2, 1.73], [0, 0, 0], [s128(0.1), s128(0.1), 1.0], alpha=0.55),
        kf(1, scale=[s128(2.0), s128(2.0), 1.0], ease="out_back"),
        kf(3, scale=[s128(1.4), s128(1.4), 1.0], alpha=0.0, ease="out_quad")], color="#8C000000"))
    els.append(sprite("s1h2-flash", "bastion-s1-glint", 4, 8, [
        kf(0, [0, 1.2, 1.7], [0, 0, 0], [s128(0.1), s128(0.1), 1.0], alpha=1.0),
        kf(1, scale=[s128(1.6), s128(1.6), 1.0], ease="out_back"),
        kf(3, scale=[s128(0.9), s128(0.9), 1.0], alpha=0.0, ease="out_quad")]))
    els.append(sprite("s1h2-smoke", "bastion-s1-smoke", 6, 14, [
        kf(0, [0.3, 0.18, 1.4], [0, 0, 0], [s256(2.8), s256(2.8) * 0.2, 1.0], alpha=0.6),
        kf(7, scale=[s256(4.0), s256(4.0) * 0.2, 1.0], alpha=0.0, ease="out_quad")], frame_ticks=2))
    els.append(quad("s1h2-sparks", "#FFC040", 4, [0, 1.0, 1.5],
                    emit(4, 2, "forward", 30, [0.4, 0.7], [6, 8], [0.04, 0.09], 0.02, SPARK_C, [1.0, 0.9, 0.0])))
    els.append(quad("s1h2-specks", "#1A1206", 5, [0, 0.15, 0.5],
                    emit(3, 1, "custom[0,0.5,1]", 60, [0.08, 0.2], [8, 10], [0.05, 0.1], 0.03, None, [0.9, 0.9, 0.0])))
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
    for fn, name, header in ((high, "bastion-s1-fx-high", "# Bastion S1 hit 1 (video 11.933-12.5 s): painted 2D layers + display particles. Played at skill tick 3, contact = effect tick 4.\n# Generated by tools/item-art/scripts/bastion_s1_fx.py; sprites from tools/item-art/scripts/bastion_s1_sprites.py. Elements only (rig is the animator's).\n"),
                             (low, "bastion-s1-fx-low", "# Bastion S1 hit 2 (video 13.1-13.7 s): yellow ink backdrop, low gold crescents, streak + speed lines, flash, particles. Played at skill tick 27, contact = effect tick 4.\n# Generated by tools/item-art/scripts/bastion_s1_fx.py. Elements only.\n")):
        doc, meta = fn()
        write(f"content/vfx/{name}.yaml", header, doc)
        print(name, "elements:", len(doc["elements"]))
        rows = budget(meta, doc["duration_ticks"])
        print(" tick sprites particles total:", " | ".join(f"e{t}:{a}+{b}={c}" for t, a, b, c in rows))
        print(" peak:", max(r[3] for r in rows))
