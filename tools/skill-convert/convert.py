#!/usr/bin/env python3
"""Mechanical skill converter v1 -> v2 (SKL-09, ADR-0301).

  python3 tools/skill-convert/convert.py --all                  # every item-bound skill (+ cast_skill children)
  python3 tools/skill-convert/convert.py --ids fireball-cast,club-bash
  python3 tools/skill-convert/convert.py --all --out /tmp/x --report /tmp/x/report.tsv

Reads  content/skills/<id>.yaml (v1), content/vfx/<id>.yaml, content/items/*.yaml (item binding).
Writes content/skills/<id>/skill.yaml  (kind: Skill: id, item, frames, gameplay)
       content/skills/<id>/look.yaml   (kind: Skill, same id: frames + presentation; the composer merges by id)
Never writes content/models, never overwrites a hand-built v2 skill (couture-rapier-s2 and its children): for those it
writes tools/skill-convert/protected-diff.txt, what their v1 data has that the v2 files lack.

The output targets the REAL v2 decoder grammar (kernel/runtime/.../skillengine/load/**): only ops, fields and target
forms those decoders accept are emitted. Where v1 has something the engine lacks, nothing is emitted for it and a
comment is left instead (grep them):
  # CONVERT-TODO <gap> (xN): ...     gameplay / meta construct v1 had and v2 cannot express (hand-fix or engine request)
  # CONVERT-PRESENT <gap> (xN): ...  presentation detail v1 had and v2 cannot express (look pass)
Header lines starting `CONVERT-TODO meta` carry the v1 skill meta (name, cooldown, cost, ...) that has no SkillDef home.
Idempotent: same input, same output bytes. Needs PyYAML for reading only.

Local axes of generated point frames: offset [x, y, z] = [left, up, forward] in the parent's frame (vfx convention).
"""
import argparse
import json
import os
import re
import sys
from collections import OrderedDict

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from yamlout import dump_doc  # noqa: E402
from rotvec import convert_keys, euler_to_vec  # noqa: E402

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
# Skills whose windup holds the caster in place: id -> walk-speed factor (0.0 = rooted). SKL-15 row 13.
WINDUP_LOCK = {"bastion-greatsword-ego": 0.0}
PROTECTED = {"couture-rapier-s2", "couture-rapier-s2-shears", "couture-rapier-s2-blade-a", "couture-rapier-s2-blade-b"}

GAMEPLAY = {"damage", "apply_status", "knockback", "propel", "hit_stop", "cast_skill", "explode", "break_area",
            "place_shape", "heal", "cleanse", "consume_item", "summon_lightning", "leap", "modify_attribute",
            "dash_strike", "counter"}
PRESENT = {"play_sound", "spawn_particle", "schedule_particle_animation", "play_vfx", "camera_shake", "screen_flash",
           "screen_veil", "telegraph", "play_animation", "stop_sound", "hide_held_item"}
AREA = {"cone", "nearby_entities", "forward_ray", "enemies", "allies"}
SHAPES_BREAK = {"sphere", "line", "box"}
SHAPES_BUILD = {"wall", "bridge", "pillar", "platform"}


# v1 trail slabs were blocks; v2 draws a coloured quad, so a block that is the only colour source maps to its look.
BLOCK_RGB = {
    "black_concrete": "#080a0f", "white_concrete": "#cfd5d6", "red_concrete": "#8e2121", "gold_block": "#f6d03d",
    "light_blue_concrete": "#2489c7", "pink_concrete": "#d5658f", "yellow_concrete": "#f0af15", "orange_concrete": "#e06101",
    "iron_block": "#d8d8d8", "gray_concrete": "#36393d", "light_gray_concrete": "#7d7d73", "snow_block": "#f8fefe",
    "blue_concrete": "#2c2e8f", "purple_concrete": "#64209c", "white_stained_glass": "#ffffff", "yellow_stained_glass": "#e5e533",
    "red_stained_glass": "#993333", "orange_stained_glass": "#d87f33", "purple_stained_glass": "#7f3fb2",
}


def argb_split(c):
    """v1 `#AARRGGBB` (or `#RRGGBB`) -> (`#rrggbb`, alpha 0..1)."""
    h = str(c).lstrip("#")
    if len(h) == 8:
        return "#" + h[2:].lower(), round(int(h[:2], 16) / 255.0, 3)
    return "#" + h.lower(), 1.0


def block_rgb(name):
    return BLOCK_RGB[str(name).replace("minecraft:", "")]


def trail_shape(tr):
    """v1 trail bands / edge / dark_rim / step_deg / lag_ticks / double_sided -> the v2 `trail` fields (SKL-15 row 11)."""
    out = {}
    bands = []
    for b in tr.get("bands") or []:
        if "color" in b:
            rgb, a = argb_split(b["color"])
        else:
            rgb, a = block_rgb(b["block"]), 1.0
        band = {"from": float(b["from"]), "to": float(b["to"]), "color": rgb, "alpha": a}
        if b.get("color_to"):
            band["color_to"] = argb_split(b["color_to"])[0]
        bands.append(band)
    if bands:
        out["bands"] = bands
    if tr.get("edge"):
        e = tr["edge"]
        out["edge"] = {"color": block_rgb(e.get("block", "white_concrete")), "width": float(e.get("width", 0.04))}
    if tr.get("dark_rim"):
        r = tr["dark_rim"]
        rgb, a = argb_split(r["color"])
        out["dark_rim"] = {"color": rgb, "alpha": a, "width": float(r.get("width", 0.18))}
    if "step_deg" in tr and float(tr["step_deg"]) != 30.0:
        out["step_deg"] = float(tr["step_deg"])
    if int(tr.get("lag_ticks", 0)):
        out["lag_ticks"] = int(tr["lag_ticks"])
    if tr.get("double_sided"):
        out["double_sided"] = True
    return out


def V(x):
    """Strip {type: literal, value: v}."""
    if isinstance(x, dict) and x.get("type") == "literal":
        return x["value"]
    return x


def vec3(x):
    d = V(x)
    if isinstance(d, dict):
        return [d.get("x", 0.0), d.get("y", 0.0), d.get("z", 0.0)]
    return list(d)


def sid(s):
    return re.sub(r"[^A-Za-z0-9_]", "_", str(s)).strip("_") or "x"


def color_hex(c):
    if isinstance(c, dict):
        return "#%02x%02x%02x" % (int(c.get("r", 255)), int(c.get("g", 255)), int(c.get("b", 255)))
    return c


def fmt(v):
    return ("%g" % v) if isinstance(v, float) else str(v)


def fl(v):
    """Float literal for CEL text (CEL does not mix int and double)."""
    return repr(float(v))


def short(x, n=120):
    return json.dumps(x, sort_keys=True, default=str, ensure_ascii=False)[:n]


class Where:
    def __init__(self, kind, event=None, guard=None):
        self.kind = kind  # cast | handler | nested
        self.event = event
        self.guard = guard
        self.ops = []  # nested only


CAST = Where("cast")


