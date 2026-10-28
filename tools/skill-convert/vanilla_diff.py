#!/usr/bin/env python3
"""Compare two vanilla-output dumps (SKL-17).

Usage: vanilla_diff.py <v1-dir> <head-dir> [--out report.md]

Each dir holds one `<skill>.jsonl` per skill: the display spawns / writes / removals and `spawnParticle` calls a cast
hands to Bukkit (v1: the reference dump of `d41b82f1e`; HEAD: `VanillaDump` of the game-sim). Both are reduced to a
per-tick state and compared; the report lists per skill the first divergent tick and field, grouped by field.

State of one display at a tick, in caster-relative world terms:
  alive, kind, item/model/text, origin (entity position + translation, rotation-free), left quaternion, scale,
  billboard, interpolation duration, teleport duration.
"""
import json, os, re, sys, collections

EPS = 1e-3


def load(path):
    rows = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def close(a, b):
    if isinstance(a, (list, tuple)) and isinstance(b, (list, tuple)):
        return len(a) == len(b) and all(close(x, y) for x, y in zip(a, b))
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return abs(a - b) <= EPS
    return a == b


def quat_sign(q):
    # q and -q are the same rotation
    for c in q:
        if abs(c) > 1e-6:
            return q if c > 0 else [-x for x in q]
    return q


def reduce(rows, engine):
    """-> (displays: {ordinal: dict}, states: {tick: {ordinal: state}}, particles: {tick: [tuples]})"""
    meta = {}
    feet_y = 0.0
    for r in rows:
        if r.get("e") == "meta":
            feet_y = r.get("feetY", 0.0)
    displays = {}
    events = collections.defaultdict(list)
    particles = collections.defaultdict(list)
    caster = {}
    for r in rows:
        e = r.get("e")
        t = r.get("t")
        if e == "caster":
            caster[t] = (r["x"], r["y"] - feet_y, r["z"])
        elif e == "particle":
            particles[t].append((r["key"], round(r["x"], 3), round(r["y"] - feet_y, 3), round(r["z"], 3), r["count"],
                                 round(r["ox"], 3), round(r["oy"], 3), round(r["oz"], 3), round(r["extra"], 3), r["data"]))
        elif e in ("spawn", "write", "update", "teleport", "style", "swap", "remove", "plate", "viewrange"):
            events[t].append(r)
    return displays, events, particles, feet_y, caster


