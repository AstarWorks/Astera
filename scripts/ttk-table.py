#!/usr/bin/env python3
"""TTK table for production weapons, computed from content/items + content/skills.

Primary attack = the item's left_click skill, else right_click. Nominal damage = sum of every literal
`damage` component of one cast against one target (sequences: every step counted once). Players take
one hit per 20 ticks (invulnerability frames, PaperDamageGateway), so the effective interval is
max(cooldown, 20) and time-to-kill = (hits - 1) * interval (first hit at t=0).
Run: python3 scripts/ttk-table.py
"""
import glob
import math
import os

import yaml

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "content")
HP = 20.0
I_FRAMES = 20


def load(path):
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def literal_sum(components):
    total = 0.0
    for c in components or []:
        b = c.get("base", {})
        if isinstance(b, dict) and b.get("type") == "literal":
            total += float(b["value"])
    return total


def damage_sum(node, seen=()):
    """Sum literal base values of `damage` effects and melee `params.damage` under node.

    `cast_skill` effects are followed (once per skill id along a path) so a sequence that fires a
    projectile skill counts that projectile's damage.
    """
    total = 0.0
    if isinstance(node, dict):
        if node.get("type") == "cast_skill" and node.get("skill_id") and node["skill_id"] not in seen:
            sub = os.path.join(ROOT, "skills", node["skill_id"] + ".yaml")
            if os.path.exists(sub):
                skill = load(sub)
                nxt = seen + (node["skill_id"],)
                total += damage_sum(skill.get("params", {}), nxt) + damage_sum(skill.get("phases", {}), nxt)
        if node.get("type") == "damage":
            total += literal_sum(node.get("components"))
        if node.get("type") == "melee" and isinstance(node.get("damage"), list):
            total += literal_sum(node["damage"])
        for v in node.values():
            total += damage_sum(v, seen)
    elif isinstance(node, list):
        for v in node:
            total += damage_sum(v, seen)
    return total


def kind_of(item):
    if item.get("resource_type") == "ammo":
        return "gun"
    ident = item["id"]
    if "rifle" in ident:
        return "gun"
    if "staff" in ident:
        return "staff"
    return "sword"


def rows():
    out = []
    for path in sorted(glob.glob(os.path.join(ROOT, "items", "*.yaml"))):
        item = load(path)
        if item.get("equip_slot") != "mainhand" or not item.get("skills"):
            continue
        if (item.get("pdc_tags") or {}).get("astera:demo") == "true":
            continue
        bind = {s["trigger"]: s for s in item["skills"]}
        b = bind.get("left_click") or bind.get("right_click")
        if not b:
            continue
        sid = b["skill_id"]
        skill = load(os.path.join(ROOT, "skills", sid + ".yaml"))
        dmg = damage_sum(skill.get("params", {})) + damage_sum(skill.get("phases", {}))
        if dmg <= 0:
            continue
        cd = b.get("cooldown_override_ticks") or skill.get("cooldown_ticks", 0)
        interval = max(cd, I_FRAMES)
        hits = math.ceil(HP / dmg)
        out.append(dict(
            id=item["id"], lv=item.get("level_requirement", 1), skill=sid, kind=kind_of(item),
            dmg=dmg, cd=cd, interval=interval, hits=hits, ttk=(hits - 1) * interval / 20.0,
            dps=dmg * 20.0 / interval,
        ))
    return out


if __name__ == "__main__":
    print("| weapon | kind | Lv | skill | dmg | cd(t) | interval(t) | hits | TTK(s) | DPS |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for r in sorted(rows(), key=lambda r: (r["kind"], r["ttk"])):
        print(f"| {r['id']} | {r['kind']} | {r['lv']} | {r['skill']} | {r['dmg']:g} | {r['cd']} | {r['interval']} | {r['hits']} | {r['ttk']:.2f} | {r['dps']:.1f} |")