class Conv:
    def __init__(self, d, vfx_dir):
        self.d = d
        self.item = None
        self.vfx_dir = vfx_dir
        self.params = OrderedDict()
        self.frames = OrderedDict()  # gameplay-side frames (skill.yaml)
        self.look_frames = OrderedDict()
        self.g_rows = {}  # (event|None, tick) -> ops
        self.p_rows = {}
        self.p_reps = []  # (at, every, for_ticks, op): bounded repeating presentation rows
        self.g_on = OrderedDict()
        self.p_on = OrderedDict()
        self.id_counts = {}
        self.sel = {}
        self.vfx_n = 0
        self.meta_lines = []
        self.has_bolt = False
        self.todo_gaps = OrderedDict()
        self.present_gaps = OrderedDict()
        self.windup = 0  # ticks every cast-row op is rebased by (v1 `windup`)

    # ---------- gaps (the only place a dropped v1 construct is recorded) ----------
    def gap(self, kind, name, detail=""):
        d = self.todo_gaps if kind == "todo" else self.present_gaps
        e = d.setdefault(name, [0, []])
        e[0] += 1
        if detail and len(e[1]) < 2 and detail not in e[1]:
            e[1].append(detail)

    def todo(self, name, detail=""):
        self.gap("todo", name, detail)

    def present(self, name, detail=""):
        self.gap("present", name, detail)

    # ---------- ids / params / frames ----------
    def oid(self, base):
        base = sid(base)
        n = self.id_counts.get(base, 0) + 1
        self.id_counts[base] = n
        return base if n == 1 else f"{base}_{n}"

    def param(self, base, val):
        """Lift a numeric constant into gameplay.params; returns "${name}" (or the literal for non-numbers)."""
        if not isinstance(val, (int, float)) or isinstance(val, bool):
            return val
        base = sid(base)
        name = base
        i = 1
        while name in self.params:
            if self.params[name] == val:
                return "${%s}" % name
            i += 1
            name = f"{base}_{i}"
        self.params[name] = val
        return "${%s}" % name

    @staticmethod
    def pname(ref):
        return ref[2:-1] if isinstance(ref, str) and ref.startswith("${") else fl(ref)

    def pframe(self, parent, offset, side="g"):
        parent_id = sid(parent)
        off = [round(float(o), 4) for o in offset]
        fid = "pt_%s_%s" % (parent_id, "_".join(fmt(o).replace("-", "m").replace(".", "p") for o in off))
        target = self.frames if side == "g" else self.look_frames
        other = self.look_frames if side == "g" else self.frames
        if fid not in target and fid not in other:
            target[fid] = {"id": fid, "kind": "point", "parent": parent, "offset": off}
        return fid

    # ---------- row / handler placement ----------
    def _put(self, side, where, delay, op):
        if where.kind == "nested":
            if side == "p":
                self.present("presentation inside a nested gameplay list (dash on_hit has no event hook)", op.get("op", ""))
                return
            if delay:
                op = {"id": self.oid("delay"), "op": "delay", "after": delay, "do": [op]}
            where.ops.append(op)
            return
        rows = self.g_rows if side == "g" else self.p_rows
        on = self.g_on if side == "g" else self.p_on
        if where.guard:
            cur = op.get("when")
            g = where.guard
            op["when"] = ("= (" + g[2:] + ") && (" + cur[2:] + ")") if cur else g
        if where.kind == "cast":
            rows.setdefault((None, delay + self.windup), []).append(op)
            return
        if delay == 0:
            on.setdefault(where.event, []).append(op)
        else:
            rows.setdefault((where.event, delay), []).append(op)

    def g(self, where, delay, op, victim_scope=False):
        if where.kind == "handler" and delay:
            # `delay` re-runs its body in the victim binding of the handler (scheduleOps), unlike a timeline row
            op = {"id": self.oid("delay"), "op": "delay", "after": delay, "do": [op]}
            delay = 0
        self._put("g", where, delay, op)

    def p(self, where, delay, op):
        self._put("p", where, delay, op)

    def rep(self, where, delay, every, span, op):
        """A presentation op that repeats every `every` ticks for `span` ticks (v1 `start..end` element / emitter): a
        timeline row `{at, every, for}`. At `span <= every` it is one firing and goes through the plain row path."""
        if span <= every or where.kind == "nested":
            self._put("p", where, delay, op)
            return
        if where.guard:
            cur = op.get("when")
            g = where.guard
            op["when"] = ("= (" + g[2:] + ") && (" + cur[2:] + ")") if cur else g
        at = delay + self.windup if where.kind == "cast" else (delay if where.event is None else where.event if delay == 0 else "%s+%d" % (where.event, delay))
        self.p_reps.append((at, every, span, op))

    # ---------- targets ----------
    def point(self, tg, where, side="p"):
        t = tg["type"] if tg else "caster"
        if t == "caster":
            return "caster"
        if t == "target":
            return "contact.point" if side == "p" and where.kind == "handler" and where.event == "hit" else "enemy"
        if t == "hit_location":
            return "contact.point"
        if t == "caster_forward":
            return self.pframe("caster", [0.0, V(tg.get("height", 0.0)), V(tg.get("distance", 0.0))], side)
        if t == "caster_offset":
            return self.pframe("caster", vec3(tg["offset"]), side)
        if t == "target_offset":
            return self.pframe("enemy", vec3(tg["offset"]), side)
        if t == "projectile_position":
            return "bolt" if self.has_bolt else "contact.point"
        return "enemy"

    def tspec(self, tg, where):
        """Gameplay target: 'caster' | 'victim' | (kind, radius) for {enemies|allies: {around: caster, radius}}."""
        if not tg:
            return "caster"
        t = tg["type"]
        if t == "caster":
            return "caster"
        if t in AREA:
            r = self.param("radius", V(tg.get("radius", tg.get("distance", 3.0))))
            around = self.point(tg["center"], where, "g") if tg.get("center") else "caster"
            if t == "allies":
                return ("allies", r, around)
            if where.kind != "cast":
                if t in ("enemies", "nearby_entities"):
                    return ("enemies", r, around)
                self.todo("cone / forward_ray targeter outside the cast row: used the contact victim", t)
            return "victim"
        return "victim"

    @staticmethod
    def put_target(op, spec, mode):
        if isinstance(spec, str):
            op["target"] = spec
        elif mode == "map":
            op["target"] = {spec[0]: {"around": spec[2], "radius": spec[1]}}
        else:  # movement family: target + sibling around / radius
            op["target"] = spec[0]
            op["around"] = spec[2]
            op["radius"] = spec[1]

    def select(self, tg, delay, m=None):
        """Area targeter in a cast context -> one `hit` op (instant) at row `delay`; returns the handler Where."""
        key = (json.dumps(tg, sort_keys=True), delay)
        if key in self.sel:
            return self.sel[key]
        t = tg["type"]
        hid = self.oid("sel")
        op = {"id": hid, "op": "hit", "frames": ["caster"]}
        if t == "cone":
            op["sector"] = {"frame": "caster", "at": "eye", "forward": "caster.forward", "left": "caster.left",
                            "angle_deg": self.param("arc_deg", V(tg["angle_degrees"])),
                            "range": self.param("reach", V(tg["distance"])), "height": 2.0}
        elif t in ("nearby_entities", "enemies"):
            op["sphere"] = {"at": "caster", "radius": self.param("radius", V(tg.get("radius", tg.get("distance", 3.0))))}
            if V(tg.get("exclude_caster", True)) is False:
                self.todo("nearby_entities exclude_caster=false (hit never targets the caster)", t)
        elif t == "forward_ray":
            to = self.pframe("aim", [0.0, 0.0, V(tg["distance"])], "g")
            op["capsule"] = {"from": "aim", "to": to, "radius": 0.3}
        else:
            op["sphere"] = {"at": "caster", "radius": 3.0}
        if "max_count" in tg:
            op["max_count"] = int(V(tg["max_count"]))
        op["window"] = "once"
        if m is not None and V(m.get("snapshot_pose", False)):
            # the volume rides a frozen copy of the caster pose taken at this tick, not the live caster
            snap = self.oid("snap")
            self.frames[snap] = {"id": snap, "kind": "body", "parent": "world", "inactive": True}
            self.g(CAST, delay, {"id": self.oid("place_" + snap), "op": "place", "frame": snap, "like": "caster"})
            op["frames"] = [snap]
            if "sector" in op:
                op["sector"].update({"frame": snap, "forward": snap + ".forward", "left": snap + ".left"})
            elif "sphere" in op:
                op["sphere"]["at"] = snap
            elif "capsule" in op:
                op["capsule"]["from"] = snap
        self.g(CAST, delay, op)
        w = Where("handler", "hit", f"= event.source == '{hid}'")
        self.sel[key] = w
        return w

    def prune_idle_hits(self):
        """Drop `hit` volumes no gameplay handler listens to (v1 area targets used only by play_vfx / sound): they
        would be invisible hit volumes without an effect. Their presentation handlers go with them."""
        def mentions(op, hid):
            return ("'%s'" % hid) in (op.get("when") or "")
        for rows_key, rows in list(self.g_rows.items()):
            for op in list(rows):
                if op.get("op") != "hit":
                    continue
                hid = op["id"]
                served = any(mentions(o, hid) for lst in self.g_on.values() for o in lst) or \
                    any(mentions(o, hid) for rs in self.g_rows.values() for o in rs)
                if served:
                    continue
                rows.remove(op)
                n = 0
                for store in (self.p_on, self.p_rows):
                    for k, lst in store.items():
                        keep = [o for o in lst if not mentions(o, hid)]
                        n += len(lst) - len(keep)
                        lst[:] = keep
                self.present("area target used only by presentation ops (no damaging hit volume to attach them to)",
                             "%s: %d presentation op(s) dropped" % (hid, n))
            if not rows:
                del self.g_rows[rows_key]

    # ---------- conditions ----------
    def cond(self, m):
        c = m.get("condition")
        if not c:
            return None
        if c["type"] == "distance_less_than" and c["b"]["type"] in ("caster", "victim", "target"):
            def ref(x):
                return "caster" if x["type"] == "caster" else "victim"
            return "= dist(pos(%s), pos(%s)) < %s" % (ref(c["a"]), ref(c["b"]), fl(V(c["distance"])))
        self.todo("mechanic condition %s" % c["type"], short(c))
        return None

    # ---------- mechanics ----------
    def mechanics(self, lst, where, base_delay=0):
        for m in lst or []:
            self.mech(m, where, base_delay)

    def mech(self, m, where, base_delay=0):
        t = m["type"]
        delay = int(V(m.get("delay_ticks", 0)) or 0) + base_delay
        tg = m.get("targets")
        if t == "for_each_target":
            w = self.select(tg, delay, m) if where.kind == "cast" and tg["type"] != "allies" else where
            for sub in m.get("mechanics", []):
                self.mech(sub, w, 0 if w is not where else delay)
            return
        if t == "dash_strike":
            self.dash(m, where, delay)
            return
        if t == "counter":
            self.counter(m, where, delay)
            return
        if tg and tg["type"] in AREA and tg["type"] != "allies" and t not in ("camera_shake", "screen_flash", "screen_veil"):
            if where.kind == "cast":
                where = self.select(tg, delay, m)
                delay = 0
                tg = {"type": "target"}
        when = self.cond(m)
        if t in GAMEPLAY:
            self.gameplay_op(m, t, tg, where, delay, when)
        elif t in PRESENT:
            self.presentation_op(m, t, tg, where, delay, when)
        else:
            self.todo("unknown v1 mechanic %s" % t, short(m, 200))

    def dir_expr(self, d, for_propel=False):
        """v1 direction -> CEL vec3 text (no leading '=')."""
        t = d["type"]
        vert = V(d.get("vertical", 0.0))
        if t == "up":
            base = "vec(0.0, 1.0, 0.0)"
        elif t == "toward_caster":
            base = "normalize(pos(caster) - pos(victim))" if not for_propel else "-axis(caster, \"forward\")"
        elif t in ("away_from_caster", "from_caster"):
            base = "normalize(pos(victim) - pos(caster))" if not for_propel else "axis(caster, \"forward\")"
        else:
            base = "axis(caster, \"forward\")"
        if vert and t != "up":
            base += " + vec(0.0, %s, 0.0)" % fl(vert)
        return "= " + base

    def gameplay_op(self, m, t, tg, where, delay, when):
        vs = where.kind == "handler" and tg is not None and tg.get("type") == "target"
        spec = self.tspec(tg, where)
        if t == "damage":
            comps = [{"kind": c["attribute"], "amount": self.param("dmg_" + c["attribute"], V(c["base"]))} for c in m["components"]]
            for comp, c in zip(comps, m["components"]):
                if "per_stack" in c:
                    ps = c["per_stack"]
                    comp["per_stack"] = {"status": V(ps["status_id"]), "amount": V(ps["amount"]),
                                         "on": "victim" if V(ps.get("on", "caster")) in ("target", "victim") else "caster",
                                         "consume": bool(V(ps.get("consume", False)))}
            op = {"id": self.oid("dmg"), "op": "damage", "components": comps}
            self.put_target(op, spec if spec != "caster" or tg else "victim", "map")
            if V(m.get("ignore_invulnerability", False)):
                op["ignore_invulnerability"] = True
        elif t == "apply_status":
            sname = m["status_id"]
            op = {"id": self.oid("st_" + sname), "op": "status", "status": sname,
                  "duration_ticks": self.param(sid(sname) + "_ticks", V(m["effect_duration_ticks"]))}
            self.put_target(op, spec, "map")
            if "stack_count" in m:
                op["stacks"] = self.param(sid(sname) + "_stacks", V(m["stack_count"]))
            if "chance" in m:
                op["chance"] = V(m["chance"])
        elif t == "knockback":
            s = self.param("knockback", V(m["strength"]))
            op = {"id": self.oid("kb"), "op": "knockback", "direction": self.dir_expr(m["direction"]), "strength": s}
            if spec != "victim":
                self.put_target(op, spec, "move")
        elif t == "propel":
            s = self.param("propel", V(m["strength"]))
            op = {"id": self.oid("propel"), "op": "propel", "direction": self.dir_expr(m["direction"], True), "strength": s}
            if spec != "caster":
                self.put_target(op, spec, "move")
        elif t == "hit_stop":
            scope = ["enemy"]
            if V(m.get("include_caster", True)):
                scope.append("caster")
            op = {"id": self.oid("stop"), "op": "freeze", "ticks": V(m["ticks"]), "scope": scope}
            if V(m.get("freeze_animation", False)):
                self.todo("hit_stop freeze_animation", "freeze_animation=true")
        elif t == "cast_skill":
            op = {"id": self.oid("cast"), "op": "cast_skill", "skill": m["skill_id"]}
        elif t == "explode":
            op = {"id": self.oid("explode"), "op": "explode", "power": self.param("explode_power", V(m["power"])),
                  "break_blocks": bool(V(m.get("break_blocks", False))), "radius": self.param("explode_radius", V(m["radius"])),
                  "components": [{"kind": c["attribute"], "amount": self.param("explode_" + c["attribute"], V(c["base"]))}
                                 for c in m.get("components", [])]}
            tt = tg["type"] if tg else "caster"
            if tt == "caster":
                op["target"] = "caster"
            else:
                if tt == "target":
                    op["target"] = "victim"
                else:
                    op["at"] = _loc(self.point(tg, where, "g"))
        elif t == "break_area":
            op = {"id": self.oid("break"), "op": "break_area"}
            shape = V(m.get("shape"))
            if shape is not None:
                if str(shape).lower() in SHAPES_BREAK:
                    op["shape"] = str(shape).lower()
                else:
                    self.todo("break_area shape %s" % shape)
            for k in ("length", "width", "height", "radius", "tool_class", "block_damage"):
                if k in m:
                    op[k] = V(m[k])
        elif t == "place_shape":
            op = {"id": self.oid("place"), "op": "place_shape"}
            for k in ("shape", "block_key", "width", "height", "length", "consume_from_inventory", "revert_after_ticks"):
                if k not in m:
                    continue
                key = {"block_key": "block", "consume_from_inventory": "consume_item", "revert_after_ticks": "revert_after"}.get(k, k)
                val = V(m[k])
                if k == "shape":
                    if str(val).lower() not in SHAPES_BUILD:
                        self.todo("place_shape shape %s" % val)
                        continue
                    val = str(val).lower()
                if k == "consume_from_inventory" and not isinstance(val, str):
                    if val:
                        self.todo("place_shape consume_from_inventory=true without an item id")
                    continue
                op[key] = val
        elif t == "heal":
            op = {"id": self.oid("heal"), "op": "heal", "amount": self.param("heal", V(m["amount"]))}
            self.put_target(op, spec, "map")
            if tg and tg["type"] == "allies" and V(tg.get("include_self", True)) is False:
                self.todo("allies include_self=false (heal / cleanse hit the caster too)")
        elif t == "cleanse":
            op = {"id": self.oid("cleanse"), "op": "cleanse", "negative_only": True}
            self.put_target(op, spec, "map")
        elif t == "consume_item":
            op = {"id": self.oid("consume"), "op": "consume_item", "target": "caster", "item": m["item_id"], "count": 1}
        elif t == "summon_lightning":
            op = {"id": self.oid("lightning"), "op": "summon_lightning", "at": self.point(tg, where, "g"),
                  "visual_only": bool(V(m.get("visual_only", False)))}
        elif t == "leap":
            op = {"id": self.oid("leap"), "op": "leap", "to": self.point(m["destination"], where, "g"),
                  "arc_height": V(m["arc_height"])}
        elif t == "modify_attribute":
            op = {"id": self.oid("attr"), "op": "modify_attribute", "attribute": m["attribute_key"],
                  "operation": "add", "amount": V(m["delta"]), "duration_ticks": V(m["ticks"])}
            self.put_target(op, spec, "map")
        else:
            raise AssertionError(t)
        if when:
            op["when"] = when
        self.g(where, delay, op, victim_scope=vs)

    def dash(self, m, where, delay):
        did = self.oid("dash")
        op = {"id": did, "op": "dash_strike", "distance": self.param("dash_distance", V(m["distance"])),
              "ticks": V(m["ticks"]), "radius": V(m["radius"]), "hit_once": bool(V(m.get("hit_once", True))),
              "invulnerable": bool(V(m.get("invulnerable", False))), "stop_on_block": bool(V(m.get("stop_on_block", True))),
              "follow_pitch": bool(V(m.get("follow_pitch", False)))}
        for k in ("stop_before_target", "local_direction", "face_movement"):
            if k in m:
                val = V(m[k])
                op[k] = vec3(val) if k == "local_direction" else val
        w = self.cond(m)
        if w:
            op["when"] = w
        nested = Where("nested")
        self.mechanics(m.get("on_hit", []), nested, 0)
        if nested.ops:
            op["on_hit"] = nested.ops
        self.g(where, delay, op)

    def counter(self, m, where, delay):
        kinds = sorted({x["type"] for x in m.get("on_hit", [])})
        self.todo("counter (parry window) has no v2 op; its on_hit mechanics were dropped",
                  "window_ticks=%s on_hit=%s" % (V(m["window_ticks"]), ",".join(kinds)))

    # ---------- presentation ----------
    def presentation_op(self, m, t, tg, where, delay, when):
        if where.kind == "nested":
            self.present("presentation inside a nested gameplay list (dash on_hit has no event hook)", t)
            return

        def W(op):
            self.p(where, delay, self._w(op, when))

        if t == "play_sound":
            op = {"id": self.oid("snd"), "op": "sound", "sound": m["sound_id"], "at": self.point(tg, where, "p")}
            if "volume" in m:
                op["volume"] = V(m["volume"])
            if "pitch" in m:
                op["pitch"] = V(m["pitch"])
            W(op)
        elif t == "spawn_particle":
            W(emit_op(self.oid("emit"), self.point(tg, where, "p"), m["particle_id"], 1))
        elif t == "schedule_particle_animation":
            at = self.point(tg, where, "p")
            for anim in m["animation"]:
                self.anim(anim, at, where, delay, when)
        elif t == "play_vfx":
            self.vfx(m, tg, where, delay, when)
        elif t == "camera_shake":
            W({"id": self.oid("shake"), "op": "camera_shake", "target": self.screen_target(tg),
               "intensity": V(m["intensity"]), "ticks": V(m["ticks"])})
            for k in ("shape", "direction", "viewers"):
                if k in m:
                    self.present("camera_shake %s (engine shake is intensity + ticks only)" % k, "%s=%s" % (k, short(V(m[k]), 40)))
        elif t == "play_animation":
            anim = m["animation"]
            if tg and tg["type"] == "caster":
                W({"id": self.oid("anim"), "op": "caster_anim", "anim": anim})
            else:
                W({"id": self.oid("anim"), "op": "victim_anim", "target": self.screen_target(tg), "anim": anim})
            self.present("play_animation ticks (anim ops have no duration)", "ticks=%s" % V(m.get("ticks")))
        else:  # screen_flash, screen_veil, telegraph, stop_sound, hide_held_item
            frag = {k: V(v) for k, v in m.items() if k not in ("type", "targets", "delay_ticks")}
            self.present("%s has no v2 op" % t, short(frag, 160))

    def _w(self, op, when):
        if when:
            op["when"] = when
        return op

    def screen_target(self, tg):
        if not tg:
            return "caster"
        t = tg["type"]
        if t == "caster":
            return "caster"
        if t in AREA:
            r = V(tg.get("radius", tg.get("distance", 8.0)))
            if t == "allies":
                return {"allies": {"around": "caster", "radius": r}}
            if t in ("enemies", "nearby_entities"):
                return {"enemies": {"around": "caster", "radius": r}}
            return "victim"
        return "victim"

    def anim(self, a, at, where, delay, when):
        t = a["type"]
        d2 = delay + int(V(a.get("delay_ticks", 0)) or 0)
        if t in ("ring", "circle", "line"):
            self.present("particle animation %s (emit has no shape / ring / path: one burst at the anchor)" % t,
                         "%s" % short({k: a[k] for k in a if k not in ("particles", "type")}, 100))
        for part in a.get("particles", []) or []:
            op = emit_op(self.oid("emit"), at, part["id"], part.get("count", 1))
            particle_vanilla(op, part)
            self.p(where, d2, self._w(op, when))

    # ---------- vfx inlining ----------
    def load_vfx(self, vid):
        p = os.path.join(self.vfx_dir, vid + ".yaml")
        if not os.path.exists(p):
            return None
        return yaml.safe_load(open(p))

    def frame_parent(self, parent):
        ok = parent in ("world", "caster", "enemy") or parent.startswith("caster.") or parent in self.frames or parent in self.look_frames \
            or (parent == "bolt" and self.has_bolt)
        return parent if ok else None

    def vfx(self, m, tg, where, delay, when):
        vid = m["vfx_id"]
        d = self.load_vfx(vid)
        if d is None:
            self.todo("missing vfx file", vid)
            return
        self.vfx_n += 1
        fid = "fx%d_%s" % (self.vfx_n, sid(vid))
        want = self.point(tg, where, "p") if tg else "caster"
        follow = bool(V(m.get("follow", d.get("follow", True))))
        parent = self.frame_parent(want) if follow else "world"
        if parent is None:
            self.present("vfx anchored at a runtime point (frame parent must exist at cast start): anchored at world", want)
            parent = "world"
        if not follow:
            self.present("vfx follow=false (anchored at world, no place-at-spawn)", vid)
        self.look_frames[fid] = {"id": fid, "kind": "body", "parent": parent}
        for k in ("orient", "scale"):
            if k in d or k in m:
                self.present("vfx frame %s (frames carry no orientation / scale)" % k, "%s=%s" % (k, short(V(m.get(k, d.get(k))), 30)))
        for k in ("palette", "follow_teleport_ticks", "attach_to_anchor", "replace_group", "follow_pitch", "ghost_bodies",
                  "target_reference"):
            if k in d:
                self.present("vfx %s (no v2 equivalent)" % k, short(d[k], 60))
        for k in ("freeze", "track_bound_target", "viewers", "snapshot_pose"):
            if k in m:
                self.present("play_vfx %s (no v2 equivalent)" % k, short(V(m[k]), 40))
        dur = int(d.get("duration_ticks", 20))
        removes = {}
        body = d.get("body")
        if body:
            self.p(where, delay, self._w({"id": self.oid("canim"), "op": "caster_anim", "anim": body}, when))
        for el in d.get("elements", []):
            self.vfx_element(el, fid, where, delay, when, dur, removes)
        for r in d.get("rigs", []):
            self.vfx_rig(r, fid, where, delay, when, dur, removes)
        for tick, ids in sorted(removes.items()):
            self.p(where, delay + tick, self._w({"id": self.oid("rm"), "op": "remove", "ids": ids}, when))

    @staticmethod
    def sprite_size(sprite):
        """Natural glyph size in blocks: sprite px * 0.025 (a 256 px sprite is 6.4 blocks)."""
        px = 32
        f = os.path.join(REPO, "content", "sprites", sprite + ".yaml")
        if os.path.exists(f):
            px = int(yaml.safe_load(open(f)).get("size", 32))
        return round(px * 0.025, 4)

    def keys(self, kfs):
        out = []
        for k in kfs:
            e = {"t": k["t"]}
            if "translate" in k:
                e["offset"] = k["translate"]
            if "rotate" in k:
                e["rotation"] = k["rotate"]
            if "scale" in k:
                sc = k["scale"]
                if isinstance(sc, list):
                    nums = [x for x in sc if isinstance(x, (int, float))]
                    sc = [float(x) for x in nums[:3]] + [1.0] * (3 - len(nums[:3]))
                e["scale"] = sc
            if k.get("ease") in ("linear", "ease_in", "ease_out", "ease_in_out"):
                e["ease"] = k["ease"]
            out.append(e)
        return convert_keys(out)

    def vfx_element(self, el, fid, where, delay, when, dur, removes):
        eid = "%s_%s" % (fid, sid(el["id"]))
        kind = el["kind"]
        kt = kind["type"]
        start = int(el.get("start_tick", 0))
        end = int(el.get("end_tick", dur))
        at = delay + start
        bb = el.get("billboard")
        face = {"viewer": "center"} if bb == "center" else {"viewer": "vertical"} if bb == "vertical" \
            else {"plane": {"frame": fid, "axes": ["left", "up"]}}
        op = None
        if kt == "item":
            op = {"id": eid, "op": "mesh", "frame": fid, "item": kind["item"]}
            if kind.get("item_model") or kind.get("display_mode"):
                self.present("item element item_model / display_mode (mesh has item + part only)", short({k: kind.get(k) for k in ("item_model", "display_mode")}, 60))
        elif kt == "sprite":
            op = {"id": eid, "op": "plate", "parent": fid, "face": face, "sprite": kind["sprite"], "size": self.sprite_size(kind["sprite"])}
            if "alpha" in kind:
                op["alpha"] = kind["alpha"]
            if kind.get("frame_ticks", 1) != 1:
                op["frame_ticks"] = int(kind["frame_ticks"])
            if kind.get("loop"):
                op["loop"] = True
            if "color" in kind:
                op["color"] = color_hex(kind["color"])
        elif kt == "particle":
            at_frame = fid
            moving = [k for k in el.get("keyframes", []) if "translate" in k]
            if moving:
                # The authored per-tick emit position: a frame under the vfx frame that a track moves, the emit sits on it.
                at_frame = eid + "_at"
                self.look_frames[at_frame] = {"id": at_frame, "kind": "body", "parent": fid}
                self.p(where, at, self._w({"id": eid + "_atrk", "op": "track", "target": at_frame, "space": fid,
                                           "keys": self.keys([{"t": k["t"], "translate": k["translate"], **({"ease": k["ease"]} if "ease" in k else {})} for k in moving])}, when))
            op = emit_op(eid, at_frame, kind["particle_id"], 1)
            if "along_path" in kind:
                self.present("particle along_path", short(kind["along_path"], 60))
        elif kt in ("thin_line", "dotted_line"):
            a = self.pframe(fid, kind["from"], "p")
            b = self.pframe(fid, kind["to"], "p")
            op = {"id": eid, "op": "line", "from": a, "to": b, "thickness": kind.get("width", kind.get("spacing", 0.05)),
                  "color": color_hex(kind["color"]), "face": {"viewer": "axis", "axis": "line.along"}}
            if kt == "dotted_line":
                self.present("dotted_line (line is solid)", short(kind, 60))
        elif kt == "block":
            self.present("block element (mesh takes an item model, plate takes a sprite: no block / solid-colour primitive)", kind.get("block", ""))
        elif kt == "quad":
            self.present("quad element (plate takes a sprite: no solid-colour quad / glyph)", short({k: kind.get(k) for k in ("color", "glyph")}, 60))
        else:
            self.present("element kind %s (text) has no v2 op" % kt, short(kind, 80))
        for k in ("group_keyframes", "target_track", "formation", "count", "jitter", "oscillate", "lane", "emit",
                  "slot_lifetime_ticks", "glow_color", "target_line", "respect_hit_stop", "brightness"):
            if k in el:
                self.present("element %s (no v2 equivalent)" % k, short(el[k], 60))
        if op is None:
            return
        kfs = el.get("keyframes", [])
        if kt == "particle":
            self.rep(where, at, max(1, int(kind.get("every_ticks", 1))), end - start, self._w(op, when))
        else:
            self.p(where, at, self._w(op, when))
        if kfs and any(x in k for k in kfs for x in ("translate", "rotate", "scale")) and op["op"] in ("mesh", "plate", "line"):
            self.p(where, at, self._w({"id": eid + "_trk", "op": "track", "target": eid, "space": fid, "keys": self.keys(kfs)}, when))
            for k in kfs:
                for x in ("alpha", "color", "block", "text"):
                    if x in k:
                        self.present("keyframe %s (track animates offset / rotation / scale / ease only)" % x)
                        break
        elif kfs and kt != "particle":
            self.present("element keyframes without a transform (alpha / colour only)")
        if op["op"] in ("mesh", "plate", "line"):
            removes.setdefault(end, []).append(eid)

    def vfx_rig(self, r, fid, where, delay, when, dur, removes):
        rid = "%s_%s" % (fid, sid(r["id"]))
        parent = r.get("parent", "right_hand")
        pr = {"right_hand": "caster.right_hand", "left_hand": "caster.left_hand"}.get(parent, parent)
        if self.frame_parent(pr) is None:
            self.present("rig parent %s is not a known frame: anchored at caster" % pr)
            pr = "caster"
        self.look_frames[rid] = {"id": rid, "kind": "body", "parent": pr}
        extras = [k for k in ("respect_hit_stop", "weapon_model", "weapon_tip", "weapon_root", "weapon_edge") if k in r]
        if extras:
            self.present("rig respect_hit_stop / weapon points", short({k: r[k] for k in extras}, 80))
        start = delay + int(r.get("start_tick", 0))
        end = int(r.get("end_tick", dur))
        first = None
        if r.get("weapon"):
            first = rid + "_weapon"
            mesh = {"id": first, "op": "mesh", "frame": rid, "item": r["weapon"]}
            for key in ("pivot", "rest", "tilt"):
                val = r.get(key)
                if val and any(float(x) != 0.0 for x in val):
                    mesh[key if key == "pivot" else key + "_turn"] = [float(x) for x in (val if key == "pivot" else euler_to_vec(val))]
            self.p(where, start, self._w(mesh, when))
            removes.setdefault(end, []).append(first)
        if r.get("parts"):
            self.present("rig block parts (mesh takes an item model, not a block + size)", "%d parts" % len(r["parts"]))
        if r.get("swing"):
            keys = []
            for k in r["swing"]:
                e = {"t": k["t"], "rotation": k["rotate"]}
                if k.get("ease") in ("linear", "ease_in", "ease_out", "ease_in_out"):
                    e["ease"] = k["ease"]
                keys.append(e)
            keys = convert_keys(keys)
            if first:
                self.p(where, start, self._w({"id": rid + "_swing", "op": "track", "target": first, "space": pr, "keys": keys}, when))
            else:
                self.present("rig swing without a weapon mesh (track needs a visual to move)", "%d keys" % len(keys))
        for tr in r.get("trails", []):
            a = self.pframe(rid, tr["from"], "p")
            b = self.pframe(rid, tr["to"], "p")
            op = {"id": "%s_%s" % (rid, sid(tr["id"])), "op": "trail", "of": b, "root": a, "ticks": tr["life_ticks"]}
            if tr.get("style", "soft") != "soft":
                op["style"] = tr["style"]
            if "fade_power" in tr and float(tr["fade_power"]) != 1.0:
                op["fade"] = float(tr["fade_power"])
            op.update(trail_shape(tr))
            self.p(where, delay + int(tr["start_t"]), self._w(op, when))
            removes.setdefault(int(tr["end_t"]) + int(tr["life_ticks"]), []).append(op["id"])
        for em in r.get("emitters", []):
            pt = self.pframe(rid, em["point"], "p")
            op = emit_op("%s_%s" % (rid, sid(em["id"])), pt, em["particle_id"], 1)
            self.rep(where, delay + int(em["start_t"]), 1, int(em["end_t"]) - int(em["start_t"]), self._w(op, when))


