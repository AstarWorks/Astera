#!/usr/bin/env python3
"""skill-lint: preflight for a VFX skill, run BEFORE every capture. Seven invariants, nothing else.

    python3 tools/skill-lint/skill_lint.py <skill-id> [--plan plan.json] [--case <case>]
    make skill-lint SKILL=bastion-greatsword-s1 [PLAN=<plan.json>] [CASE=<case>]

Checks (each prints PASS / FAIL / NOT CHECKED; exit 1 on any FAIL):
  1 held weapon    visibleWeapons == the number of HELD weapons in the inventory (tools/skill-compare/data/<case>/inventory.md,
                   rows whose 付く先 column says the object is held in a hand; 1 when the case has no inventory yet) on every
                   tick of the plan dump. A thrown or split blade is an inventory object (projectile / ItemDisplay), not a
                   second held weapon (workflow v4, retro-2026-10-27-fable-characteristics.md).
  2 two-hand grip  the right hand bone is on the weapon grip on every tick. The left hand is required on the grip only for a
                   heavy (two-handed) weapon, decided from the item data that binds this skill (content/items/*.yaml,
                   attack_speed <= HEAVY_ATTACK_SPEED); a one-handed weapon's left hand is free. When the plan dump has no
                   left_arm bone or no item binds the skill, the left-hand line prints NOT CHECKED. It never passes silently.
  3 feet on floor  the lowest corner of the leg boxes is on the floor (y = 0 in the caster frame) within a tolerance
                   on every tick where the caster stands on the ground (airborne ticks, from the dump's `caster` track,
                   are listed as skipped, not passed)
  4 display budget simultaneous Display entities and particles spawned per tick stay under the budget
  5 hit events     gameplay events are event-driven, never tick-timed fakes (couture-rapier-s2, founder: 「テストのための実装感が
                   ぬぐえない」): (a) a thrown / flying weapon is a `projectile` (visual.vfx + hit_entity / miss), not an
                   item element riding caster-local keyframes; (b) a stuck / linked / embedded object is never placed at a
                   caster-local offset in a `follow: true` vfx; (c) a hit-flavoured vfx (id or elements say hit / impact /
                   contact / burst / stick) aimed with an entity-seeking targeter (cone, nearby, nearest) is bound to the
                   event (`on_hit:` of dash_strike / counter, `hit_entity:` of a projectile) or at least co-timed with the
                   `damage` that uses the same targeter at the same `delay_ticks`; a tick offset that only works because the
                   capture dummy stands at DISTANCE fails
  6 inventory      every 3D-object row of the inventory names a model (`astera:<id>` or `<id>` in its 構成案 column) that exists
    models         under content/models/items/, and every `item_model: astera:<id>` the skill shows (cast vfx, event-phase vfx,
                   projectile visual.vfx, cast_skill sub-skills) is an inventory row. A model the modeler built and the skill
                   never shows, or a model the skill shows that the inventory never listed, fails. NOT CHECKED without an
                   inventory (never silent).
  7 distance bake  no keyframe / offset in the skill or its vfx carries the capture DISTANCE (the enemy x of the case intent)
                   as a literal: an object that goes "to where the dummy stood" is a camera-tuned fake. NOT CHECKED without
                   a case intent.

There are no per-skill targets. Facts about the REFERENCE (blade angle, torso lean, ...) are judged on skill-compare,
not here. Plan dump (written by the runtime, build/skill-lint/<skill>/plan.json): docs/contributing/vfx-team/skill-lint.md.
"""
import argparse
import datetime
import json
import math
import re
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]

