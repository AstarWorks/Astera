#!/usr/bin/env python3
"""Compare two gameplay dumps (SKL-17): what every skill DOES, v1 against HEAD.

Usage: gameplay_diff.py <v1-dir> <head-dir> [--out report.md]

Each dir holds one `<skill>.jsonl` per skill, written by the v1 reference recorder (`v1-dumper/V1GameplayDumpTest.kt.txt`) or by
`GameplayDump` of the game-sim. Scene: caster at the origin facing +Z, dummy 5 blocks ahead, wall plane 8 blocks ahead; ticks
are relative to the cast start. One line = one event; the shared vocabulary:

  meta, cost, cooldown, sound, damage, status, kb, tp, block, pos (per tick, caster/dummy, with velocity), vel (on change),
  proj / projpos (v1 only), phase (HEAD only: cast events start/release/hit/block/end), ego (v1), egocost (HEAD)

Differences are grouped by CAUSE (a fixed label such as "sound: missing in HEAD"), with the number of skills and events in each
group, the earliest divergent tick and the worst examples. Nothing here judges which side is right.
"""
import collections, json, os, sys

POS_EPS = 0.05
VOL_EPS = 0.02
PITCH_EPS = 0.02


def load(path):
    rows = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


class Diffs:
    """cause -> list of (skill, first_tick, magnitude, detail)"""

    def __init__(self):
        self.g = collections.defaultdict(list)

    def add(self, cause, skill, tick, mag, detail):
        self.g[cause].append((skill, tick, mag, detail))


def pair_by_key(a, b, key):
    """a, b: lists of events (with t). -> (paired[(ea, eb)], only_a, only_b); pairs equal keys in tick order."""
    da, db = collections.defaultdict(list), collections.defaultdict(list)
    for e in a:
        da[key(e)].append(e)
    for e in b:
        db[key(e)].append(e)
    paired, oa, ob = [], [], []
    for k in set(da) | set(db):
        xs = sorted(da.get(k, []), key=lambda e: e["t"])
        ys = sorted(db.get(k, []), key=lambda e: e["t"])
        n = min(len(xs), len(ys))
        paired += list(zip(xs[:n], ys[:n]))
        oa += xs[n:]
        ob += ys[n:]
    return paired, oa, ob


def pos_of(e):
    return (e.get("x", 0.0), e.get("y", 0.0), e.get("z", 0.0))


def dist(p, q):
    return sum((u - v) ** 2 for u, v in zip(p, q)) ** 0.5


def compare_events(d, skill, label, a, b, key, attrs, shift_ok=0):
    """Generic: missing / extra / tick shifted / attribute differs."""
    paired, oa, ob = pair_by_key(a, b, key)
    if oa:
        d.add(f"{label}: v1 has it, HEAD does not", skill, min(e["t"] for e in oa), len(oa),
              f"{len(oa)}x {sorted(set(str(key(e)) for e in oa))[:3]} first t{min(e['t'] for e in oa)}")
    if ob:
        d.add(f"{label}: HEAD has it, v1 does not", skill, min(e["t"] for e in ob), len(ob),
              f"{len(ob)}x {sorted(set(str(key(e)) for e in ob))[:3]} first t{min(e['t'] for e in ob)}")
    shifted = [(x, y) for x, y in paired if abs(x["t"] - y["t"]) > shift_ok]
    if shifted:
        t0 = min(min(x["t"], y["t"]) for x, y in shifted)
        worst = max(shifted, key=lambda p: abs(p[0]["t"] - p[1]["t"]))
        sign = "HEAD earlier" if sum(y["t"] - x["t"] for x, y in shifted) < 0 else "HEAD later"
        d.add(f"{label}: tick differs ({sign})", skill, t0, len(shifted),
              f"{len(shifted)}x, worst {key(worst[0])} v1 t{worst[0]['t']} vs HEAD t{worst[1]['t']}")
    for name, fn in attrs.items():
        bad = [(x, y) for x, y in paired if not fn(x, y)]
        if bad:
            x, y = bad[0]
            d.add(f"{label}: {name} differs", skill, min(min(p[0]["t"], p[1]["t"]) for p in bad), len(bad),
                  f"{len(bad)}x, e.g. {key(x)} v1 t{x['t']} {_brief(x)} vs HEAD t{y['t']} {_brief(y)}")