# ---------------------------------------------------------------- skill-level driver

_PARTICLE_COUNTS = {}


def _custom_count(pid):
    """The `count:` of content/particles/<pid>.yaml, or None when pid is a vanilla particle key."""
    if pid not in _PARTICLE_COUNTS:
        path = os.path.join(REPO, "content", "particles", pid + ".yaml")
        _PARTICLE_COUNTS[pid] = None
        if os.path.exists(path):
            with open(path) as fh:
                _PARTICLE_COUNTS[pid] = int((yaml.safe_load(fh) or {}).get("count", 1))
    return _PARTICLE_COUNTS[pid]


def emit_op(oid, at, pid, v1_count):
    """An `emit` op. Vanilla key: `count` is the vanilla particle count. Custom definition: `count` is the number of
    particles, so a v1 repeat of N (N whole definitions) becomes N * the definition's own count; one repeat leaves it unset."""
    op = {"id": oid, "op": "emit", "at": at, "particle": pid}
    dc = _custom_count(pid)
    if dc is None:
        op["count"] = v1_count
    elif v1_count != 1:
        op["count"] = int(v1_count) * dc
    return op


def particle_vanilla(op, part):
    """v1 particle part -> the vanilla `spawnParticle` arguments, 1:1: offset, speed (= extra), data (dust colour) and size."""
    off = part.get("offset")
    if off:
        op["offset"] = [off.get("x", 0.0), off.get("y", 0.0), off.get("z", 0.0)] if isinstance(off, dict) else off
    if "extra" in part:
        op["speed"] = part["extra"]
    if "color" in part:
        op["data"] = color_hex(part["color"])
    if "scale" in part:
        op["data_size"] = part["scale"]