# Thresholds (blocks / counts). Not per-skill: change them here, with a reason, for everybody.
GRIP_TOL = 0.30          # right hand bone to weapon grip origin
LEFT_GRIP_TOL = 0.35     # left hand end to weapon grip origin (the left arm is one rigid bone aimed at the grip, arm's length)
FLOOR_TOL = 0.12         # lowest leg corner above / below y = 0
AIRBORNE_Y = 0.15        # caster feet this far above where they stood at the cast = airborne, floor check skipped
BUDGET_DISPLAYS = 60     # director.md "Display budget"
BUDGET_PARTICLES = 300
# Check 5 vocabulary (element / vfx ids are the author's own words; the lint reads them, so name things honestly).
HIT_WORDS = ("hit", "impact", "contact", "burst", "explode", "explosion", "stick", "stuck", "linked", "embed", "impale", "pinned")
STUCK_WORDS = ("stuck", "stick", "linked", "embed", "impale", "pinned", "lodged")
THROWN_WORDS = ("fly", "flying", "thrown", "throw", "shot", "bolt", "launch", "projectile")
ENTITY_SEEKING = ("cone", "nearby_entities", "nearest", "nearest_entity", "sphere", "box", "line", "entities_in")
EVENT_PHASES = ("on_hit", "hit_entity", "hit_block", "miss", "expire", "on_parry", "on_counter", "on_land")
FAKE_FLIGHT_BLOCKS = 2.0   # an item / model element travelling this far along caster-local keyframes is a fake projectile
# Check 2: a weapon is two-handed when its item data says it is heavy (content/items/<id>.yaml attributes.attack_speed).
# Greatswords / hammers / axes / staffs sit at 0.8-1.0; rapiers, daggers, katanas at 1.6-3.0. Items carry no grip field.
HEAVY_ATTACK_SPEED = 1.0
# Inventory (workflow v4): tools/skill-compare/data/<case>/inventory.md, table under `## 物体台帳` with the columns
# `# | 物体 | 数 | 色 | 形 | 動き | 誰に/どこに付く | 3D物体/面/線/粒子 | 構成案`. Words that mark a row as a held weapon / a 3D object.
HELD_WORDS = ("手持ち", "握", "手に", "両手", "右手", "左手", "held", "hand", "grip")
OBJECT_3D_WORDS = ("3d", "物体", "model", "itemdisplay")
MODEL_ID_RE = re.compile(r"astera:([a-z0-9][a-z0-9-]*)|`([a-z0-9][a-z0-9-]*)`")
# Check 7: a translate / offset component this close to the capture DISTANCE is the dummy's position baked into content.
DISTANCE_TOL = 0.2
DISTANCE_MIN = 1.5         # below this the enemy stands in melee reach and any offset is ambiguous
POSITION_KEYS = ("translate", "offset", "position", "pos", "anchor_offset")
HORIZONTAL = (0, 2)        # x and z of a caster-local vector; y is height, never the distance
TARGET_ANCHORED = ("target", "hit_location", "projectile_position", "hit_entity", "nearest", "nearby", "cone")

# Rig defaults when the dump predates `bones.*.scale` (box size in blocks; a dumped `scale` is twice that, see dump_box_size): 4 x 12 x 4 px boxes at PLAYER_SCALE.
PLAYER_SCALE = 0.9375
DEFAULT_LEG_SIZE = (4 / 16 * PLAYER_SCALE, 12 / 16 * PLAYER_SCALE, 4 / 16 * PLAYER_SCALE)
DEFAULT_ARM_SIZE = (4 / 16 * PLAYER_SCALE, 12 / 16 * PLAYER_SCALE, 4 / 16 * PLAYER_SCALE)

RESULTS = []  # (check, status, text) with status PASS | FAIL | NOT CHECKED | SKIP
DETAILS = []


def emit(check, status, text, **detail):
    assert status in ("PASS", "FAIL", "NOT CHECKED", "SKIP")
    RESULTS.append((check, status, text))
    DETAILS.append(detail)
    print("[%s] %s  %s" % (status if status != "NOT CHECKED" else "NOT CHECKED", check, text))


# ---- helpers ------------------------------------------------------------------------------------------------------
def lit(v, default=0):
    if v is None:
        return default
    return v.get("value", default) if isinstance(v, dict) else v


def load_yaml(rel):
    return yaml.safe_load((ROOT / rel).read_text())


def qrot(q, v):
    """q = [qx, qy, qz, qw] rotating a local vector into caster-local space."""
    x, y, z, w = q
    tx, ty, tz = 2 * (y * v[2] - z * v[1]), 2 * (z * v[0] - x * v[2]), 2 * (x * v[1] - y * v[0])
    return (v[0] + w * tx + (y * tz - z * ty), v[1] + w * ty + (z * tx - x * tz), v[2] + w * tz + (x * ty - y * tx))


def add(a, b):
    return tuple(x + y for x, y in zip(a, b))


