#!/usr/bin/env python3
"""Mechanical skill converter v1 -> v2 (SKL-09, ADR-0299, worker W19).

  python3 tools/skill-convert/convert.py --all                  # every item-bound skill (+ cast_skill children)
  python3 tools/skill-convert/convert.py --ids fireball-cast,club-bash
  python3 tools/skill-convert/convert.py --all --out /tmp/x --report /tmp/x/report.tsv

Reads  content/skills/<id>.yaml (v1), content/vfx/<id>.yaml, content/items/*.yaml (item binding).
Writes content/skills/<id>/skill.yaml  (kind: Skill: meta, frames, gameplay)
       content/skills/<id>/look.yaml   (kind: Skill, same id: frames + presentation; the composer deep-merges by id)
Never writes content/models, never overwrites a skill that is already v2 (couture-rapier-s2 coin 2 and its children).

Markers left in the output (grep them):
  # CONVERT-TODO <reason>     gameplay construct with no safe mechanical mapping (hand-fix needed)
  # CONVERT-PRESENT <what>    presentation detail that has no v2 op / needs authoring (flagged gap for the look pass)
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

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
PROTECTED = {"couture-rapier-s2", "couture-rapier-s2-shears", "couture-rapier-s2-blade-a", "couture-rapier-s2-blade-b"}

GAMEPLAY = {"damage", "apply_status", "knockback", "propel", "hit_stop", "cast_skill", "explode", "break_area",
            "place_shape", "heal", "cleanse", "consume_item", "summon_lightning", "leap", "modify_attribute",
            "dash_strike", "counter"}
PRESENT = {"play_sound", "spawn_particle", "schedule_particle_animation", "play_vfx", "camera_shake", "screen_flash",
           "screen_veil", "telegraph", "play_animation", "stop_sound", "hide_held_item"}
AREA = {"cone", "nearby_entities", "forward_ray", "enemies", "allies"}


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


def num(x):
    v = V(x)
    return v


def sid(s):
    return re.sub(r"[^A-Za-z0-9_]", "_", str(s)).strip("_") or "x"


def color_hex(c):
    if isinstance(c, dict):
        return "#%02x%02x%02x" % (int(c.get("r", 255)), int(c.get("g", 255)), int(c.get("b", 255)))
    return c


def fmt(v):
    return ("%g" % v) if isinstance(v, float) else str(v)


class Where:
    def __init__(self, kind, event=None, guard=None):
        self.kind = kind  # cast | handler
        self.event = event
        self.guard = guard


CAST = Where("cast")


class Conv:
    def __init__(self, d, vfx_dir):
        self.d = d
        self.vfx_dir = vfx_dir
        self.params = OrderedDict()
        self.expose = []
        self.frames = OrderedDict()  # gameplay-side frames (skill.yaml)
        self.look_frames = OrderedDict()
        self.g_rows = {}  # (event|None, tick) -> ops
        self.p_rows = {}
        self.g_on = OrderedDict()
        self.p_on = OrderedDict()
        self.p_repeat_rows = []
        self.id_counts = {}
        self.sel = {}
        self.todo = 0
        self.present = 0
        self.vfx_n = 0
        self.top_comments = []
        self.body_anim = None
        self.projectile_guard = None

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
        rows = self.g_rows if side == "g" else self.p_rows
        on = self.g_on if side == "g" else self.p_on
        if where.kind == "cast":
            rows.setdefault((None, delay), []).append(op)
            return
        if where.guard:
            cur = op.get("when")
            g = where.guard
            if cur:
                op["when"] = "= (" + g[2:] + ") && (" + cur[2:] + ")"
            else:
                op["when"] = g
        if delay == 0:
            on.setdefault(where.event, []).append(op)
        else:
            rows.setdefault((where.event, delay), []).append(op)

    def g(self, where, delay, op, victim_scope=False):
        if where.kind != "cast" and delay and victim_scope:
            op.setdefault("#", []).append(
                f"CONVERT-TODO delayed op (+{delay}) inside a {where.event} handler: victim/contact scope after the delay is not guaranteed")
            self.todo += 1
        self._put("g", where, delay, op)

    def p(self, where, delay, op):
        self._put("p", where, delay, op)

    def note_present(self, where, delay, text, op_comment_holder=None):
        self.present += 1
        self.p(where, delay, {"#": [f"CONVERT-PRESENT {text}"], "id": self.oid("present"), "op": "event", "name": "noop_present"})

    # ---------- targets ----------
    def point(self, tg, where):
        t = tg["type"]
        if t == "caster":
            return "caster"
        if t == "target":
            return "enemy"
        if t == "hit_location":
            return "contact.point"
        if t == "caster_forward":
            return self.pframe("caster", [0.0, V(tg.get("height", 0.0)), V(tg.get("distance", 0.0))], "g")
        if t == "caster_offset":
            return self.pframe("caster", vec3(tg["offset"]), "g")
        if t == "target_offset":
            return self.pframe("enemy", vec3(tg["offset"]), "g")
        if t == "projectile_position":
            return "bolt"
        return "enemy"

    def victim_target(self, tg):
        t = tg["type"] if tg else "caster"
        if t == "caster":
            return "caster"
        return "victim"

    def select(self, tg, delay, snapshot=False):
        """Area targeter in a cast context -> one `hit` op (instant) at row `delay`; returns the handler Where."""
        key = (json.dumps(tg, sort_keys=True), delay)
        if key in self.sel:
            return self.sel[key]
        t = tg["type"]
        hid = self.oid("sel")
        op = {"id": hid, "op": "hit", "frames": ["caster"]}
        if t == "cone":
            op["sector"] = {"frame": "caster", "forward": "caster.forward", "left": "caster.left",
                            "angle_deg": self.param("arc_deg", V(tg["angle_degrees"])),
                            "range": self.param("reach", V(tg["distance"])), "height": 2.0}
            if "max_count" in tg:
                op["max_count"] = V(tg["max_count"])
        elif t == "nearby_entities":
            op["sphere"] = {"at": "caster", "radius": self.param("radius", V(tg.get("radius", tg.get("distance", 3.0))))}
            if V(tg.get("exclude_caster", False)):
                op["exclude_caster"] = True
            if "max_count" in tg:
                op["max_count"] = V(tg["max_count"])
        elif t == "forward_ray":
            dist = V(tg["distance"])
            to = self.pframe("caster.eye", [0.0, 0.0, dist], "g")
            op["capsule"] = {"from": "caster.eye", "to": to, "radius": 0.3}
        elif t in ("enemies", "allies"):
            op["sphere"] = {"at": "caster", "radius": self.param("radius", V(tg.get("radius", tg.get("distance", 3.0))))}
            op["team"] = t
            if t == "allies" and V(tg.get("include_self", False)):
                op["include_self"] = True
        else:
            op["sphere"] = {"at": "caster", "radius": 3.0}
        op["window"] = "once"
        if snapshot:
            op["snapshot"] = True
        self.g(CAST, delay, op)
        w = Where("handler", "hit", f"= event.source == '{hid}'")
        self.sel[key] = w
        return w

    # ---------- conditions ----------
    def cond(self, m):
        c = m.get("condition")
        if not c:
            return None
        if c["type"] == "distance_less_than":
            def ref(x):
                return "caster" if x["type"] == "caster" else "victim"
            return "= dist(pos(%s), pos(%s)) < %s" % (ref(c["a"]), ref(c["b"]), fmt(V(c["distance"])))
        self.todo += 1
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
            w = self.select(tg, delay, V(m.get("snapshot_pose", False))) if where.kind == "cast" else where
            for sub in m.get("mechanics", []):
                self.mech(sub, w, 0)
            return
        if t == "dash_strike":
            self.dash(m, where, delay)
            return
        if t == "counter":
            self.counter(m, where, delay)
            return
        # area targets -> select
        if tg and tg["type"] in AREA and t not in ("camera_shake", "screen_flash", "screen_veil"):
            if where.kind == "cast":
                where = self.select(tg, delay, V(m.get("snapshot_pose", False)))
                delay = 0
                tg = {"type": "target"}
            else:
                c = self.cmark("CONVERT-TODO area targeter %s inside a handler, using the contact victim" % tg["type"])
                self.todo += 1
                m = dict(m)
                m["_c"] = c
                tg = {"type": "target"}
        when = self.cond(m)
        if t in GAMEPLAY:
            self.gameplay_op(m, t, tg, where, delay, when)
        elif t in PRESENT:
            self.presentation_op(m, t, tg, where, delay, when)
        else:
            self.todo += 1
            self.g(where, delay, {"#": [f"CONVERT-TODO unknown v1 mechanic {t}: {json.dumps(m, sort_keys=True)[:300]}"],
                                  "id": self.oid("todo"), "op": "event", "name": "noop_todo"})

    def cmark(self, s):
        return s

    def mk(self, op, tg, **kw):
        d = {"id": self.oid(kw.pop("idbase", op)), "op": op}
        return d

    def gameplay_op(self, m, t, tg, where, delay, when):
        vs = where.kind != "cast"
        if t == "damage":
            comps = []
            for c in m["components"]:
                attr = c["attribute"]
                comp = {"kind": attr, "amount": self.param("dmg_" + attr, V(c["base"]))}
                if "per_stack" in c:
                    comp["per_stack"] = V(c["per_stack"])
                comps.append(comp)
            op = {"id": self.oid("dmg"), "op": "damage", "target": self.victim_target(tg), "components": comps}
            if V(m.get("ignore_invulnerability", False)):
                op["ignore_iframes"] = True
        elif t == "apply_status":
            sname = m["status_id"]
            op = {"id": self.oid("st_" + sname), "op": "status", "target": self.victim_target(tg), "status": sname,
                  "duration_ticks": self.param(sid(sname) + "_ticks", V(m["effect_duration_ticks"]))}
            if "stack_count" in m:
                op["stacks"] = self.param(sid(sname) + "_stacks", V(m["stack_count"]))
            if "chance" in m:
                op["chance"] = V(m["chance"])
        elif t == "knockback":
            s = self.param("knockback", V(m["strength"]))
            d = m["direction"]["type"]
            vert = V(m["direction"].get("vertical", 0.0)) if isinstance(m["direction"], dict) else 0.0
            sign = "-" if d == "toward_caster" else ""
            base = "normalize(pos(victim) - pos(caster))"
            sv = s if isinstance(s, str) and not s.startswith("${") else None
            sname = s[2:-1] if isinstance(s, str) and s.startswith("${") else fmt(s)
            expr = "= %snormalize(pos(victim) - pos(caster)) * %s" % (sign, sname)
            if vert:
                expr += " + world.up * %s" % fmt(vert)
            op = {"id": self.oid("kb"), "op": "impulse", "target": self.victim_target(tg), "vector": expr}
        elif t == "propel":
            s = self.param("propel", V(m["strength"]))
            sname = s[2:-1] if isinstance(s, str) and s.startswith("${") else fmt(s)
            d = m["direction"]["type"]
            vert = V(m["direction"].get("vertical", 0.0)) if isinstance(m["direction"], dict) else 0.0
            if d == "up":
                expr = "= world.up * %s" % sname
            elif d == "away_from_caster":
                expr = "= forward(caster) * %s" % sname
            else:
                expr = "= forward(caster) * %s" % sname
            if vert:
                expr += " + world.up * %s" % fmt(vert)
            op = {"id": self.oid("propel"), "op": "impulse", "target": "caster", "vector": expr}
        elif t == "hit_stop":
            scope = ["enemy"] if where.kind != "cast" else ["enemy"]
            if V(m.get("include_caster", True)):
                scope.append("caster")
            op = {"id": self.oid("stop"), "op": "freeze", "ticks": V(m["ticks"]), "scope": scope}
            if V(m.get("freeze_animation", False)):
                op["freeze_animation"] = True
        elif t == "cast_skill":
            op = {"id": self.oid("cast"), "op": "cast_skill", "skill": m["skill_id"]}
        elif t == "explode":
            op = {"id": self.oid("explode"), "op": "explode", "at": self.point(tg, where) if tg["type"] != "target" else "enemy",
                  "power": self.param("explode_power", V(m["power"])), "break_blocks": bool(V(m.get("break_blocks", False))),
                  "radius": self.param("explode_radius", V(m["radius"])), "components": [
                      {"kind": c["attribute"], "amount": self.param("explode_" + c["attribute"], V(c["base"]))} for c in m.get("components", [])]}
        elif t == "break_area":
            op = {"id": self.oid("break"), "op": "break_area", "at": "caster"}
            for k in ("shape", "length", "width", "height", "radius", "tool_class", "block_damage"):
                if k in m:
                    op[k] = V(m[k])
        elif t == "place_shape":
            op = {"id": self.oid("place"), "op": "place_shape", "at": "caster"}
            for k in ("shape", "block_key", "width", "height", "length", "consume_from_inventory", "revert_after_ticks"):
                if k in m:
                    op["block" if k == "block_key" else k] = V(m[k])
        elif t == "heal":
            op = {"id": self.oid("heal"), "op": "heal", "target": self.victim_target(tg), "amount": self.param("heal", V(m["amount"]))}
            if tg and tg["type"] == "allies":
                op["target"] = {"kind": "allies", "around": "caster", "radius": V(tg.get("radius", 8.0)),
                                "include_self": bool(V(tg.get("include_self", True)))}
        elif t == "cleanse":
            op = {"id": self.oid("cleanse"), "op": "cleanse", "target": "caster", "negative_only": True}
            if tg and tg["type"] == "allies":
                op["target"] = {"kind": "allies", "around": "caster", "radius": V(tg.get("radius", 8.0)),
                                "include_self": bool(V(tg.get("include_self", True)))}
        elif t == "consume_item":
            op = {"id": self.oid("consume"), "op": "consume_item", "target": "caster", "item": m["item_id"], "count": 1}
        elif t == "summon_lightning":
            op = {"id": self.oid("lightning"), "op": "summon_lightning", "at": self.point(tg, where) if tg else "enemy",
                  "visual_only": bool(V(m.get("visual_only", False)))}
        elif t == "leap":
            op = {"id": self.oid("leap"), "op": "leap", "target": "caster", "destination": self.point(m["destination"], where),
                  "arc_height": V(m["arc_height"])}
        elif t == "modify_attribute":
            op = {"id": self.oid("attr"), "op": "modify_attribute", "target": self.victim_target(tg), "attribute": m["attribute_key"],
                  "operation": "add", "amount": V(m["delta"]), "duration_ticks": V(m["ticks"])}
        else:
            raise AssertionError(t)
        if when:
            op["when"] = when
        self.g(where, delay, op, victim_scope=vs and tg is not None and tg.get("type") == "target")

    def dash(self, m, where, delay):
        did = self.oid("dash")
        op = {"id": did, "op": "dash_strike", "distance": self.param("dash_distance", V(m["distance"])),
              "ticks": V(m["ticks"]), "radius": V(m["radius"]), "hit_once": bool(V(m.get("hit_once", True))),
              "invulnerable": bool(V(m.get("invulnerable", False))), "stop_on_block": bool(V(m.get("stop_on_block", True))),
              "follow_pitch": bool(V(m.get("follow_pitch", False)))}
        for k_v1, k_v2 in (("stop_before_target", "stop_before_target"), ("local_direction", "local_direction"),
                           ("face_movement", "face_movement")):
            if k_v1 in m:
                val = V(m[k_v1])
                op[k_v2] = vec3(val) if k_v1 == "local_direction" else val
        if self.cond(m):
            op["when"] = self.cond(m)
        self.g(where, delay, op)
        w = Where("handler", "hit", f"= event.source == '{did}'")
        self.mechanics(m.get("on_hit", []), w, 0)

    def counter(self, m, where, delay):
        cid = self.oid("counter")
        op = {"#": ["CONVERT-TODO counter (parry window) has no v2 op yet; on_hit moved to handler `counter` (guard op needed)"],
              "id": cid, "op": "guard", "ticks": V(m["window_ticks"]), "max_distance": V(m["max_distance"]), "emits": "counter"}
        self.todo += 1
        self.g(where, delay, op)
        self.mechanics(m.get("on_hit", []), Where("handler", "counter", None), 0)

    # ---------- presentation ----------
    def presentation_op(self, m, t, tg, where, delay, when):
        W = lambda op: self.p(where, delay, self._w(op, when))
        if t == "play_sound":
            at = self.point(tg, where)
            op = {"id": self.oid("snd"), "op": "sound", "sound": m["sound_id"], "at": at}
            if "volume" in m:
                op["volume"] = V(m["volume"])
            if "pitch" in m:
                op["pitch"] = V(m["pitch"])
            W(op)
        elif t == "spawn_particle":
            W({"id": self.oid("emit"), "op": "emit", "at": self.point(tg, where), "particle": m["particle_id"], "count": 1})
        elif t == "schedule_particle_animation":
            at = self.point(tg, where)
            for anim in m["animation"]:
                self.anim(anim, at, where, delay, when, m)
        elif t == "play_vfx":
            self.vfx(m, tg, where, delay, when)
        elif t == "camera_shake":
            op = {"id": self.oid("shake"), "op": "camera_shake", "target": self.screen_target(tg),
                  "intensity": V(m["intensity"]), "ticks": V(m["ticks"])}
            for k in ("shape", "direction", "viewers"):
                if k in m:
                    op[k] = V(m[k])
            W(op)
        elif t == "play_animation":
            anim = m["animation"]
            if tg and tg["type"] == "caster":
                W({"id": self.oid("anim"), "op": "caster_anim", "anim": anim, "ticks": V(m["ticks"])})
            else:
                W({"id": self.oid("anim"), "op": "victim_anim", "target": "victim", "anim": anim, "ticks": V(m["ticks"])})
        elif t in ("screen_flash", "screen_veil", "telegraph", "stop_sound", "hide_held_item"):
            self.present += 1
            frag = {k: V(v) for k, v in m.items() if k not in ("type", "targets")}
            frag["target"] = self.screen_target(tg) if tg else None
            self.p(where, delay, self._w({"#": [f"CONVERT-PRESENT {t} has no v2 op yet: {json.dumps(frag, sort_keys=True, default=str)[:400]}"],
                                          "id": self.oid(t), "op": "event", "name": "noop_present"}, when))

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
        if t == "target":
            return "victim"
        if t in AREA:
            return {"kind": "nearby", "around": "caster", "radius": V(tg.get("radius", tg.get("distance", 8.0))),
                    "exclude_caster": bool(V(tg.get("exclude_caster", False)))}
        return "victim"

    def anim(self, a, at, where, delay, when, m):
        t = a["type"]
        d2 = delay + int(V(a.get("delay_ticks", 0)) or 0)
        for part in a.get("particles", []) or []:
            op = {"id": self.oid("emit"), "op": "emit", "at": at, "particle": part["id"], "count": part.get("count", 1)}
            if "color" in part:
                op["color"] = color_hex(part["color"])
            if "scale" in part:
                op["scale"] = part["scale"]
            if part.get("extra"):
                op["speed"] = part["extra"]
            if "offset" in part:
                op["offset"] = vec3(part["offset"])
            if t == "ring":
                op.update({"shape": "ring", "points": a["points"], "radius": {"from": a["start_radius"], "to": a["end_radius"]},
                           "ticks": a["period_ticks"]})
                if "rise" in a:
                    op["rise"] = a["rise"]
            elif t == "circle":
                for k in ("angle", "amount", "period_ticks", "speed", "acceleration", "min_radius", "max_radius",
                          "repeat_radius_acceleration", "radius_acceleration"):
                    if k in a:
                        op[k if k != "period_ticks" else "ticks"] = a[k]
                op["shape"] = "circle"
            elif t == "line":
                op["shape"] = "line"
                for k in ("angle", "length", "segments", "jitter", "points_per_segment", "amount"):
                    if k in a:
                        op[k] = a[k]
            self.p(where, d2, self._w(op, when))

    # ---------- vfx inlining ----------
    def load_vfx(self, vid):
        p = os.path.join(self.vfx_dir, vid + ".yaml")
        if not os.path.exists(p):
            return None
        return yaml.safe_load(open(p))

    def vfx(self, m, tg, where, delay, when):
        vid = m["vfx_id"]
        d = self.load_vfx(vid)
        if d is None:
            self.todo += 1
            self.p(where, delay, {"#": [f"CONVERT-TODO missing vfx file {vid}"], "id": self.oid("vfx"), "op": "event", "name": "noop_todo"})
            return
        self.vfx_n += 1
        fid = "fx%d_%s" % (self.vfx_n, sid(vid))
        parent = self.point(tg, where) if tg else "caster"
        follow = bool(V(m.get("follow", d.get("follow", True))))
        frame = {"id": fid, "kind": "body", "parent": parent if follow else "world", "orient": d["orient"],
                 "vfx": vid, "follow": follow}
        if not follow:
            frame["anchor_at_spawn"] = parent
        if "scale" in m:
            frame["scale"] = V(m["scale"])
        notes = []
        for k in ("palette", "follow_teleport_ticks", "attach_to_anchor", "replace_group", "follow_pitch", "ghost_bodies",
                  "target_reference", "sample_ticks"):
            if k in d and k != "sample_ticks":
                notes.append(f"{k}={json.dumps(d[k], default=str)[:120]}")
        for k in ("freeze", "track_bound_target", "viewers", "snapshot_pose"):
            if k in m:
                notes.append(f"play_vfx.{k}={json.dumps(V(m[k]), default=str)}")
        if notes:
            frame["#"] = ["CONVERT-PRESENT vfx frame details: " + "; ".join(notes)]
            self.present += 1
        self.look_frames[fid] = frame
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

    def keys(self, kfs):
        out = []
        for k in kfs:
            e = {"t": k["t"]}
            if "translate" in k:
                e["offset"] = k["translate"]
            if "rotate" in k:
                e["rotation"] = k["rotate"]
            if "scale" in k:
                e["scale"] = k["scale"]
            for x in ("alpha", "color", "ease", "block", "text"):
                if x in k:
                    e[x] = k[x]
            out.append(e)
        return out

    def vfx_element(self, el, fid, where, delay, when, dur, removes):
        eid = "%s_%s" % (fid, sid(el["id"]))
        kind = el["kind"]
        kt = kind["type"]
        start = int(el.get("start_tick", 0))
        end = int(el.get("end_tick", dur))
        at = delay + start
        ops = []
        comments = []
        bb = el.get("billboard")
        face = {"viewer": "center"} if bb == "center" else {"viewer": "vertical"} if bb == "vertical" else {"plane": {"frame": fid, "axes": ["left", "up"]}}
        if kt == "block":
            ops.append({"id": eid, "op": "mesh", "frame": fid, "block": kind["block"]})
        elif kt == "quad":
            op = {"id": eid, "op": "plate", "parent": fid, "face": face, "color": kind["color"], "alpha": kind.get("alpha", 1.0)}
            if "glyph" in kind:
                op["glyph"] = kind["glyph"]
            ops.append(op)
        elif kt == "sprite":
            op = {"id": eid, "op": "plate", "parent": fid, "face": face, "sprite": kind["sprite"], "frame_ticks": kind.get("frame_ticks", 1),
                  "loop": kind.get("loop", False)}
            if "color" in kind:
                op["color"] = kind["color"]
            ops.append(op)
        elif kt == "item":
            ops.append({"id": eid, "op": "mesh", "frame": fid, "item": kind["item"], "item_model": kind.get("item_model"),
                        "display_mode": kind.get("display_mode")})
        elif kt == "particle":
            ops.append({"id": eid, "op": "emit", "at": fid, "particle": kind["particle_id"], "count": 1})
            if "along_path" in kind:
                comments.append(f"along_path={json.dumps(kind['along_path'], default=str)}")
        elif kt in ("thin_line", "dotted_line"):
            a = self.pframe_look(fid, kind["from"])
            b = self.pframe_look(fid, kind["to"])
            ops.append({"id": eid, "op": "line", "from": a, "to": b, "thickness": kind.get("width", kind.get("spacing", 0.05)),
                        "color": kind["color"], "face": {"viewer": "axis", "axis": "line.along"}})
        else:
            comments.append(f"element kind {kt} (text) has no v2 op: {json.dumps(kind, ensure_ascii=False)[:200]}")
        if el.get("brightness") is not None and ops:
            ops[0]["brightness"] = el["brightness"]
        for k in ("group_keyframes", "target_track", "formation", "count", "jitter", "oscillate", "lane", "emit",
                  "slot_lifetime_ticks", "glow_color", "target_line", "respect_hit_stop"):
            if k in el:
                comments.append(f"{k}={json.dumps(el[k], default=str, ensure_ascii=False)[:240]}")
        if comments:
            self.present += 1
            head = ops[0] if ops else {"id": eid, "op": "event", "name": "noop_present"}
            if not ops:
                ops.append(head)
            head.setdefault("#", []).append("CONVERT-PRESENT %s: %s" % (el["id"], "; ".join(comments)))
        kfs = el.get("keyframes", [])
        if ops and kt != "text" and kfs and (len(kfs) > 1 or any(x in kfs[0] for x in ("rotate", "scale", "alpha")) or "translate" in kfs[0]):
            tr = {"id": eid + "_trk", "op": "track", "target": eid, "space": fid, "keys": self.keys(kfs)}
            ops.append(tr)
        if kt == "particle":
            row = {"id": self.oid("fxrow"), "op": "event", "name": "x"}
            for op in ops:
                self.p(where, at, self._w(op, when))
            # repeating emission: marker on the emit op itself
            ops[0]["every"] = kind.get("every_ticks", 1)
            ops[0]["ticks"] = max(1, end - start)
        else:
            for op in ops:
                self.p(where, at, self._w(op, when))
            if kt != "text" and ops and ops[0]["op"] in ("mesh", "plate", "line"):
                removes.setdefault(end, []).append(eid)

    def pframe_look(self, parent, offset):
        return self.pframe(parent, offset, "p")

    def vfx_rig(self, r, fid, where, delay, when, dur, removes):
        rid = "%s_%s" % (fid, sid(r["id"]))
        parent = r.get("parent", "right_hand")
        pr = {"right_hand": "caster.right_hand", "left_hand": "caster.left_hand"}.get(parent, parent)
        frame = {"id": rid, "kind": "item" if r.get("weapon") else "body", "parent": pr}
        if r.get("weapon"):
            frame["weapon"] = r["weapon"]
        if "weapon_tip" in r:
            frame["points"] = {k: r[k] for k in ("weapon_tip", "weapon_root", "weapon_edge") if k in r}
        extras = [f"{k}={json.dumps(r[k], default=str)}" for k in ("pivot", "rest", "tilt", "respect_hit_stop", "weapon_model") if k in r]
        if extras:
            frame["#"] = ["CONVERT-PRESENT rig details: " + "; ".join(extras)]
            self.present += 1
        self.look_frames[rid] = frame
        start = delay + int(r.get("start_tick", 0))
        end = int(r.get("end_tick", dur))
        first = None
        if r.get("weapon"):
            first = rid + "_weapon"
            self.p(where, start, self._w({"id": first, "op": "mesh", "frame": rid, "item": r["weapon"]}, when))
        for part in r.get("parts", []):
            pid = "%s_%s" % (rid, sid(part["id"]))
            op = {"id": pid, "op": "mesh", "frame": rid, "block": part["block"], "offset": part["offset"], "size": part["size"]}
            if "rotate" in part:
                op["rotation"] = part["rotate"]
            if "brightness" in part:
                op["brightness"] = part["brightness"]
            first = first or pid
            self.p(where, start, self._w(op, when))
        if r.get("swing"):
            keys = []
            for k in r["swing"]:
                e = {"t": k["t"], "rotation": k["rotate"]}
                if "ease" in k:
                    e["ease"] = k["ease"]
                keys.append(e)
            self.p(where, start, self._w({"id": rid + "_swing", "op": "track", "target": first or rid, "space": pr, "keys": keys}, when))
        for tr in r.get("trails", []):
            a = self.pframe_look(rid, tr["from"])
            b = self.pframe_look(rid, tr["to"])
            op = {"id": "%s_%s" % (rid, sid(tr["id"])), "op": "trail", "of": b, "root": a, "ticks": tr["life_ticks"],
                  "style": tr.get("style"), "step_deg": tr.get("step_deg"), "bands": tr.get("bands")}
            for k in ("edge", "dark_rim", "fade_power", "lag_ticks", "double_sided"):
                if k in tr:
                    op[k] = tr[k]
            self.p(where, delay + int(tr["start_t"]), self._w(op, when))
            removes.setdefault(int(tr["end_t"]) + int(tr["life_ticks"]), []).append(op["id"])
        for em in r.get("emitters", []):
            pt = self.pframe_look(rid, em["point"])
            op = {"id": "%s_%s" % (rid, sid(em["id"])), "op": "emit", "at": pt, "particle": em["particle_id"], "count": 1,
                  "every": 1, "ticks": max(1, int(em["end_t"]) - int(em["start_t"]))}
            self.p(where, delay + int(em["start_t"]), self._w(op, when))
        removes.setdefault(end, []).append(rid)


# ---------------------------------------------------------------- skill-level driver

def convert_skill(d, vfx_dir, item):
    c = Conv(d, vfx_dir)
    arch = d["archetype"]
    params = d["params"]
    phases = d["phases"]

    # does a projectile skill have other hit sources? then guard projectile handlers by body id
    def has_area(lst):
        for m in lst or []:
            tg = m.get("targets")
            if m["type"] in ("dash_strike", "for_each_target") or (tg and tg["type"] in AREA):
                return True
        return False
    guard_proj = arch == "projectile" and has_area(phases.get("cast"))

    # windup / reload / block_damage
    if "windup" in d:
        c.todo += 1
        c.top_comments.append("CONVERT-TODO windup (rebase cast to tick %s, windup sound pitch arc): %s" % (
            V(d["windup"].get("ticks")), json.dumps(d["windup"], sort_keys=True, default=str)[:300]))
    if "reload_ticks" in d:
        c.g(CAST, 0, {"id": c.oid("reload"), "op": "reload", "ticks": d["reload_ticks"]})

    if arch == "melee":
        sid_ = "swing"
        c.id_counts["swing"] = 1
        c.g(CAST, 0, {"id": "swing", "op": "hit", "frames": ["caster"],
                      "sector": {"frame": "caster", "forward": "caster.forward", "left": "caster.left",
                                 "angle_deg": c.param("arc_deg", params["arc_degrees"]), "range": c.param("range", params["range"]), "height": 2.0},
                      "window": "once"})
        w = Where("handler", "hit", "= event.source == 'swing'")
        for comp in params.get("damage", []):
            c.g(w, 0, {"id": c.oid("dmg"), "op": "damage", "target": "victim",
                       "components": [{"kind": comp["attribute"], "amount": c.param("dmg_" + comp["attribute"], V(comp["base"]))}]})
        if params.get("knockback"):
            kb = c.param("knockback", params["knockback"])
            kn = kb[2:-1]
            c.g(w, 0, {"id": c.oid("kb"), "op": "impulse", "target": "victim", "vector": "= normalize(pos(victim) - pos(caster)) * " + kn})
        c.mechanics(phases.get("cast"), CAST)
        c.mechanics(phases.get("hit_entity"), w)
        if phases.get("miss"):
            c.todo += 1
            c.g(Where("handler", "end", "= hits == 0"), 0, {"#": ["CONVERT-TODO melee miss phase has content: needs hit counting; ops converted below"],
                                                            "id": c.oid("todo"), "op": "event", "name": "noop_todo"})
            c.mechanics(phases["miss"], Where("handler", "end", "= hits == 0"))
    elif arch == "aoe":
        c.id_counts["burst"] = 1
        c.g(CAST, 0, {"id": "burst", "op": "hit", "frames": ["caster"], "sphere": {"at": "caster", "radius": c.param("radius", params["radius"])},
                      "window": "once"})
        w = Where("handler", "hit", "= event.source == 'burst'")
        for comp in params.get("damage", []):
            amt = c.param("dmg_" + comp["attribute"], V(comp["base"]))
            if params.get("falloff") == "linear":
                nm = amt[2:-1]
                amt = "= %s * (1.0 - 0.5 * dist(pos(caster), pos(victim)) / radius)" % nm
            c.g(w, 0, {"id": c.oid("dmg"), "op": "damage", "target": "victim", "components": [{"kind": comp["attribute"], "amount": amt}]})
        if "block_damage" in d:
            bd = d["block_damage"]
            c.g(CAST, 0, {"id": c.oid("chip"), "op": "block_chip", "amount": V(bd["amount"]), "radius": V(bd.get("radius", 2.5))})
        c.mechanics(phases.get("cast"), CAST)
        c.mechanics(phases.get("hit_entity"), w)
        c.mechanics(phases.get("expire"), Where("handler", "end", None))
    elif arch == "projectile":
        bolt = {"id": "bolt", "op": "projectile", "body": "bolt", "from": None}
        c.id_counts["bolt"] = 1
        up = V(params.get("muzzle_up", 0.0))
        fw = V(params.get("muzzle_forward", 0.5))
        muzzle = c.pframe("caster.eye", [0.0, up, fw], "g")
        speed = c.param("launch_speed", params["launch_speed"])
        life = c.param("max_lifetime_ticks", params["max_lifetime_ticks"])
        aim = c.param("aim_distance", V(params.get("aim_distance", 50.0)))
        bolt.update({"from": muzzle, "velocity": "= normalize(aim_point(caster, %s) - pos(%s)) * %s" % (aim[2:-1], muzzle, speed[2:-1]),
                     "gravity": 0.0, "drag": 0.0, "max_ticks": life,
                     "volume": {"sphere": {"at": "bolt", "radius": V(params.get("hit_radius", 0.3))}}, "pierce": 0})
        c.g(CAST, 0, bolt)
        wh = Where("handler", "hit", "= event.source == 'bolt'" if guard_proj else None)
        wb = Where("handler", "block", "= event.source == 'bolt'" if guard_proj else None)
        we = Where("handler", "expire", "= event.source == 'bolt'" if guard_proj else None)
        if "block_damage" in d:
            c.g(wb, 0, {"id": c.oid("chip"), "op": "block_chip", "amount": V(d["block_damage"]["amount"])})
        vis = params.get("visual") or {}
        if vis.get("trail"):
            for tr in vis["trail"]:
                for part in tr.get("particles", []):
                    op = {"id": c.oid("trail"), "op": "emit", "at": "bolt", "particle": part["id"], "count": part.get("count", 1),
                          "every": 1, "ticks": "= max_ticks", "spacing": vis.get("trail_spacing")}
                    if tr["type"] == "line":
                        op["shape"] = "line"
                    if "color" in part:
                        op["color"] = color_hex(part["color"])
                    if "scale" in part:
                        op["scale"] = part["scale"]
                    if part.get("offset"):
                        op["offset"] = vec3(part["offset"])
                    c.p(CAST, 0, op)
        if vis.get("display"):
            disp = vis["display"]
            c.p(CAST, 0, {"id": c.oid("mesh"), "op": "mesh", "frame": "bolt", "item": disp.get("material"), "scale": disp.get("scale"),
                          "variant": disp.get("variant")})
        if vis.get("vfx"):
            c.vfx({"vfx_id": vis["vfx"]}, {"type": "projectile_position"}, CAST, 0, None)
        c.mechanics(phases.get("cast"), CAST)
        c.mechanics(phases.get("hit_entity"), wh)
        c.mechanics(phases.get("hit_block"), wb)
        c.mechanics(phases.get("expire"), we)
        c.mechanics(phases.get("miss"), we)
    elif arch == "buff":
        dur = c.param(sid(params["status_effect_id"]) + "_ticks", params["duration_ticks"])
        c.g(CAST, 0, {"id": c.oid("buff"), "op": "status", "target": "caster", "status": params["status_effect_id"], "duration_ticks": dur})
        c.mechanics(phases.get("cast"), CAST)
        if phases.get("expire"):
            c.g(CAST, 0, {"id": c.oid("sch"), "op": "schedule", "after": dur, "event": "buff_end"})
            c.mechanics(phases["expire"], Where("handler", "buff_end", None))
    else:  # sequence
        if "block_damage" in d:
            c.g(CAST, 0, {"id": c.oid("chip"), "op": "block_chip", "amount": V(d["block_damage"]["amount"])})
        c.mechanics(phases.get("cast"), CAST)

    return c


def rows_out(rows, prefix):
    out = []
    for (ev, tick), ops in sorted(rows.items(), key=lambda kv: (kv[0][0] or "", kv[0][1])):
        at = tick if ev is None else f"{ev}+{tick}"
        rid = "%s%s" % (prefix, str(at).replace("+", "_"))
        out.append({"id": rid, "at": at, "do": ops})
    # cast rows first
    out.sort(key=lambda r: (not isinstance(r["at"], int), r["at"] if isinstance(r["at"], int) else 0))
    return out


def collapse_repeat(rows):
    """Particle emits with `every` become their own repeating rows."""
    out = {}
    for key, ops in rows.items():
        plain = []
        for op in ops:
            if "every" in op and op.get("op") == "emit":
                every = op.pop("every")
                ticks = op.pop("ticks")
                out.setdefault(("rep", key, len(out)), [every, ticks, key, op])
            else:
                plain.append(op)
        if plain:
            out[key] = plain
    return out


def build_docs(c, d, item, items):
    meta = OrderedDict()
    meta["name"] = d["name"]
    meta["description"] = d["description"]
    meta["cooldown_ticks"] = d["cooldown_ticks"]
    if "resource_cost" in d:
        meta["resource_cost"] = d["resource_cost"]
    if "ego_cost" in d:
        meta["ego_cost"] = d["ego_cost"]
    tags = [t for t in d.get("tags", []) if t not in ("demo", "vfx", "rtm", "animation")]
    if tags:
        meta["tags"] = tags
    if "block_damage" in d:
        meta["block_damage"] = d["block_damage"]
    if "windup" in d:
        meta["windup"] = d["windup"]
    if "reload_ticks" in d:
        meta["reload_ticks"] = d["reload_ticks"]

    sk = OrderedDict()
    sk["apiVersion"] = "astera/v2"
    sk["kind"] = "Skill"
    sk["id"] = d["id"]
    if item:
        sk["item"] = item
        if len(items) > 1:
            sk["#"] = ["also bound to items: " + ", ".join(items[1:])]
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
        gp["on"] = OrderedDict((k, v) for k, v in c.g_on.items())
    sk["gameplay"] = gp
    if c.top_comments:
        sk["#"] = sk.get("#", []) + c.top_comments

    # presentation
    prows = {}
    reps = []
    for key, ops in c.p_rows.items():
        plain = []
        for op in ops:
            if op.get("op") == "emit" and "every" in op:
                reps.append((key, op))
            else:
                plain.append(op)
        if plain:
            prows[key] = plain
    pres = OrderedDict()
    pt = rows_out(prows, "fx")
    for i, (key, op) in enumerate(reps):
        every = op.pop("every")
        ticks = op.pop("ticks")
        ev, tick = key
        at = tick if ev is None else f"{ev}+{tick}"
        row = {"id": "rep%d" % (i + 1), "at": at, "every": every}
        if isinstance(ticks, int):
            row["ticks"] = ticks
        else:
            row["until"] = "end"
        row["do"] = [op]
        pt.append(row)
    if pt:
        pres["timeline"] = pt
    if c.p_on:
        pres["on"] = OrderedDict((k, v) for k, v in c.p_on.items())
    look = None
    if pres or c.look_frames:
        look = OrderedDict()
        look["apiVersion"] = "astera/v2"
        look["kind"] = "Skill"
        look["id"] = d["id"]
        if c.look_frames:
            look["frames"] = list(c.look_frames.values())
        look["presentation"] = pres
    return sk, look


def header(d, src):
    return [f"Converted from {src} by tools/skill-convert/convert.py (v1 archetype: {d['archetype']}). Grep the converter markers (TODO / PRESENT prefixed with CONVERT-) before hand-editing.",
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
    try:
        return "apiVersion: astera/v2" in open(path).read(400)
    except OSError:
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=REPO)
    ap.add_argument("--ids", default="")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--out", default=None, help="default <repo>/content/skills")
    ap.add_argument("--report", default=None, help="tsv report path (default <out>/../../tools/skill-convert/report.tsv for --all)")
    ap.add_argument("--dry-run", action="store_true")
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
    for i in todo_ids:
        src = os.path.join(repo, "content", "skills", i + ".yaml")
        if not os.path.exists(src):
            rows.append((i, "missing", 0, 0, 0, 0))
            continue
        d = yaml.safe_load(open(src))
        its = items.get(i, [])
        try:
            c = convert_skill(d, os.path.join(repo, "content", "vfx"), its[0] if its else None)
            sk, look = build_docs(c, d, its[0] if its else None, its)
        except Exception as e:  # converter gap: report, do not write
            rows.append((i, "error:%s:%s" % (type(e).__name__, e), 1, 0, 0, 0))
            continue
        sk_text = dump_doc(sk, header(d, "content/skills/%s.yaml" % i))
        look_text = dump_doc(look, header(d, "content/skills/%s.yaml + content/vfx/*" % i)) if look else None
        text = sk_text + (look_text or "")
        ntodo = len(re.findall(r"# CONVERT-TODO", text))
        npres = len(re.findall(r"# CONVERT-PRESENT", text))
        protected = i in PROTECTED or is_v2(os.path.join(out, i, "skill.yaml"))
        status = "protected" if protected else "ok"
        if not protected and not a.dry_run:
            os.makedirs(os.path.join(out, i), exist_ok=True)
            open(os.path.join(out, i, "skill.yaml"), "w").write(sk_text)
            lp = os.path.join(out, i, "look.yaml")
            if look_text:
                open(lp, "w").write(look_text)
            elif os.path.exists(lp):
                os.remove(lp)
        rows.append((i, status, ntodo, npres, len(sk_text.splitlines()), len(look_text.splitlines()) if look_text else 0))
    if a.report or a.all:
        rp = a.report or os.path.join(repo, "tools", "skill-convert", "report.tsv")
        os.makedirs(os.path.dirname(rp), exist_ok=True)
        with open(rp, "w") as f:
            f.write("id\tstatus\tconvert_todo\tconvert_present\tskill_lines\tlook_lines\n")
            for r in rows:
                f.write("\t".join(str(x) for x in r) + "\n")
        if a.all:
            with open(os.path.join(os.path.dirname(rp), "unbound-skills.txt"), "w") as f:
                f.write("# v1 skills not bound to any item and not reached by cast_skill: deletion candidates (not converted, not deleted)\n")
                f.write("\n".join(unbound) + "\n")
    zero = [r for r in rows if r[1] != "missing" and r[2] == 0]
    zero_all = [r for r in zero if r[3] == 0]
    print("skills: %d  zero CONVERT-TODO: %d  zero TODO and zero PRESENT: %d  protected: %d" % (
        len(rows), len(zero), len(zero_all), len([r for r in rows if r[1] == "protected"])))


if __name__ == "__main__":
    main()