def _loc(point):
    """Gameplay `at:` location: `contact.point` is a CEL variable, not a frame point, so it needs the value form."""
    return "= contact.point" if point == "contact.point" else point


def block_damage(c, d, arch, on_hit, on_block):
    """v1 `block_damage` (ADR-0280) -> a `break_area` chip at the impact / cast point."""
    bd = d.get("block_damage")
    if not bd:
        return
    op = {"op": "break_area", "shape": "sphere", "radius": max(float(bd.get("radius", 0.0)), 0.5),
          "block_damage": int(bd["amount"]), "breaks": True,
          # A sphere is centred `length` ahead of `at` (tool skills dig ahead); a v1 chip is centred ON the impact / cast point.
          "length": 0.0}
    if bd.get("tool"):
        op["tool_class"] = str(bd["tool"]).split("/")[0].upper()
    if arch == "projectile":
        for where in (on_hit, on_block):
            c.g(where, 0, dict(op, id=c.oid("chip"), at="= contact.point"))
    elif arch == "aoe":
        c.g(CAST, 0, dict(op, id=c.oid("chip"), at="caster"))
    else:
        reach = float(bd.get("reach", 0.0)) or 4.0
        c.g(CAST, 0, dict(op, id=c.oid("chip"), at=c.pframe("aim", [0.0, 0.0, reach], "g")))

