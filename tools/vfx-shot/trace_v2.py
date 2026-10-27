"""Validate (and synthesize a sample of) an `astera-sim-trace/2` file; the format is specified in SIM-TRACE-V2.md.

usage:
  python3 -I trace_v2.py <trace.json>            exit 0 = valid, 1 = problems listed on stderr
  python3 -I trace_v2.py --sample <out.json>     synthetic coin-2 trace (spinning shears, release, stick, reel) for renderer work
"""
import json
import math
import sys

KINDS = {"mesh", "plate", "line", "trail"}
FRAME_KINDS = {"world", "caster", "bone", "item", "part", "body", "enemy", "contact", "point"}
FACES = {"viewer_center", "viewer_vertical", "viewer_axis", "plane"}
OBJ_FIELDS = {"mesh": {"pose", "interp"}, "plate": {"pose", "interp"}, "line": {"from", "to"}, "trail": {"root", "tip"}}


def finite(v, n):
    return isinstance(v, list) and len(v) == n and all(isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x) for x in v)


def validate(t):
    errs = []

    def err(m):
        if len(errs) < 40:
            errs.append(m)

    if t.get("format") != "astera-sim-trace/2":
        err("format must be 'astera-sim-trace/2'")
    for k in ("skill", "item"):
        if not isinstance(t.get(k), str) or not t[k]:
            err(f"{k}: non-empty string required")
    if t.get("tickRate") != 20:
        err("tickRate must be 20")
    first, last, cast = t.get("firstTick"), t.get("lastTick"), t.get("castTick")
    if not all(isinstance(x, int) for x in (first, last, cast)) or not (first <= cast <= last):
        err("firstTick <= castTick <= lastTick (integers) required")
        return errs
    frames = {}
    for f in t.get("frames", []):
        if f.get("kind") not in FRAME_KINDS:
            err(f"frame {f.get('id')}: kind {f.get('kind')!r}")
        if f.get("id") in frames:
            err(f"duplicate frame {f.get('id')}")
        frames[f.get("id")] = f
    for need in ("world", "caster"):
        if need not in frames:
            err(f"frame table lacks '{need}'")
    for f in frames.values():
        p = f.get("parent")
        if p is not None and p not in frames:
            err(f"frame {f['id']}: parent {p!r} not declared")
    objs = {}
    for o in t.get("objects", []):
        h = o.get("handle")
        if not isinstance(h, int) or h in objs:
            err(f"object handle {h!r} missing or duplicate")
            continue
        objs[h] = o
        if o.get("kind") not in KINDS:
            err(f"object {h}: kind {o.get('kind')!r}")
        if not isinstance(o.get("op"), str):
            err(f"object {h}: op id (string) required")
        if not isinstance(o.get("spawnTick"), int):
            err(f"object {h}: spawnTick int required")
        rt = o.get("removeTick")
        if rt is not None and (not isinstance(rt, int) or rt <= o.get("spawnTick", 0)):
            err(f"object {h}: removeTick must be null or > spawnTick")
        if o.get("kind") == "mesh" and not isinstance(o.get("item"), str):
            err(f"object {h}: mesh needs item")
        if o.get("kind") in ("plate", "line") and (o.get("face") or {}).get("type") not in FACES:
            err(f"object {h}: face.type one of {sorted(FACES)}")
    ticks = t.get("ticks", [])
    if [x.get("tick") for x in ticks] != list(range(first, last + 1)):
        err("ticks[] must have exactly one record per tick firstTick..lastTick, in order")
        return errs
    spawned = set()
    for rec in ticks:
        n = rec["tick"]
        poses = rec.get("poses", {})
        for fid, p in poses.items():
            if fid not in frames:
                err(f"tick {n}: pose for undeclared frame {fid!r}")
            if not finite(p, 7):
                err(f"tick {n}: pose {fid} must be 7 finite numbers")
            elif abs(math.sqrt(sum(x * x for x in p[3:])) - 1) > 1e-3:
                err(f"tick {n}: pose {fid} quaternion is not unit")
        if "caster" not in poses:
            err(f"tick {n}: no caster pose")
        for key, val in rec.get("objects", {}).items():
            try:
                h = int(key)
            except ValueError:
                err(f"tick {n}: object key {key!r} is not a handle")
                continue
            o = objs.get(h)
            if o is None:
                err(f"tick {n}: update for unknown handle {h}")
                continue
            if not (o["spawnTick"] <= n and (o.get("removeTick") is None or n < o["removeTick"])):
                err(f"tick {n}: handle {h} updated outside its life [{o['spawnTick']}, {o.get('removeTick')})")
            bad = set(val) - OBJ_FIELDS.get(o["kind"], set())
            if bad:
                err(f"tick {n}: handle {h} ({o['kind']}) has unknown fields {sorted(bad)}")
            for f in ("pose",):
                if f in val and not finite(val[f], 7):
                    err(f"tick {n}: handle {h} pose must be 7 numbers")
            for f in ("from", "to", "root", "tip"):
                if f in val and not finite(val[f], 3):
                    err(f"tick {n}: handle {h} {f} must be 3 numbers")
            spawned.add(h)
    for h, o in objs.items():
        if h not in spawned:
            err(f"object {h} ({o.get('op')}) never has a state in ticks[].objects")
        else:
            first_state = next((r["tick"] for r in ticks if str(h) in r.get("objects", {})), None)
            if first_state != o["spawnTick"]:
                err(f"object {h}: first state at tick {first_state}, spawnTick {o['spawnTick']}")
    return errs