def displays_over_time(rows, engine, ticks):
    """Replays events; returns per tick {ordinal: state dict} for live displays."""
    _, events, particles, feet_y, caster = reduce(rows, engine)
    cur = {}
    out = {}
    spawn_info = {}
    for t in range(0, ticks + 1):
        for r in events.get(t, []):
            e = r["e"]
            i = r.get("id")
            if e == "spawn":
                sp = {k: r.get(k) for k in ("kind", "item", "model", "text", "bg", "opacity", "br", "rigshade", "rgb", "seeThrough", "shadow")}
                spawn_info[i] = sp
                st = {"alive": True, "kind": r["kind"], "item": r.get("item"), "model": r.get("model"), "text": r.get("text"),
                      "bg": r.get("bg"), "opacity": r.get("opacity"), "br": r.get("br"), "rigshade": r.get("rigshade"),
                      "rgb": r.get("rgb"), "seeThrough": r.get("seeThrough"), "shadow": r.get("shadow"),
                      "spawn_tick": t, "pos": None, "yaw": 0.0, "pitch": 0.0, "tr": [0, 0, 0], "left": [0, 0, 0, 1],
                      "scale": [1, 1, 1], "bb": "fixed", "interp": 0, "tp": 0, "anch": False}
                cur[i] = st
                if engine == "v1":
                    st["pos"] = [r["x"], r["y"] - (0 if r.get("anch") else feet_y), r["z"]]
                    st["tr"] = r["tr"]; st["left"] = r["left"]; st["scale"] = r["scale"]
                    st["bb"] = r.get("bb"); st["anch"] = bool(r.get("anch"))
                    st["br"] = r.get("br"); st["rigshade"] = r.get("rigshade")
            elif e == "write":  # HEAD
                st = cur.get(i)
                if st is None:
                    continue
                st["pos"] = [r["x"], r["y"], r["z"]]
                st["yaw"] = r["yaw"]; st["pitch"] = r["pitch"]
                st["tr"] = r["tr"]; st["left"] = r["left"]; st["scale"] = r["scale"]
                st["bb"] = r["bb"]; st["interp"] = r["interp"]; st["tp"] = r["tp"]
                st["anch"] = r.get("anch") is not None
                st["anch_feet"] = r.get("anch")
            elif e == "update":  # v1
                st = cur.get(i)
                if st is None:
                    continue
                st["tr"] = r["tr"]; st["left"] = r["left"]; st["scale"] = r["scale"]; st["interp"] = r["interp"]
            elif e == "teleport":  # v1
                st = cur.get(i)
                if st is None:
                    continue
                st["pos"] = [r["x"], r["y"] - (0 if st["anch"] else feet_y), r["z"]]
                st["yaw"] = r["yaw"]; st["pitch"] = r["pitch"]; st["tp"] = r["tp"]
            elif e == "style":
                st = cur.get(i)
                if st is not None:
                    st["opacity"] = r.get("opacity", st["opacity"])
            elif e == "remove":
                if i in cur:
                    cur[i]["alive"] = False
        snap = {}
        for i, st in cur.items():
            if st["alive"]:
                s = dict(st)
                p = s["pos"] or [0, 0, 0]
                if engine == "v1":
                    c = caster.get(t, (0, 0, 0))
                    if s["anch"]:
                        # an anchored v1 display: pos is the entity offset from the caster; translation is local
                        p = [p[0], p[1], p[2]]
                    else:
                        pass
                else:
                    if s.get("anch_feet") is not None:
                        pass
                s["origin"] = [p[0] + s["tr"][0], p[1] + s["tr"][1], p[2] + s["tr"][2]]
                s["left"] = quat_sign(list(s["left"]))
                snap[i] = s
        out[t] = snap
    return out, particles


FIELDS = ["alive", "kind", "item", "model", "text", "bg", "origin", "yaw", "pitch", "left", "scale", "bb", "interp", "tp", "br", "rigshade", "rgb", "seeThrough", "shadow"]


def klass(st):
    """Comparable class of a display: skin bones, the held item, block, a text glyph (by font), any other item."""
    k = st["kind"]
    if k == "item" and st.get("item") == "main-hand":
        return "main_hand"
    if k == "text":
        t = re.sub(r"^<#[0-9a-fA-F]{6}>", "", st.get("text") or "")
        return "text:" + (t if t.startswith("<font") or len(t) <= 2 else "styled")
    if k == "item":
        return "item:" + str(st.get("model") or st.get("item"))
    return k


def sig_groups(states, ticks):
    """class -> list of (spawn_tick, ordinal), in spawn order."""
    seen = {}
    for t in range(ticks + 1):
        for i, st in states[t].items():
            if i not in seen:
                seen[i] = (klass(st), st["spawn_tick"])
    g = collections.defaultdict(list)
    for i, (k, t) in seen.items():
        g[k].append((t, i))
    for k in g:
        g[k].sort()
    return g, seen


FIELD_CHECKS = ["origin", "left", "scale", "bb", "interp", "tp", "seeThrough", "shadow", "rgb", "bg"]


def field_diff(a, b):
    out = []
    for f in FIELD_CHECKS:
        x, y = a.get(f), b.get(f)
        if f == "left":
            x = quat_sign(list(x)); y = quat_sign(list(y))
        if f == "bb":
            x = (x or "").lower(); y = (y or "").lower()
        if not close(x, y):
            out.append(f)
    return out