def convert_skill(d, vfx_dir, item):
    c = Conv(d, vfx_dir)
    c.item = item
    arch = d["archetype"]
    params = d["params"]
    phases = d["phases"]

    def has_area(lst):
        for m in lst or []:
            tg = m.get("targets")
            if m["type"] in ("dash_strike", "for_each_target") or (tg and tg["type"] in AREA):
                return True
        return False
    guard_proj = arch == "projectile" and has_area(phases.get("cast"))

    if "windup" in d:
        wu = d["windup"]
        if wu.get("vfx"):
            c.vfx({"vfx_id": wu["vfx"]}, {"type": "caster"}, CAST, 0, None)
        if wu.get("sound"):
            sd = wu["sound"]
            c.mech({"type": "play_sound", "sound_id": sd["sound_id"], "volume": sd.get("volume", 1.0),
                    "targets": {"type": "caster"}}, CAST)
            if sd.get("pitch_arc"):
                c.present("windup sound pitch_arc (sound has a fixed pitch)", short(sd["pitch_arc"], 80))
        if d.get("id") in WINDUP_LOCK:
            # SKL-15 row 13: commit the caster for the windup (v1 had no lock); the row sits at tick 0, before the offset
            c.g(CAST, 0, {"id": "windup_lock", "op": "lock", "target": "caster", "speed": WINDUP_LOCK[d["id"]], "ticks": int(V(wu["ticks"]))})
        c.windup = int(V(wu["ticks"]))

    if arch == "melee":
        c.id_counts["swing"] = 1
        c.g(CAST, 0, {"id": "swing", "op": "hit", "frames": ["caster"],
                      "sector": {"frame": "caster", "at": "eye", "forward": "caster.forward", "left": "caster.left",
                                 "angle_deg": c.param("arc_deg", params["arc_degrees"]), "range": c.param("range", params["range"]),
                                 "height": 2.0},
                      "window": "once"})
        w = Where("handler", "hit", "= event.source == 'swing'")
        for comp in params.get("damage", []):
            c.g(w, 0, {"id": c.oid("dmg"), "op": "damage", "target": "victim",
                       "components": [{"kind": comp["attribute"], "amount": c.param("dmg_" + comp["attribute"], V(comp["base"]))}]})
        if params.get("knockback"):
            kb = c.param("knockback", params["knockback"])
            c.g(w, 0, {"id": c.oid("kb"), "op": "knockback", "direction": "= normalize(pos(victim) - pos(caster))", "strength": kb})
        c.mechanics(phases.get("cast"), CAST)
        c.mechanics(phases.get("hit_entity"), w)
        block_damage(c, d, arch, None, None)
        if phases.get("miss"):
            c.g(w, 0, {"id": "swing_hit_count", "op": "counter", "key": "swing_hits"})
            # the swing volume is resolved on its first ticks; the miss phase runs once it is clear nothing was hit
            c.mechanics(phases["miss"], Where("cast", guard="= counter('swing_hits') == 0.0"), 2)
    elif arch == "aoe":
        c.id_counts["burst"] = 1
        radius = c.param("radius", params["radius"])
        c.g(CAST, 0, {"id": "burst", "op": "hit", "frames": ["caster"], "sphere": {"at": "caster", "radius": radius}, "window": "once"})
        w = Where("handler", "hit", "= event.source == 'burst'")
        for comp in params.get("damage", []):
            amt = c.param("dmg_" + comp["attribute"], V(comp["base"]))
            if params.get("falloff") == "linear":
                amt = "= %s * (1.0 - 0.5 * dist(pos(caster), pos(victim)) / %s)" % (c.pname(amt), c.pname(radius))
            c.g(w, 0, {"id": c.oid("dmg"), "op": "damage", "target": "victim", "components": [{"kind": comp["attribute"], "amount": amt}]})
        c.mechanics(phases.get("cast"), CAST)
        c.mechanics(phases.get("hit_entity"), w)
        c.mechanics(phases.get("expire"), Where("handler", "end", None))
        block_damage(c, d, arch, None, None)
    elif arch == "projectile":
        c.has_bolt = True
        c.frames["bolt"] = {"id": "bolt", "kind": "body", "parent": "world", "inactive": True}
        c.id_counts["bolt"] = 1
        up = V(params.get("muzzle_up", 0.0))
        fw = V(params.get("muzzle_forward", 0.5))
        muzzle = c.pframe("aim", [0.0, up, fw], "g")
        speed = c.param("launch_speed", params["launch_speed"])
        life = c.param("max_lifetime_ticks", params["max_lifetime_ticks"])
        # v1: aim_distance 0 (default) = fly parallel to the look ray; > 0 = dive onto the look-ray point.
        if params.get("aim_distance", 0.0) > 0.0:
            aim = c.param("aim_distance", V(params["aim_distance"]))
            vel = "= normalize(aim_point(caster, %s) - pos(%s)) * %s" % (c.pname(aim), muzzle, c.pname(speed))
        else:
            vel = "= look(caster) * %s" % c.pname(speed)
        c.g(CAST, 0, {"id": "bolt", "op": "projectile", "body": "bolt", "from": muzzle, "velocity": vel,
                      "max_ticks": life, "radius": V(params.get("hit_radius", 0.5))})
        guard = "= event.source == 'bolt'" if guard_proj else None
        wh = Where("handler", "hit", guard)
        wb = Where("handler", "block", guard)
        we = Where("handler", "expire", guard)
        vis = params.get("visual") or {}
        for tr in vis.get("trail") or []:
            for part in tr.get("particles", []):
                op = emit_op(c.oid("trail"), "bolt", part["id"], part.get("count", 1))
                if vis.get("trail_spacing", 0.5) > 0:
                    op["spacing"] = vis.get("trail_spacing", 0.5)
                particle_vanilla(op, part)
                if tr["type"] == "line":
                    c.present("projectile trail shape (emit has none)", tr["type"])
                c.p_rows.setdefault(("rep", 1), []).append(op)
        if vis.get("display"):
            disp = vis["display"]
            op = {"id": c.oid("mesh"), "op": "mesh", "frame": "bolt", "item": disp.get("material")}
            if disp.get("scale"):
                op["scale"] = disp["scale"]
            c.present("projectile display.material used as the mesh item id (unverified); variant dropped",
                      "%s variant=%s" % (disp.get("material"), disp.get("variant")))
            c.p(CAST, 0, op)
        if vis.get("vfx"):
            c.vfx({"vfx_id": vis["vfx"]}, {"type": "projectile_position"}, CAST, 0, None)
        c.mechanics(phases.get("cast"), CAST)
        c.mechanics(phases.get("hit_entity"), wh)
        c.mechanics(phases.get("hit_block"), wb)
        c.mechanics(phases.get("expire"), we)
        c.mechanics(phases.get("miss"), we)
        block_damage(c, d, arch, wh, wb)
    elif arch == "buff":
        dur = c.param(sid(params["status_effect_id"]) + "_ticks", params["duration_ticks"])
        c.g(CAST, 0, {"id": c.oid("buff"), "op": "status", "target": "caster", "status": params["status_effect_id"], "duration_ticks": dur})
        c.mechanics(phases.get("cast"), CAST)
        if phases.get("expire"):
            c.g(CAST, 0, {"id": c.oid("sch"), "op": "schedule", "after": dur, "event": "buff_end"})
            c.mechanics(phases["expire"], Where("handler", "buff_end", None))
    else:  # sequence
        c.mechanics(phases.get("cast"), CAST)

    return c