class Skill:
    def __init__(self, skill_id):
        self.id = skill_id
        self.doc = load_yaml("content/skills/%s.yaml" % skill_id)
        self.steps = (self.doc.get("phases") or {}).get("cast") or []
        self.vfx = {}  # cast-time vfx_id -> (delay, doc)
        for s in self.steps:
            if s.get("type") == "play_vfx":
                self.vfx[s["vfx_id"]] = (int(lit(s.get("delay_ticks"))), load_yaml("content/vfx/%s.yaml" % s["vfx_id"]))
        # Everything the skill can show: every vfx id anywhere in this skill (cast, on_hit, projectile phases, projectile
        # visual.vfx) and in the sub-skills it casts (cast_skill skill_id), recursively.
        self.all_docs = {}  # "skill:<id>" / "vfx:<id>" -> doc
        self.vfx_targets = {}  # vfx id -> set of targeter types it is played with ("" = caster / unset)
        self._collect(skill_id, set())

    def _collect(self, skill_id, seen):
        if skill_id in seen:
            return
        seen.add(skill_id)
        path = ROOT / "content/skills" / (skill_id + ".yaml")
        if not path.is_file():
            return
        doc = load_yaml("content/skills/%s.yaml" % skill_id)
        self.all_docs["skill:" + skill_id] = doc
        vfx_ids, sub_skills = set(), set()

        def walk(node, key=None):
            if isinstance(node, dict):
                if node.get("type") == "play_vfx" and isinstance(node.get("vfx_id"), str):
                    vfx_ids.add(node["vfx_id"])
                    tgt = node.get("targets") if isinstance(node.get("targets"), dict) else {}
                    self.vfx_targets.setdefault(node["vfx_id"], set()).add(str(tgt.get("type") or ""))
                if key == "visual" and isinstance(node.get("vfx"), str):
                    self.vfx_targets.setdefault(node["vfx"], set()).add("projectile_position")
                if node.get("type") == "cast_skill" and isinstance(node.get("skill_id"), str):
                    sub_skills.add(node["skill_id"])
                if key == "visual" and isinstance(node.get("vfx"), str):
                    vfx_ids.add(node["vfx"])
                for k, v in node.items():
                    walk(v, k)
            elif isinstance(node, list):
                for v in node:
                    walk(v, key)
        walk(doc)
        for vid in vfx_ids:
            vp = ROOT / "content/vfx" / (vid + ".yaml")
            if vp.is_file() and ("vfx:" + vid) not in self.all_docs:
                self.all_docs["vfx:" + vid] = load_yaml("content/vfx/%s.yaml" % vid)
        for sid in sub_skills:
            self._collect(sid, seen)

    def item_models(self):
        """{model id -> [where]} for every `item_model: astera:<id>` the skill can show."""
        found = {}

        def walk(node, where):
            if isinstance(node, dict):
                im = node.get("item_model")
                if isinstance(im, str):
                    m = re.fullmatch(r"(?:astera:)?([a-z0-9][a-z0-9-]*)", im.strip())
                    if m:
                        found.setdefault(m.group(1), []).append(where)
                for v in node.values():
                    walk(v, where)
            elif isinstance(node, list):
                for v in node:
                    walk(v, where)
        for where, doc in self.all_docs.items():
            walk(doc, where)
        return found

    def item(self):
        """The item (content/items/*.yaml) whose skills[] binds this skill id, else the item whose id is the longest prefix."""
        best, best_len = None, -1
        for f in sorted((ROOT / "content/items").glob("*.yaml")):
            doc = yaml.safe_load(f.read_text()) or {}
            iid = str(doc.get("id") or f.stem)
            for b in doc.get("skills") or []:
                if isinstance(b, dict) and b.get("skill_id") == self.id:
                    return doc
            if self.id.startswith(iid + "-") and len(iid) > best_len:
                best, best_len = doc, len(iid)
        return best


# ---- inventory (workflow v4) -------------------------------------------------------------------------------------
def load_inventory(case):
    """Rows of the 物体台帳 table of tools/skill-compare/data/<case>/inventory.md, or None when the file does not exist.
    Each row: {"n", "object", "count", "colour", "shape", "motion", "anchor", "medium", "plan", "raw"}."""
    path = ROOT / "tools/skill-compare/data" / case / "inventory.md"
    if not path.is_file():
        return None
    rows, header_seen = [], False
    for line in path.read_text().splitlines():
        if not line.strip().startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if not header_seen:
            if len(cells) >= 8 and "物体" in cells[1] and "数" in cells[2]:
                header_seen = True
            continue
        if all(set(c) <= set("-: ") for c in cells):
            continue
        cells += [""] * (9 - len(cells))
        rows.append({"n": cells[0], "object": cells[1], "count": cells[2], "colour": cells[3], "shape": cells[4], "motion": cells[5],
                     "anchor": cells[6], "medium": cells[7], "plan": cells[8], "raw": line.strip()})
    return rows


def row_count(row):
    """Integer count of an inventory row, or None when the author wrote a range / question (「2 か 3」)."""
    m = re.fullmatch(r"\s*(\d+)\s*(?:\[[OIU]\])?\s*", row["count"])
    return int(m.group(1)) if m else None


def is_held(row):
    return _has_word(row["anchor"], HELD_WORDS)


def is_3d(row):
    return _has_word(row["medium"], OBJECT_3D_WORDS)


def row_models(row):
    return [a or b for a, b in MODEL_ID_RE.findall(row["plan"])]


def model_exists(model_id):
    base = ROOT / "content/models/items"
    return (base / (model_id + ".model.json")).is_file() or any(base.rglob(model_id + ".model.json"))