def _brief(e):
    return {k: v for k, v in e.items() if k not in ("t", "e", "src", "hp", "detail")}


def series(rows, who):
    out = {}
    for r in rows:
        if r.get("e") == "pos" and r["who"] == who:
            out[r["t"]] = pos_of(r)
    return out


def vel_series(rows, who):
    out = {}
    for r in rows:
        if r.get("e") == "pos" and r["who"] == who:
            out[r["t"]] = (r.get("vx", 0.0), r.get("vy", 0.0), r.get("vz", 0.0))
    return out


def activity_end(rows):
    """Last tick with any action (v1 has no cast-end event): sounds, damage, status, kb, tp, block, projectile, death."""
    # status expiry / removal is a timer, not a cast action; world effects (explosions) are
    ts = [r["t"] for r in rows if r.get("e") in ("sound", "damage", "status", "kb", "tp", "block", "proj", "projpos", "death", "world") and "t" in r
          and not (r.get("e") == "status" and r.get("op") in ("expired", "removed"))]
    return max(ts) if ts else -1


def compare_skill(d, skill, v1, hd):
    if any(r.get("e") == "ev" and r.get("type") == "SkillCastRejected" for r in v1):
        d.add("v1 reference cast was rejected (not comparable)", skill, 0, 1, "v1 refused the cast in the scripted scene (full ammo for a reload, or empty gauge)")
        return
    by = lambda rows, e: [r for r in rows if r.get("e") == e]
    if any(r.get("e") == "error" for r in hd):
        d.add("HEAD cast raised an error", skill, 0, 1, next(r["msg"] for r in hd if r.get("e") == "error"))
    if any(r.get("e") == "error" for r in v1):
        d.add("v1 cast raised an error", skill, 0, 1, next(r["msg"] for r in v1 if r.get("e") == "error"))

    # sounds
    compare_events(d, skill, "sound", by(v1, "sound"), by(hd, "sound"), lambda e: e["key"],
                   {"position": lambda x, y: dist(pos_of(x), pos_of(y)) <= 0.25 or abs(x["t"] - y["t"]) > 0,
                    "volume": lambda x, y: abs(x["vol"] - y["vol"]) <= VOL_EPS,
                    "pitch": lambda x, y: abs(x["pitch"] - y["pitch"]) <= PITCH_EPS})
    # damage
    compare_events(d, skill, "damage", by(v1, "damage"), by(hd, "damage"), lambda e: (e["v"], round(e["amt"], 1)),
                   {})
    dv1, dhd = by(v1, "damage"), by(hd, "damage")
    if dv1 and dhd:
        a, b = round(sum(e["amt"] for e in dv1), 1), round(sum(e["amt"] for e in dhd), 1)
        if a != b:
            d.add("damage: total amount differs", skill, min(e["t"] for e in dv1 + dhd), 1, f"v1 {a} in {len(dv1)} hits vs HEAD {b} in {len(dhd)} hits")
    # HEAD's invulnerability grant / release is v1's `immunity` status applied / removed
    for r in hd:
        if r.get("e") == "invuln":
            r.update(e="status", id="immunity", op="applied" if r["on"] else "removed")
    # status (expired == removed)
    norm = lambda rows: [dict(r, op="removed" if r["op"] in ("expired", "removed") else r["op"]) for r in rows]
    compare_events(d, skill, "status", norm(by(v1, "status")), norm(by(hd, "status")), lambda e: (e["who"], e["id"], e["op"]), {})
    # knockback / impulse
    # two pushes on one tick (the plain-hit push and the skill's own impulse) are not ordered the same by v1's and HEAD's pipelines
    kb_order = lambda rows: sorted(rows, key=lambda r: (r["t"], r["who"], round(r.get("strength", 0), 1)))
    compare_events(d, skill, "knockback", kb_order(by(v1, "kb")), kb_order(by(hd, "kb")), lambda e: e["who"],
                   {"strength": lambda x, y: abs(x.get("strength", 0) - y.get("strength", 0)) <= 0.05})
    # teleports (dash / blink / pull)
    compare_events(d, skill, "teleport", by(v1, "tp"), by(hd, "tp"), lambda e: e["who"],
                   {"destination": lambda x, y: dist(pos_of(x), pos_of(y)) <= 0.25})
    # blocks
    compare_events(d, skill, "block", [e for e in by(v1, "block") if e["k"] in ("break", "damage", "place")],
                   [e for e in by(hd, "block") if e["k"] in ("break", "damage", "place")], lambda e: e["k"], {})

    # cost / cooldown
    c1, c2 = by(v1, "cost"), by(hd, "cost")
    if c1 and c2 and (c1[0].get("ammo"), c1[0].get("energy")) != (c2[0].get("ammo"), c2[0].get("energy")):
        d.add("cost: resource amount differs", skill, 0, 1,
              f"v1 ammo {c1[0].get('ammo')} energy {c1[0].get('energy')} vs HEAD ammo {c2[0].get('ammo')} energy {c2[0].get('energy')}")
    k1, k2 = by(v1, "cooldown"), by(hd, "cooldown")
    t1 = k1[0]["ticks"] if k1 else 0
    t2 = k2[0]["ticks"] if k2 else 0
    if t1 != t2:
        d.add("cooldown: duration differs" if t1 and t2 else ("cooldown: v1 has it, HEAD does not" if t1 else "cooldown: HEAD has it, v1 does not"),
              skill, 0, 1, f"v1 {t1} vs HEAD {t2}")
    r1 = (k1[0].get("reload") or 0) if k1 else 0
    r2 = (k2[0].get("reload") or 0) if k2 else 0
    if r1 != r2:
        d.add("cooldown: reload ticks differ", skill, 0, 1, f"v1 {r1} vs HEAD {r2}")
    spent = any(r.get("e") == "ego" and r["t"] == 0 and r["value"] == 0 for r in v1)
    e2 = [r for r in hd if r.get("e") == "egocost"]
    if spent != bool(e2):
        d.add("ego: gauge spend in one side only", skill, 0, 1, f"v1 drains the gauge at t0: {spent}; HEAD declares egocost {e2[0]['value'] if e2 else 0}")

    # projectiles (v1 only has them; HEAD has frames, seen through hit/block phases)
    spawns = [r for r in by(v1, "proj") if r["k"] == "spawn"]
    ends = [r for r in by(v1, "proj") if r["k"] != "spawn"]
    # `sel*` hits are target selectors (cones, spheres), not projectile frames; only flying volumes compare with v1 projectiles
    hphase = [r for r in by(hd, "phase") if r["name"] in ("hit", "block") and not str(r.get("source", "")).startswith("sel")]
    if spawns:
        v1_hit = min((r["t"] for r in ends if r["k"].startswith("hit")), default=None)
        hd_hit = min((r["t"] for r in hphase), default=None)
        if v1_hit is not None and hd_hit is None:
            d.add("projectile: v1 projectile hits, HEAD frame never hits", skill, v1_hit, len(spawns), f"v1 {len(spawns)} spawns, first hit t{v1_hit}")
        elif v1_hit is None and hd_hit is not None:
            d.add("projectile: HEAD frame hits, v1 projectile never hits", skill, hd_hit, len(spawns), f"HEAD first hit t{hd_hit}")
        elif v1_hit is not None and v1_hit != hd_hit:
            d.add("projectile: first impact tick differs " + ("(HEAD earlier)" if hd_hit < v1_hit else "(HEAD later)"),
                  skill, min(v1_hit, hd_hit), abs(v1_hit - hd_hit), f"v1 t{v1_hit} vs HEAD t{hd_hit}; v1 {len(spawns)} projectile(s)")
    kinds = collections.Counter(r["k"] for r in ends)
    if "hit_block" in kinds and not any(r["name"] == "block" for r in hphase) and not any(r["t"] for r in by(hd, "block")):
        d.add("projectile: v1 hits the wall, HEAD does not record a block hit", skill, min(r["t"] for r in ends if r["k"] == "hit_block"), kinds["hit_block"], f"v1 hit_block x{kinds['hit_block']}")

    # movement of caster and dummy
    for who in ("caster", "dummy"):
        s1, s2 = series(v1, who), series(hd, who)
        ts = sorted(set(s1) & set(s2))
        first = next((t for t in ts if dist(s1[t], s2[t]) > POS_EPS), None)
        if first is not None:
            end = ts[-1]
            m1 = dist(s1[ts[0]], s1[end]) > POS_EPS
            m2 = dist(s2[ts[0]], s2[end]) > POS_EPS
            if m1 and not m2:
                cause = f"movement ({who}): v1 moves, HEAD stays"
            elif m2 and not m1:
                cause = f"movement ({who}): HEAD moves, v1 stays"
            else:
                cause = f"movement ({who}): path differs"
            d.add(cause, skill, first, round(dist(s1[end], s2[end]), 2),
                  f"first t{first} (v1 {tuple(round(c, 2) for c in s1[first])} vs HEAD {tuple(round(c, 2) for c in s2[first])}); end gap {dist(s1[end], s2[end]):.2f} blocks")
        w1, w2 = vel_series(v1, who), vel_series(hd, who)
        nz1 = [t for t in sorted(w1) if dist(w1[t], (0, 0, 0)) > 1e-3]
        nz2 = [t for t in sorted(w2) if dist(w2[t], (0, 0, 0)) > 1e-3]
        if bool(nz1) != bool(nz2):
            d.add(f"velocity ({who}): " + ("v1 sets a velocity, HEAD does not" if nz1 else "HEAD sets a velocity, v1 does not"),
                  skill, (nz1 or nz2)[0], len(nz1 or nz2), f"first t{(nz1 or nz2)[0]} v={w1.get(nz1[0]) if nz1 else w2.get(nz2[0])}")
        elif nz1 and nz2 and (nz1[0] != nz2[0] or dist(w1[nz1[0]], w2[nz2[0]]) > 0.05):
            d.add(f"velocity ({who}): value or tick differs", skill, min(nz1[0], nz2[0]), 1,
                  f"v1 t{nz1[0]} {tuple(round(c, 2) for c in w1[nz1[0]])} vs HEAD t{nz2[0]} {tuple(round(c, 2) for c in w2[nz2[0]])}")

    # cast end
    # chain casts raise one `end` per child; the parent's is the last one
    ends_hd = [r["t"] for r in by(hd, "phase") if r["name"] == "end"][-1:]
    a1, a2 = activity_end(v1), activity_end(hd)
    end_hd = ends_hd[0] if ends_hd else None
    if end_hd is None:
        d.add("cast end: not yet ended at the 120-tick horizon (not a defect)", skill, 120, 1, f"v1 last action t{a1}")
    elif a1 >= 0 and abs(a1 - end_hd) > 2:
        # HEAD's `end` waits for every timeline item, look.yaml loops and removals included; v1 has no such tail. When HEAD's own last
        # gameplay action (sound, damage, status, kb, tp, ...) coincides with v1's, the extra time is a visual tail, listed apart.
        if end_hd > a1 and abs(a2 - a1) <= 2:
            d.add("cast end: HEAD stays open only for a visual tail (same last gameplay action as v1)", skill, a1, end_hd - a1,
                  f"last gameplay action v1 t{a1} / HEAD t{a2}; HEAD end t{end_hd}")
        else:
            d.add("cast end: HEAD ends " + ("earlier" if end_hd < a1 else "later") + " than v1's last action", skill, min(a1, end_hd), abs(a1 - end_hd),
                  f"v1 last action t{a1} vs HEAD last action t{a2}, HEAD end t{end_hd}")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    out = None
    if "--out" in sys.argv:
        out = sys.argv[sys.argv.index("--out") + 1]
        args = [a for a in args if a != out]
    v1d, hdd = args[0], args[1]
    skills = sorted(f[:-6] for f in os.listdir(hdd) if f.endswith(".jsonl") and os.path.exists(os.path.join(v1d, f)))
    only_v1 = sorted(f[:-6] for f in os.listdir(v1d) if f.endswith(".jsonl") and not os.path.exists(os.path.join(hdd, f)))
    only_hd = sorted(f[:-6] for f in os.listdir(hdd) if f.endswith(".jsonl") and not os.path.exists(os.path.join(v1d, f)))
    d = Diffs()
    clean = []
    for s in skills:
        before = sum(len(v) for v in d.g.values())
        compare_skill(d, s, load(os.path.join(v1d, s + ".jsonl")), load(os.path.join(hdd, s + ".jsonl")))
        if sum(len(v) for v in d.g.values()) == before:
            clean.append(s)

    lines = ["# Gameplay diff: v1 (d41b82f1e) vs HEAD", "",
             f"Skills compared: {len(skills)} (only in v1: {len(only_v1)}, only in HEAD: {len(only_hd)}). Skills with no gameplay divergence: {len(clean)}.",
             "Scene: caster at the origin, yaw 0, pitch 0, dummy 5 blocks ahead, wall plane 8 blocks ahead, 120 ticks, ticks relative to the cast start.",
             "Nothing here says which side is right; each group is one cause with the skills it hits.",
             "Harness: both sides run damage and status through the real combat use cases (v1 via its mechanic dispatcher, HEAD via PaperSkillGameplayGateway over GameSim), "
             "so the 0.4 plain-hit push, ego gain and status timers/expiry are recorded the same way (`kb` via=combat, `ego`, `status`). "
             "A skill's own push (Knockback mechanic, explosion) is `kb` via=impulse on both sides. "
             "v1 has real projectile entities (spawn, per-tick position, impact); HEAD models them as frames, compared here through the first hit / block event.", ""]
    fam = collections.OrderedDict()
    for cause in d.g:
        fam.setdefault(cause.split(":")[0].split(" (")[0], []).append(cause)
    lines += ["## Summary by area", "", "| area | groups | skills hit | events |", "|---|---|---|---|"]
    for f, causes in sorted(fam.items(), key=lambda kv: -len(set(x[0] for c in kv[1] for x in d.g[c]))):
        sk = set(x[0] for c in causes for x in d.g[c])
        lines.append(f"| {f} | {len(causes)} | {len(sk)} | {sum(x[2] if isinstance(x[2], int) else 1 for c in causes for x in d.g[c])} |")
    lines.append("")
    lines += ["## Groups (largest first)", ""]
    for cause, items in sorted(d.g.items(), key=lambda kv: -len(set(x[0] for x in kv[1]))):
        sk = sorted(set(x[0] for x in items))
        first = min(items, key=lambda x: x[1])
        worst = sorted(items, key=lambda x: -(x[2] if isinstance(x[2], (int, float)) else 0))[:5]
        lines.append(f"### {cause}")
        lines.append(f"- skills: {len(sk)}; earliest divergent tick: t{first[1]} ({first[0]})")
        lines.append("- worst: " + "; ".join(f"`{w[0]}` {w[3]}" for w in worst))
        lines.append("- all: " + ", ".join(sk))
        lines.append("")
    if only_v1 or only_hd:
        lines += ["## Not in both", "", f"- only in v1: {', '.join(only_v1) or '-'}", f"- only in HEAD: {', '.join(only_hd) or '-'}", ""]
    lines += ["## Skills with no gameplay divergence", "", ", ".join(clean) or "-", ""]
    text = "\n".join(lines)
    if out:
        os.makedirs(os.path.dirname(out), exist_ok=True)
        open(out, "w").write(text)
    print(text[:3000])


if __name__ == "__main__":
    main()