def rows_out(rows, prefix):
    out = []
    for (ev, tick), ops in sorted(rows.items(), key=lambda kv: (kv[0][0] or "", kv[0][1])):
        at = tick if ev is None else f"{ev}+{tick}"
        rid = "%s%s" % (prefix, str(at).replace("+", "_"))
        out.append({"id": rid, "at": at, "do": ops})
    out.sort(key=lambda r: (not isinstance(r["at"], int), r["at"] if isinstance(r["at"], int) else 0))
    return out


def gap_lines(gaps, kind):
    out = []
    for name, (n, samples) in gaps.items():
        s = "CONVERT-%s %s (x%d)" % (kind, name, n)
        if samples:
            s += ": " + " | ".join(samples)
        out.append(s[:330])
    return out


def meta_block(d):
    """v1 skill header -> the v2 `meta:` block (cast gate + UI)."""
    m = OrderedDict()
    if "name" in d:
        m["name"] = d["name"]
    if "description" in d:
        m["description"] = d["description"]
    for k in ("cooldown_ticks", "resource_type", "resource_cost", "ego_cost", "reload_ticks", "ready_cue"):
        if k in d:
            m[k] = d[k]
    tags = [t for t in d.get("tags", []) if t not in ("demo", "vfx", "rtm", "animation")]
    if tags:
        m["tags"] = tags
    # Fail loudly: a v1 cast gate (cost / cooldown / reload / ego) that the v2 meta did not carry would silently make the skill free.
    for k in ("cooldown_ticks", "resource_type", "resource_cost", "ego_cost", "reload_ticks"):
        if k in d and m.get(k) != d[k]:
            raise SystemExit("meta_block: %s: v1 %s=%r was not carried into the v2 meta (%r)" % (d.get("id"), k, d[k], m.get(k)))
    return m