def case_distance(case):
    """Enemy x of the case intent (the DISTANCE the capture dummy stands at), or None."""
    cdir = ROOT / "tools/skill-compare/data" / case
    cj = cdir / "case.json"
    if not cj.is_file():
        return None
    try:
        cdoc = json.loads(cj.read_text())
        intent_file = ((cdoc.get("intent") or {}).get("file")) or "intent.json"
        idoc = json.loads((cdir / intent_file).read_text())
    except (OSError, ValueError):
        return None
    for actor in idoc.get("actors") or []:
        if actor.get("id") == "enemy":
            keys = actor.get("keys") or []
            if keys and isinstance(keys[0].get("pos"), list) and keys[0]["pos"]:
                return float(keys[0]["pos"][0])
    return None


def element_span(el, duration):
    s = int(el.get("start_tick", 0))
    e = el.get("end_tick")
    return s, int(e) if e is not None else int(duration)


def emission_ticks(el, duration):
    """Element ticks at which this element spawns particles, or None when it is a plain display."""
    kind = el.get("kind", {})
    s, e = element_span(el, duration)
    if kind.get("type") == "particle":
        every = max(1, int(kind.get("every_ticks", 1)))
        return list(range(s, e, every))  # end exclusive, as VfxTimeline
    if "emit" in el:
        em = el["emit"]
        n = max(1, math.ceil(float(em.get("count", 1)) / max(float(em.get("rate_per_tick", 1)), 1e-6)))
        return list(range(s, s + n))
    return None


_pcount = {}


def load_particle_count(pid):
    if pid not in _pcount:
        f = ROOT / "content/particles" / (str(pid) + ".yaml")
        _pcount[pid] = yaml.safe_load(f.read_text()).get("count", 1) if f.is_file() else 1
    return _pcount[pid]


def load_plan(path):
    if not path.is_file():
        return None
    return json.loads(path.read_text())


# ---- check 1 ------------------------------------------------------------------------------------------------------
def held_weapon_count(inventory):
    """(expected visibleWeapons, note). The inventory's held rows decide; 1 when there is no inventory; None when a held
    row's count is unresolved (the author must ask the founder, not guess)."""
    if inventory is None:
        return 1, "no inventory.md for the case: one held weapon assumed"
    held = [r for r in inventory if is_held(r)]
    if not held:
        return 1, "inventory lists no held row: one held weapon assumed"
    total = 0
    for r in held:
        n = row_count(r)
        if n is None:
            return None, "inventory row %s (%s) has an unresolved count %r: ask the founder, do not build on a guess" % (r["n"], r["object"], r["count"])
        total += n
    return total, "inventory held rows %s -> %d held weapon(s)" % (", ".join(r["n"] for r in held), total)


def check_held_weapon(plan, inventory):
    c = "1 held weapon"
    expected, note = held_weapon_count(inventory)
    if expected is None:
        emit(c, "FAIL", note)
        return
    if plan is None:
        emit(c, "FAIL", "no plan dump: cannot count visible weapons (run make skill-lint-plan); %s" % note)
        return
    bad = [(t["tick"], t.get("visibleWeapons")) for t in plan["ticks"] if t.get("visibleWeapons") != expected]
    n = len(plan["ticks"])
    if plan.get("simulated"):
        emit(c, "NOT CHECKED", "the plan dump is a Python simulation (no runtime lease model): visibleWeapons is not measured")
    elif bad:
        emit(c, "FAIL", "visibleWeapons != %d on %d/%d ticks, first t%d = %s (%s)" % (expected, len(bad), n, bad[0][0], bad[0][1], note), ticks=[b[0] for b in bad])
    else:
        emit(c, "PASS", "visibleWeapons == %d on all %d ticks (%s)" % (expected, n, note))


def dump_box_size(bone, default):
    """Box size in blocks. The dump's `scale` is the Display scale of an 8 px model cube (head 0.9375 = 8 px at PLAYER_SCALE, body
    0.9375 x 1.406 x 0.469 = 8 x 12 x 4 px), so the box edge in blocks is scale / 2, not scale."""
    sc = bone.get("scale")
    return tuple(v / 2.0 for v in sc) if sc else default


# ---- check 2 ------------------------------------------------------------------------------------------------------
def left_hand_end(bone):
    """Hand end of the left arm box: its centre + the box's -Y half length, in the box's own rotation (caster-local)."""
    size = dump_box_size(bone, DEFAULT_ARM_SIZE)
    return add(tuple(bone["pos"]), qrot(bone["rot"], (0.0, -size[1] / 2.0, 0.0)))


def weapon_hands(skill):
    """(two_handed: bool | None, note) from the item data that binds the skill (None = no item found)."""
    item = skill.item()
    if item is None:
        return None, "no item in content/items binds %s: left hand NOT CHECKED" % skill.id
    speed = (item.get("attributes") or {}).get("attack_speed")
    try:
        speed = float(speed)
    except (TypeError, ValueError):
        return None, "item %s has no attributes.attack_speed: left hand NOT CHECKED" % item.get("id")
    if speed <= HEAVY_ATTACK_SPEED:
        return True, "item %s attack_speed %.1f <= %.1f: heavy, two-handed" % (item.get("id"), speed, HEAVY_ATTACK_SPEED)
    return False, "item %s attack_speed %.1f > %.1f: one-handed weapon, left hand free" % (item.get("id"), speed, HEAVY_ATTACK_SPEED)