def analyse(v1_rows, head_rows, ticks):
    s1, p1 = displays_over_time(v1_rows, "v1", ticks)
    s2, p2 = displays_over_time(head_rows, "head", ticks)
    g1, seen1 = sig_groups(s1, ticks)
    g2, seen2 = sig_groups(s2, ticks)
    res = {"kinds": {}, "fields": collections.Counter(), "particles": {}}
    for k in sorted(set(g1) | set(g2)):
        a, b = g1.get(k, []), g2.get(k, [])
        row = {"v1": len(a), "head": len(b), "v1_first": a[0][0] if a else None, "head_first": b[0][0] if b else None}
        first = None
        for (ta, ia), (tb, ib) in zip(a, b):
            for t in range(ticks + 1):
                x, y = s1[t].get(ia), s2[t].get(ib)
                if (x is None) != (y is None):
                    if first is None or t < first[0]:
                        first = (t, "alive")
                    break
                if x is None:
                    continue
                d = field_diff(x, y)
                if d:
                    if first is None or t < first[0]:
                        first = (t, d[0])
                    res["fields"][d[0]] += 1
                    break
        row["first_diff"] = first
        res["kinds"][k] = row
    keys1 = collections.Counter(p[0] for ps in p1.values() for p in ps)
    keys2 = collections.Counter(p[0] for ps in p2.values() for p in ps)
    firstp = None
    for t in range(ticks + 1):
        if sorted(p1.get(t, [])) != sorted(p2.get(t, [])):
            firstp = t
            break
    res["particles"] = {"v1": sum(keys1.values()), "head": sum(keys2.values()), "first_diff_tick": firstp,
                        "missing_keys": sorted(set(keys1) - set(keys2)), "extra_keys": sorted(set(keys2) - set(keys1))}
    return res


def main():
    v1_dir, head_dir = sys.argv[1], sys.argv[2]
    out = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else None
    ticks = 120
    ids = sorted(f[:-6] for f in os.listdir(head_dir) if f.endswith(".jsonl"))
    per = {}
    for i in ids:
        a = load(os.path.join(v1_dir, i + ".jsonl")); b = load(os.path.join(head_dir, i + ".jsonl"))
        per[i] = analyse(a, b, ticks)
    causes = collections.defaultdict(list)
    for i, r in per.items():
        for k, row in r["kinds"].items():
            base = k.split(":")[0]
            if row["v1"] > 0 and row["head"] == 0:
                causes[f"{base}: displays in v1, none in HEAD"].append(f"{i} ({row['v1']}x)")
            elif row["v1"] == 0 and row["head"] > 0:
                causes[f"{base}: displays in HEAD, none in v1"].append(f"{i} ({row['head']}x)")
            elif row["v1"] != row["head"]:
                causes[f"{base}: count differs"].append(f"{i} ({row['v1']}->{row['head']})")
            elif row["first_diff"]:
                causes[f"{base}: same count, first field {row['first_diff'][1]}"].append(f"{i}@{row['first_diff'][0]}")
        pr = r["particles"]
        if pr["v1"] != pr["head"]:
            causes["particles: call count differs"].append(f"{i} ({pr['v1']}->{pr['head']})")
        elif pr["first_diff_tick"] is not None:
            causes["particles: same count, args differ"].append(f"{i}@{pr['first_diff_tick']}")
    lines = ["# Vanilla output diff, v1 (d41b82f1e) vs HEAD", "",
             f"{len(ids)} skills, first {ticks} ticks, caster at the origin (yaw 0, pitch 0), dummy 5 blocks ahead.", "",
             "## By root cause", ""]
    for c, items in sorted(causes.items(), key=lambda x: -len(x[1])):
        lines.append(f"- **{c}**: {len(items)} skills: " + ", ".join(items[:12]) + (" ..." if len(items) > 12 else ""))
    head = len(lines)
    lines += ["", "## Per skill", "", "skill | class | v1 | HEAD | first spawn v1/HEAD | first divergent (tick, field) ", "---|---|---|---|---|---"]
    for i, r in per.items():
        for k, row in r["kinds"].items():
            lines.append(f"{i} | {k} | {row['v1']} | {row['head']} | {row['v1_first']}/{row['head_first']} | {row['first_diff'] or '-'}")
        pr = r["particles"]
        lines.append(f"{i} | particles | {pr['v1']} | {pr['head']} | - | tick {pr['first_diff_tick']} missing={pr['missing_keys'][:4]} extra={pr['extra_keys'][:4]}")
    text = "\n".join(lines) + "\n"
    if out:
        open(out, "w").write(text)
    print("\n".join(lines[:head]))
    return per


if __name__ == "__main__":
    main()