def build_docs(c, d, item, items):
    c.prune_idle_hits()
    sk = OrderedDict()
    sk["apiVersion"] = "astera/v2"
    sk["kind"] = "Skill"
    sk["id"] = d["id"]
    if item:
        sk["item"] = item
    meta = meta_block(d)
    if meta:
        sk["meta"] = meta
    if c.frames:
        sk["frames"] = list(c.frames.values())
    gp = OrderedDict()
    if c.params:
        gp["params"] = OrderedDict(c.params)
    exp = []
    for name, base in (("damage", "dmg_"), ("range", "reach"), ("range", "range"), ("flight_speed", "launch_speed"),
                       ("duration", "_ticks"), ("radius", "radius")):
        for pn in c.params:
            if (pn.startswith(base) and base != "_ticks") or (base == "_ticks" and d["archetype"] == "buff" and pn.endswith("_ticks")):
                if not any(e["id"] == name for e in exp):
                    exp.append({"id": name, "param": pn})
                break
    if exp:
        gp["expose"] = exp
    g_rows = rows_out(c.g_rows, "t")
    if g_rows:
        gp["timeline"] = g_rows
    if c.g_on:
        gp["on"] = OrderedDict(c.g_on.items())
    sk["gameplay"] = gp
    cm = []
    if len(items) > 1:
        cm.append("also bound to items: " + ", ".join(items[1:]))
    cm += gap_lines(c.todo_gaps, "TODO")
    sk["#"] = cm

    # presentation: repeating rows (trail emits) become `every` rows
    prows = {}
    reps = []
    for key, ops in c.p_rows.items():
        if key[0] == "rep":
            reps.extend(ops)
        else:
            prows[key] = ops
    pres = OrderedDict()
    pt = rows_out(prows, "fx")
    for i, op in enumerate(reps):
        pt.append({"id": "rep%d" % (i + 1), "at": 0, "every": 1, "until": "end", "do": [op]})
    for j, (at, every, span, op) in enumerate(c.p_reps):
        pt.append({"id": "loop%d" % (j + 1), "at": at, "every": every, "for": span, "do": [op]})
    if pt:
        pres["timeline"] = pt
    if c.p_on:
        pres["on"] = OrderedDict(c.p_on.items())
    look = None
    if pres or c.look_frames or c.present_gaps:
        look = OrderedDict()
        look["apiVersion"] = "astera/v2"
        look["kind"] = "Skill"
        look["id"] = d["id"]
        if c.present_gaps:
            look["#"] = gap_lines(c.present_gaps, "PRESENT")
        if c.look_frames:
            look["frames"] = list(c.look_frames.values())
        if pres:
            look["presentation"] = pres
    return sk, look


def header(d, src):
    return [f"Converted from {src} by tools/skill-convert/convert.py (v1 archetype: {d['archetype']}). Grep CONVERT-TODO / CONVERT-PRESENT before hand-editing.",
            "Generated point-frame offsets are [left, up, forward] in the parent frame."]


def item_map(repo):
    m = {}
    for p in sorted(os.listdir(os.path.join(repo, "content", "items"))):
        if not p.endswith(".yaml"):
            continue
        try:
            d = yaml.safe_load(open(os.path.join(repo, "content", "items", p)))
        except Exception:
            continue
        for s in (d or {}).get("skills", []) or []:
            m.setdefault(s["skill_id"], [])
            if d["id"] not in m[s["skill_id"]]:
                m[s["skill_id"]].append(d["id"])
    return m