def check_two_hand_grip(plan, skill):
    c = "2 two-hand grip"
    two_handed, hands_note = weapon_hands(skill)
    if plan is None:
        emit(c, "FAIL", "no plan dump: cannot check either hand (%s)" % hands_note)
        return
    n = len(plan["ticks"])
    right_far, left_far, missing_r = [], [], []
    left_present = all("left_arm" in t["bones"] for t in plan["ticks"])
    for tk in plan["ticks"]:
        w = tk.get("weapon")
        if not w:
            continue
        rh = tk["bones"].get("right_hand")
        if rh is None:
            missing_r.append(tk["tick"])
        else:
            d = math.dist(rh["pos"], w["pos"])
            if d > GRIP_TOL:
                right_far.append((tk["tick"], d))
        la = tk["bones"].get("left_arm") if left_present else None
        # a neutral left arm (identity rotation) is the vanilla held pose after the hand-back: nobody claims a two-hand hold there
        if la is not None and any(abs(v) > 1e-6 for v in la["rot"][:3]):
            d = math.dist(left_hand_end(tk["bones"]["left_arm"]), w["pos"])
            if d > LEFT_GRIP_TOL:
                left_far.append((tk["tick"], d))
    if missing_r:
        emit(c + " right", "FAIL", "right_hand bone missing from the dump at t%d (+%d more)" % (missing_r[0], len(missing_r) - 1))
    elif right_far:
        emit(c + " right", "FAIL", "right hand %.2f blocks from the grip at t%d (limit %.2f); %d/%d ticks off" % (right_far[0][1], right_far[0][0], GRIP_TOL, len(right_far), n),
             ticks=[t for t, _ in right_far])
    else:
        emit(c + " right", "PASS", "right hand within %.2f of the grip on all %d ticks" % (GRIP_TOL, n))
    if two_handed is None:
        emit(c + " left", "NOT CHECKED", hands_note)
    elif not two_handed:
        emit(c + " left", "PASS", "%s: not required on the grip" % hands_note)
    elif not left_present:
        emit(c + " left", "NOT CHECKED", "the plan dump has no left_arm bone on every tick: the left hand cannot be located, so the two-hand grip is NOT verified (%s)" % hands_note)
    elif left_far:
        worst = max(left_far, key=lambda x: x[1])
        emit(c + " left", "FAIL", "left hand end %.2f blocks from the grip at t%d (limit %.2f); %d/%d ticks off, worst %.2f at t%d" % (
            left_far[0][1], left_far[0][0], LEFT_GRIP_TOL, len(left_far), n, worst[1], worst[0]), ticks=[t for t, _ in left_far])
    else:
        emit(c + " left", "PASS", "left hand end within %.2f of the grip on all %d ticks (%s)" % (LEFT_GRIP_TOL, n, hands_note))


# ---- check 3 ------------------------------------------------------------------------------------------------------
def lowest_corner_y(bone):
    size = dump_box_size(bone, DEFAULT_LEG_SIZE)
    lo = float("inf")
    for sx in (-1, 1):
        for sy in (-1, 1):
            for sz in (-1, 1):
                p = add(tuple(bone["pos"]), qrot(bone["rot"], (sx * size[0] / 2, sy * size[1] / 2, sz * size[2] / 2)))
                lo = min(lo, p[1])
    return lo


def check_feet_on_floor(plan):
    c = "3 feet on floor"
    if plan is None:
        emit(c, "FAIL", "no plan dump: cannot locate the feet")
        return
    legs = ("left_leg", "right_leg")
    caster = {e["tick"]: e["pos"] for e in plan.get("caster", [])}
    base_y = caster[min(caster)][1] if caster else 0.0
    bad, airborne, n = [], [], 0
    for tk in plan["ticks"]:
        if not all(leg in tk["bones"] for leg in legs):
            emit(c, "NOT CHECKED", "leg bones missing from the dump at t%d" % tk["tick"])
            return
        t = tk["tick"]
        if t in caster and caster[t][1] - base_y > AIRBORNE_Y:
            airborne.append(t)
            continue
        n += 1
        for leg in legs:
            y = lowest_corner_y(tk["bones"][leg])
            if abs(y) > FLOOR_TOL:
                bad.append((t, leg, y))
    note = "; %d airborne ticks skipped (t%d-%d)" % (len(airborne), airborne[0], airborne[-1]) if airborne else ""
    if not caster:
        note += "; no caster track in the dump, every tick treated as grounded"
    if bad:
        worst = max(bad, key=lambda x: abs(x[2]))
        emit(c, "FAIL", "%d foot readings off the floor beyond %.2f: first t%d %s sole y=%+.2f, worst t%d %s y=%+.2f%s" % (
            len(bad), FLOOR_TOL, bad[0][0], bad[0][1], bad[0][2], worst[0], worst[1], worst[2], note), ticks=sorted({b[0] for b in bad}))
    else:
        emit(c, "PASS", "both soles within %.2f of the floor on %d grounded ticks%s" % (FLOOR_TOL, n, note))