# ---------------------------------------------------------------------------------------------- sample


def qz(a):
    return [0.0, 0.0, math.sin(a / 2), math.cos(a / 2)]


def qy(a):
    return [0.0, math.sin(a / 2), 0.0, math.cos(a / 2)]


def pose(pos, q):
    return [round(v, 6) for v in list(pos) + list(q)]


def sample():
    first, cast, last = 0, 5, 70
    release, stick_at, reel, arrive = cast + 22, cast + 28, cast + 40, cast + 50
    enemy = (6.0, 0.0, 0.0)
    caster_q = qy(math.pi / 2)  # yaw -90: faces +X; rotation about +Y by +90 deg maps +Z to +X
    hand = (0.35, 1.2, -0.3)    # right hand of a caster facing +X: right = +Z... world offset from feet
    hand = (0.4, 1.15, 0.3)
    frames = [
        {"id": "world", "kind": "world", "parent": None},
        {"id": "caster", "kind": "caster", "parent": "world"},
        {"id": "enemy", "kind": "enemy", "parent": "world"},
        {"id": "shears", "kind": "item", "parent": "caster.right_hand", "item": "couture-rapier"},
        {"id": "caster.right_hand", "kind": "point", "parent": "caster"},
        {"id": "body", "kind": "part", "parent": "shears", "item": "couture-rapier", "part": "body"},
        {"id": "blade_a", "kind": "part", "parent": "shears", "item": "couture-rapier", "part": "blade_a"},
        {"id": "blade_b", "kind": "part", "parent": "shears", "item": "couture-rapier", "part": "blade_b"},
    ]
    objects = [
        {"handle": 1, "op": "shears_body", "kind": "mesh", "item": "couture-rapier", "part": "body", "scale": 1.0, "frame": "body", "spawnTick": cast, "removeTick": None},
        {"handle": 2, "op": "shears_a", "kind": "mesh", "item": "couture-rapier", "part": "blade_a", "scale": 1.0, "frame": "blade_a", "spawnTick": cast, "removeTick": None},
        {"handle": 3, "op": "shears_b", "kind": "mesh", "item": "couture-rapier", "part": "blade_b", "scale": 1.0, "frame": "blade_b", "spawnTick": cast, "removeTick": None},
        {"handle": 4, "op": "thread_a", "kind": "line", "thickness": 0.03, "color": "#ff5fa2", "face": {"type": "viewer_axis", "axis": [1, 0, 0]}, "spawnTick": release, "removeTick": arrive + 1},
        {"handle": 5, "op": "thread_b", "kind": "line", "thickness": 0.03, "color": "#ff5fa2", "face": {"type": "viewer_axis", "axis": [1, 0, 0]}, "spawnTick": release, "removeTick": arrive + 1},
        {"handle": 6, "op": "trail_a", "kind": "trail", "ticks": 6, "widthFrac": 1.0, "color": "#ffd0e8", "alpha": 0.8, "spawnTick": cast, "removeTick": stick_at},
    ]
    ticks = []
    spin = 0.0
    off_a, off_b = (0.0, 0.18, 0.0), (0.0, -0.18, 0.0)  # blade offsets in the shears frame (spin plane = XY)
    stuck_a = stuck_b = None
    for n in range(first, last + 1):
        poses = {"world": pose((0, 0, 0), [0, 0, 0, 1]), "caster": pose((0, 0, 0), caster_q), "enemy": pose(enemy, qy(math.pi / 2 * 3)),
                 "caster.right_hand": pose(hand, caster_q)}
        objs, events, contacts, damage, particles, sounds = {}, [], [], [], [], []
        if n < cast:
            ticks.append({"tick": n, "poses": poses, "objects": {}, "events": [], "contacts": [], "particles": [], "sounds": [], "damage": [], "casterBody": None})
            continue
        t = n - cast
        if n < release:
            # spin overhead in the vertical plane containing the throw direction (+X): axis = Z. Never world.up.
            spin += 0.55
            c = (0.3 + 0.1 * math.cos(spin), 2.35, 0.3)
            q = qz(spin)
            poses["shears"] = pose(c, q)
            rot = lambda v: (v[0] * math.cos(spin) - v[1] * math.sin(spin), v[0] * math.sin(spin) + v[1] * math.cos(spin), v[2])
            ra, rb = rot((0.55, 0, 0)), rot((-0.55, 0, 0))
            poses["body"] = pose(c, q)
            poses["blade_a"] = pose((c[0] + ra[0], c[1] + ra[1], c[2]), q)
            poses["blade_b"] = pose((c[0] + rb[0], c[1] + rb[1], c[2]), q)
        else:
            k = n - release
            if stuck_a is None:
                spin += 0.55
                ax = 0.3 + 0.9 * k
                for fid, dz in (("body", 0.0), ("blade_a", 0.25), ("blade_b", -0.25)):
                    poses[fid] = pose((ax, max(1.2, 2.35 - 0.12 * k), dz * (1 + k * 0.05)), qz(spin))
                if ax >= enemy[0] - 0.4:
                    stuck_a = stuck_b = n
                    events.append({"name": "hit", "source": "blade_a", "victim": "enemy"})
                    events.append({"name": "stick", "source": "blade_a"})
                    contacts.append({"piece": "blade_a", "victim": "enemy", "point": [enemy[0] - 0.3, 1.2, 0.1], "normal": [-1, 0, 0]})
                    damage.append({"victim": "enemy", "kind": "slash", "amount": 18})
                    particles.append({"particle": "minecraft:crit", "at": [enemy[0] - 0.3, 1.2, 0.1], "count": 8, "dir": [-1, 0, 0], "spreadDeg": 25, "speed": 0.2, "color": "#ff5fa2"})
                    sounds.append({"sound": "astera:skill.stab", "at": [enemy[0] - 0.3, 1.2, 0.1], "volume": 1.0, "pitch": 1.0})
                    last_fly = {fid: poses[fid] for fid in ("body", "blade_a", "blade_b")}
                    stuck_pose = last_fly
            elif n < reel:
                for fid in ("body", "blade_a", "blade_b"):
                    poses[fid] = stuck_pose[fid]
            else:
                u = min(1.0, (n - reel) / float(arrive - reel))
                for fid in ("body", "blade_a", "blade_b"):
                    s = stuck_pose[fid]
                    poses[fid] = pose([s[i] + (hand[i] - s[i]) * u for i in range(3)], s[3:])
                if n == reel:
                    events.append({"name": "reel", "source": "shears"})
                if n == arrive:
                    events.append({"name": "arrive", "source": "shears"})
            if n == release:
                events.append({"name": "release", "source": "shears"})
                sounds.append({"sound": "astera:skill.whoosh", "at": list(poses["body"][:3]), "volume": 1.0, "pitch": 1.2})
        if "shears" not in poses:
            poses["shears"] = poses["body"]
        for h, fid in ((1, "body"), (2, "blade_a"), (3, "blade_b")):
            if n == cast or poses[fid] != ticks[-1]["poses"].get(fid):
                objs[str(h)] = {"pose": poses[fid], "interp": 1}
        if release <= n <= arrive:
            objs["4"] = {"from": list(hand), "to": poses["blade_a"][:3]}
            objs["5"] = {"from": list(hand), "to": poses["blade_b"][:3]}
        if n < stick_at:
            a = poses["blade_a"]
            objs["6"] = {"root": a[:3], "tip": [a[0], a[1] + 0.1, a[2]]}
        ticks.append({"tick": n, "poses": poses, "objects": objs, "events": events, "contacts": contacts, "particles": particles, "sounds": sounds, "damage": damage, "casterBody": None})
    return {
        "format": "astera-sim-trace/2", "skill": "couture-rapier-s2", "item": "couture-rapier", "tickRate": 20,
        "castTick": cast, "firstTick": first, "lastTick": last,
        "caster": {"id": "caster", "height": 1.8}, "victims": [{"id": "enemy", "height": 1.8, "slim": False}],
        "frames": frames, "objects": objects, "ticks": ticks,
    }


def main(argv):
    if len(argv) == 3 and argv[1] == "--sample":
        t = sample()
        json.dump(t, open(argv[2], "w"), separators=(",", ":"))
        errs = validate(t)
        for e in errs:
            print("sample invalid:", e, file=sys.stderr)
        print(f"wrote {argv[2]} ({len(t['ticks'])} ticks)")
        return 1 if errs else 0
    if len(argv) == 2:
        errs = validate(json.load(open(argv[1])))
        for e in errs:
            print(e, file=sys.stderr)
        print("ok" if not errs else f"{len(errs)} problem(s)")
        return 1 if errs else 0
    print(__doc__, file=sys.stderr)
    return 2


sys.exit(main(sys.argv))