def closure(repo, bound):
    seen = []
    queue = list(bound)
    while queue:
        i = queue.pop(0)
        if i in seen:
            continue
        seen.append(i)
        p = os.path.join(repo, "content", "skills", i + ".yaml")
        if not os.path.exists(p):
            continue
        d = yaml.safe_load(open(p))
        stack = [d]
        while stack:
            o = stack.pop()
            if isinstance(o, dict):
                if o.get("type") == "cast_skill":
                    queue.append(o["skill_id"])
                stack.extend(o.values())
            elif isinstance(o, list):
                stack.extend(o)
    return seen


def is_v2(path):
    """A hand-built v2 skill: v2 header and not written by this converter (its output starts with `# Converted from`)."""
    try:
        head = open(path).read(400)
    except OSError:
        return False
    return "apiVersion: astera/v2" in head and "Converted from" not in head


def op_inventory(node, acc=None):
    """{op name: [count, {field names seen}]} over every dict with an `op` key anywhere in a loaded YAML tree."""
    acc = {} if acc is None else acc
    if isinstance(node, dict):
        if isinstance(node.get("op"), str):
            e = acc.setdefault(node["op"], [0, set()])
            e[0] += 1
            e[1].update(k for k in node if k not in ("id", "op"))
        for v in node.values():
            op_inventory(v, acc)
    elif isinstance(node, list):
        for v in node:
            op_inventory(v, acc)
    return acc


def protected_diff(sid_, skill_dir, sk_text, look_text, conv):
    """What the v1 data of a hand-built (protected) skill would give that the v2 files lack. Reads only; writes nothing.

    Compares the converter's would-be output with every YAML file in the skill's v2 directory: op counts per kind, op
    fields the converted ops use that no v2 op of that kind uses, and the converter's own unconvertible gaps."""
    have = {}
    for fn in sorted(os.listdir(skill_dir)) if os.path.isdir(skill_dir) else []:
        if fn.endswith(".yaml"):
            for doc in yaml.safe_load_all(open(os.path.join(skill_dir, fn))):
                op_inventory(doc, have)
    want = {}
    for text in (sk_text, look_text or ""):
        for doc in yaml.safe_load_all(text):
            op_inventory(doc, want)
    lines = ["== %s (protected: not written) ==" % sid_]
    for name in sorted(set(want) | set(have)):
        w = want.get(name, [0, set()])
        h = have.get(name, [0, set()])
        note = []
        if w[0] > h[0]:
            note.append("v1 gives %d, v2 has %d" % (w[0], h[0]))
        missing = sorted(w[1] - h[1])
        if missing:
            note.append("fields only v1 data fills: " + ", ".join(missing))
        if note:
            lines.append("  op %-12s %s" % (name, "; ".join(note)))
    for kind, gaps in (("TODO", conv.todo_gaps), ("PRESENT", conv.present_gaps)):
        for name in gaps:
            lines.append("  %s %s" % (kind, name))
    return lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=REPO)
    ap.add_argument("--ids", default="")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--out", default=None, help="default <repo>/content/skills")
    ap.add_argument("--report", default=None, help="tsv report path (default <repo>/tools/skill-convert/report.tsv for --all)")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--vfx-dir", default=None, help="v1 vfx yaml dir (default <repo>/content/vfx; the old engine deleted it: "
                    "git archive 0c9e986ef^ content/vfx | tar -x -C <dir>)")
    a = ap.parse_args()
    repo = a.repo
    out = a.out or os.path.join(repo, "content", "skills")
    items = item_map(repo)
    bound = sorted(items.keys())
    todo_ids = closure(repo, bound) if a.all else [x for x in a.ids.split(",") if x]
    reached = set(closure(repo, bound))
    unbound = sorted(os.path.basename(p)[:-5] for p in os.listdir(os.path.join(repo, "content", "skills"))
                     if p.endswith(".yaml") and os.path.basename(p)[:-5] not in reached)
    rows = []
    diffs = []
    gap_skills = {}
    for i in todo_ids:
        src = os.path.join(repo, "content", "skills", i + ".yaml")
        if not os.path.exists(src):
            rows.append((i, "missing", 0, 0, 0, 0, 0))
            continue
        d = yaml.safe_load(open(src))
        its = items.get(i, [])
        try:
            c = convert_skill(d, a.vfx_dir or os.path.join(repo, "content", "vfx"), its[0] if its else None)
            sk, look = build_docs(c, d, its[0] if its else None, its)
        except Exception as e:  # converter gap: report, do not write
            rows.append((i, "error:%s:%s" % (type(e).__name__, e), 1, 0, 0, 0, 0))
            continue
        sk_text = dump_doc(sk, header(d, "content/skills/%s.yaml" % i))
        look_text = dump_doc(look, header(d, "content/skills/%s.yaml + content/vfx/*" % i)) if look else None
        text = sk_text + (look_text or "")
        nmeta = len(re.findall(r"# CONVERT-TODO meta", text))
        ntodo = len(re.findall(r"# CONVERT-TODO", text)) - nmeta
        npres = len(re.findall(r"# CONVERT-PRESENT", text))
        for kind, gaps in (("TODO", c.todo_gaps), ("PRESENT", c.present_gaps)):
            for name in gaps:
                gap_skills.setdefault((kind, name), []).append(i)
        protected = i in PROTECTED or is_v2(os.path.join(out, i, "skill.yaml"))
        status = "protected" if protected else "ok"
        if protected:
            diffs.extend(protected_diff(i, os.path.join(out, i), sk_text, look_text, c))
        if not protected and not a.dry_run:
            os.makedirs(os.path.join(out, i), exist_ok=True)
            open(os.path.join(out, i, "skill.yaml"), "w").write(sk_text)
            lp = os.path.join(out, i, "look.yaml")
            if look_text:
                open(lp, "w").write(look_text)
            elif os.path.exists(lp):
                os.remove(lp)
        rows.append((i, status, ntodo, npres, nmeta, len(sk_text.splitlines()), len(look_text.splitlines()) if look_text else 0))
    if a.report or a.all:
        rp = a.report or os.path.join(repo, "tools", "skill-convert", "report.tsv")
        os.makedirs(os.path.dirname(rp), exist_ok=True)
        with open(rp, "w") as f:
            f.write("id\tstatus\tconvert_todo\tconvert_present\ttodo_meta_header\tskill_lines\tlook_lines\n")
            for r in rows:
                f.write("\t".join(str(x) for x in r) + "\n")
        if a.all:
            with open(os.path.join(os.path.dirname(rp), "unbound-skills.txt"), "w") as f:
                f.write("# v1 skills not bound to any item and not reached by cast_skill: deletion candidates (not converted, not deleted)\n")
                f.write("\n".join(unbound) + "\n")
    if diffs and not a.dry_run:
        dp = os.path.join(os.path.dirname(a.report) if a.report else os.path.join(repo, "tools", "skill-convert"), "protected-diff.txt")
        os.makedirs(os.path.dirname(dp), exist_ok=True)
        with open(dp, "w") as f:
            f.write("# Hand-built v2 skills the converter will not overwrite: what their v1 data has that the v2 files lack (read-only diff).\n")
            f.write("\n".join(diffs) + "\n")
    conv = [r for r in rows if r[1] in ("ok", "protected")]
    clean = [r for r in conv if r[2] == 0 and r[3] == 0]
    print("skills: %d  clean (no TODO, no PRESENT; meta header excluded): %d  zero TODO: %d  protected: %d  errors: %d" % (
        len(rows), len(clean), len([r for r in conv if r[2] == 0]), len([r for r in rows if r[1] == "protected"]),
        len([r for r in rows if r[1].startswith("error")])))
    print("total CONVERT-TODO: %d  CONVERT-PRESENT: %d" % (sum(r[2] for r in conv), sum(r[3] for r in conv)))
    for (kind, name), ids in sorted(gap_skills.items(), key=lambda kv: (kv[0][0], -len(kv[1]))):
        print("%-8s %4d skills  %s" % (kind, len(ids), name))


if __name__ == "__main__":
    main()