# ---- check 4 ------------------------------------------------------------------------------------------------------
def check_display_budget(skill):
    c = "4 display budget"
    end = 0
    for vid, (delay, doc) in skill.vfx.items():
        end = max(end, delay + int(doc.get("duration_ticks", 0)))
    disp, parts = [0] * (end + 1), [0] * (end + 1)
    for vid, (delay, doc) in skill.vfx.items():
        dur = int(doc.get("duration_ticks", 0))
        if doc.get("rigs"):
            n = sum(len(r.get("parts", [])) for r in doc["rigs"])
            for T in range(delay, delay + dur):
                disp[T] += n
        for el in doc.get("elements", []):
            s, e = element_span(el, dur)
            kind = el.get("kind", {})
            if kind.get("type") == "particle":
                for et in emission_ticks(el, dur):
                    parts[et + delay] += int(load_particle_count(kind.get("particle_id")))
            elif "emit" in el:
                em = emission_ticks(el, dur)
                per = float(el["emit"].get("rate_per_tick", 1))
                for et in em:
                    parts[et + delay] += int(per)
                life = int(el["emit"].get("lifetime", el["emit"].get("lifetime_ticks", 6)))
                for T in range(s + delay, s + delay + len(em) + life):
                    disp[min(T, end)] += min(int(el["emit"].get("count", 1)), int(per) * (T - s - delay + 1))
            else:
                for T in range(s + delay, min(e + delay, end + 1)):
                    disp[T] += 1
    peak_t = max(range(len(disp)), key=lambda t: disp[t])
    peak_p = max(range(len(parts)), key=lambda t: parts[t])
    emit(c + " displays", "PASS" if disp[peak_t] <= BUDGET_DISPLAYS else "FAIL",
         "peak %d simultaneous Displays at skill t%d (budget %d)" % (disp[peak_t], peak_t, BUDGET_DISPLAYS), ticks=[peak_t])
    emit(c + " particles", "PASS" if parts[peak_p] <= BUDGET_PARTICLES else "FAIL",
         "peak %d particles spawned per tick at skill t%d (budget %d)" % (parts[peak_p], peak_p, BUDGET_PARTICLES), ticks=[peak_p])


# ---- check 5 ------------------------------------------------------------------------------------------------------
def _has_word(text, words):
    t = str(text or "").lower()
    return any(w in t for w in words)


def _targeter_key(t):
    if not isinstance(t, dict):
        return str(t)
    return json.dumps({k: lit(v) if isinstance(v, dict) and "value" in v else v for k, v in sorted(t.items())}, sort_keys=True, default=str)


def _event_bound_vfx(doc):
    """vfx ids played from an event phase anywhere in the skill (on_hit lists, projectile hit_entity / hit_block / miss / expire)."""
    found = set()

    def walk(node, in_event):
        if isinstance(node, dict):
            if in_event and node.get("type") == "play_vfx" and node.get("vfx_id"):
                found.add(node["vfx_id"])
            for k, v in node.items():
                walk(v, in_event or k in EVENT_PHASES)
        elif isinstance(node, list):
            for v in node:
                walk(v, in_event)
    walk(doc, False)
    return found


def _span_blocks(el):
    pts = [kf.get("translate") for kf in el.get("keyframes", []) if isinstance(kf.get("translate"), list) and len(kf["translate"]) >= 3]
    if len(pts) < 2:
        return 0.0
    return max(math.dist(pts[0][:3], p[:3]) for p in pts[1:])


def check_hit_events(skill):
    c = "5 hit events"
    problems = []
    event_vfx = _event_bound_vfx(skill.doc)
    # (a) fake projectiles and (b) stuck objects at caster-local offsets, read from every cast-time vfx
    for vid, (delay, doc) in skill.vfx.items():
        attached = bool(doc.get("follow")) or bool(doc.get("attach_to_anchor"))
        for el in doc.get("elements", []):
            kind = el.get("kind", {})
            eid = str(el.get("id", ""))
            if attached and kind.get("type") in ("item", "model", "block") and _has_word(eid, THROWN_WORDS) and _span_blocks(el) >= FAKE_FLIGHT_BLOCKS:
                problems.append("%s/%s: a %s element flies %.1f blocks on caster-local keyframes (fake projectile: it goes where the capture dummy stood, "
                                "never where the enemy is); use a `projectile` with `visual.display` and `hit_entity:` / `miss:`" % (vid, eid, kind.get("type"), _span_blocks(el)))
            if attached and _has_word(eid, STUCK_WORDS) and vid not in event_vfx:
                problems.append("%s/%s: a stuck / linked object placed at a caster-local offset in a follow vfx (it appears whether or not anything was hit); "
                                "spawn it from `on_hit:` / `hit_entity:` anchored to the hit entity" % (vid, eid))
    # (c) hit-flavoured vfx aimed with an entity-seeking targeter at a tick offset, not bound to the event nor co-timed with its damage
    damage_at = {}
    for s in skill.steps:
        if s.get("type") == "damage":
            damage_at.setdefault(_targeter_key(s.get("targets")), set()).add(int(lit(s.get("delay_ticks"))))
    event_sources = sorted({s.get("type") for s in skill.steps if s.get("type") in ("dash_strike", "projectile", "launch_projectile", "counter", "parry")})
    for s in skill.steps:
        if s.get("type") != "play_vfx":
            continue
        vid = s.get("vfx_id")
        t = s.get("targets") or {}
        ttype = str(t.get("type", "")) if isinstance(t, dict) else str(t)
        doc = skill.vfx.get(vid, (0, {}))[1]
        hitty = _has_word(vid, HIT_WORDS) or any(_has_word(el.get("id"), HIT_WORDS) for el in doc.get("elements", []))
        if ttype not in ENTITY_SEEKING or not hitty or vid in event_vfx:
            continue
        delay = int(lit(s.get("delay_ticks")))
        if delay in damage_at.get(_targeter_key(t), set()):
            continue
        hint = ("the skill has %s: put this play_vfx in its `on_hit:` / `hit_entity:` with `targets: {type: target}`" % " / ".join(event_sources)) if event_sources \
            else "schedule the `damage` with the same targeter at the same delay_ticks (or make the hit a projectile / dash_strike event)"
        problems.append("%s: hit effect aimed with `%s` at cast+%d, not bound to the hit (damage with that targeter at %s); %s"
                        % (vid, ttype, delay, sorted(damage_at.get(_targeter_key(t), set())) or "no tick", hint))
    if problems:
        emit(c, "FAIL", "%d tick-timed fake(s): " % len(problems) + " | ".join(problems))
    else:
        emit(c, "PASS", "thrown objects are projectiles, stuck objects and hit effects are bound to the hit event or co-timed with their damage")


# ---- check 6 ------------------------------------------------------------------------------------------------------
def check_inventory_models(skill, inventory, case):
    c = "6 inventory models"
    shown = skill.item_models()
    if inventory is None:
        emit(c, "NOT CHECKED", "no tools/skill-compare/data/%s/inventory.md: the %d model(s) the skill shows (%s) are not checked against an inventory"
             % (case, len(shown), ", ".join(sorted(shown)) or "none"))
        return
    problems = []
    listed = set()
    for r in inventory:
        ids = row_models(r)
        listed.update(ids)
        if is_3d(r):
            if not ids:
                problems.append("row %s (%s): a 3D object row names no model in its 構成案 column (write `astera:<model-id>`; search content/models/items first)" % (r["n"], r["object"]))
            for mid in ids:
                if not model_exists(mid):
                    problems.append("row %s (%s): model %s does not exist under content/models/items/" % (r["n"], r["object"], mid))
    for mid in sorted(set(listed)):
        if mid not in shown and model_exists(mid):
            rows = [r["n"] for r in inventory if mid in row_models(r)]
            problems.append("row %s: model %s is in the inventory but no vfx of the skill shows it (`item_model: astera:%s` missing; the modeler's work was dropped)" % (",".join(rows), mid, mid))
    for mid, where in sorted(shown.items()):
        if mid not in listed:
            problems.append("%s shows item_model astera:%s, which no inventory row names (an object the founder never approved; add the row and return to gate 1, or remove it)" % (", ".join(sorted(set(where))), mid))
    if problems:
        emit(c, "FAIL", "%d inventory / model mismatch(es): " % len(problems) + " | ".join(problems))
    else:
        emit(c, "PASS", "%d inventory row(s), %d 3D row(s) with existing models, every shown item_model (%s) is an inventory row"
             % (len(inventory), sum(1 for r in inventory if is_3d(r)), ", ".join(sorted(shown)) or "none"))


# ---- check 7 ------------------------------------------------------------------------------------------------------
def check_distance_bake(skill, case):
    c = "7 distance bake"
    distance = case_distance(case)
    if distance is None:
        emit(c, "NOT CHECKED", "no case intent with an enemy actor for %s: the capture DISTANCE is unknown" % case)
        return
    if distance < DISTANCE_MIN:
        emit(c, "NOT CHECKED", "case DISTANCE %.2f is inside melee reach (< %.1f): a baked offset cannot be told from a normal one" % (distance, DISTANCE_MIN))
        return
    hits = []

    def walk(node, where, path):
        if isinstance(node, dict):
            for k, v in node.items():
                if k in POSITION_KEYS and isinstance(v, list) and len(v) == 3 and all(isinstance(x, (int, float)) for x in v):
                    for i in HORIZONTAL:
                        x = v[i]
                        if abs(abs(float(x)) - distance) <= DISTANCE_TOL:
                            hits.append("%s %s.%s[%d]=%s" % (where, path, k, i, x))
                else:
                    walk(v, where, path + "." + str(k) if path else str(k))
        elif isinstance(node, list):
            for i, v in enumerate(node):
                tag = v.get("id") if isinstance(v, dict) and isinstance(v.get("id"), str) else str(i)
                walk(v, where, path + "[" + tag + "]")
    skipped = []
    for where, doc in skill.all_docs.items():
        vid = where[4:] if where.startswith("vfx:") else None
        tg = skill.vfx_targets.get(vid, set()) if vid else set()
        if tg and all(t in TARGET_ANCHORED for t in tg):
            skipped.append(vid)  # played at the hit entity / hit point: its frame is the target's, not the capture distance
            continue
        walk(doc, where, "")
    if hits:
        emit(c, "FAIL", "capture DISTANCE %.2f (case intent enemy x) is baked into content at %d place(s): %s. Bind the object to the hit entity / projectile instead"
             % (distance, len(hits), " | ".join(hits[:8]) + (" ..." if len(hits) > 8 else "")))
    else:
        emit(c, "PASS", "no caster-anchored translate / offset carries the capture DISTANCE %.2f (+-%.1f) in %d document(s)%s"
             % (distance, DISTANCE_TOL, len(skill.all_docs) - len(skipped), " (%d target-anchored vfx not in scope)" % len(skipped) if skipped else ""))


# ---- report.json (published to skill-compare by tools/skill-compare/scripts/publish-lint.mjs) ---------------------------
def git(*a):
    return subprocess.check_output(["git", *a], cwd=ROOT, text=True).strip()


def write_report(skill_id, plan_path):
    checks = []
    for (name, status, text), d in zip(RESULTS, DETAILS):
        e = {"check": name, "group": name.split(" ")[0], "status": status.replace(" ", "_"), "text": text}
        e.update({k: v for k, v in d.items() if v is not None})
        checks.append(e)
    fails = sum(1 for c in checks if c["status"] == "FAIL")
    report = {
        "version": 2, "kind": "lint", "skill": skill_id, "ok": fails == 0,
        "summary": {"checks": len(checks), "fail": fails, "pass": sum(1 for c in checks if c["status"] == "PASS"),
                    "skip": sum(1 for c in checks if c["status"] in ("SKIP", "NOT_CHECKED"))},
        "provenance": {
            "branch": git("rev-parse", "--abbrev-ref", "HEAD"), "gitRevision": git("rev-parse", "HEAD"),
            "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "planTime": datetime.datetime.fromtimestamp(Path(plan_path).stat().st_mtime, datetime.timezone.utc).isoformat() if Path(plan_path).is_file() else None,
        },
        "checks": checks,
    }
    out = ROOT / "build/skill-lint" / skill_id / "report.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=1) + "\n")
    print("report    %s" % out.relative_to(ROOT))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("skill")
    ap.add_argument("--plan")
    ap.add_argument("--case", help="skill-compare case id (default = skill id): inventory.md and intent DISTANCE")
    ap.add_argument("--no-report", action="store_true")
    a = ap.parse_args()
    skill = Skill(a.skill)
    case = a.case or a.skill
    plan_path = Path(a.plan) if a.plan else ROOT / "build/skill-lint" / a.skill / "plan.json"
    plan = load_plan(plan_path)
    inventory = load_inventory(case)
    print("skill-lint %s  (plan dump %s, case %s inventory %s)\n" % (a.skill, "present" if plan else "MISSING", case,
          "%d rows" % len(inventory) if inventory is not None else "MISSING"))
    check_held_weapon(plan, inventory)
    check_two_hand_grip(plan, skill)
    check_feet_on_floor(plan)
    check_display_budget(skill)
    check_hit_events(skill)
    check_inventory_models(skill, inventory, case)
    check_distance_bake(skill, case)
    if not a.no_report:
        write_report(a.skill, plan_path)
    fails = [r for r in RESULTS if r[1] == "FAIL"]
    unchecked = [r for r in RESULTS if r[1] == "NOT CHECKED"]
    print("\n%d checks, %d FAIL, %d NOT CHECKED. %s" % (len(RESULTS), len(fails), len(unchecked),
          "Do not capture." if fails else ("OK to capture, but the NOT CHECKED items above are unverified." if unchecked else "OK to capture.")))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
