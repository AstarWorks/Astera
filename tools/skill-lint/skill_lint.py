#!/usr/bin/env python3
"""skill-lint: preflight for a VFX skill, run BEFORE every capture. Four invariants, nothing else.

    python3 tools/skill-lint/skill_lint.py <skill-id> [--plan plan.json]
    make skill-lint SKILL=bastion-greatsword-s1 [PLAN=<plan.json>]

Checks (each prints PASS / FAIL / NOT CHECKED; exit 1 on any FAIL):
  1 one weapon     visibleWeapons == 1 on every tick of the plan dump
  2 two-hand grip  BOTH hand tips on the weapon grip on every tick: right hand bone at the grip, and the left arm's
                   hand end (left_arm box end, its -Y axis) within a tolerance of the grip. When the plan dump has no
                   left_arm bone this prints NOT CHECKED. It never passes silently.
  3 feet on floor  the lowest corner of the leg boxes is on the floor (y = 0 in the caster frame) within a tolerance
                   on every tick where the caster stands on the ground (airborne ticks, from the dump's `caster` track,
                   are listed as skipped, not passed)
  4 display budget simultaneous Display entities and particles spawned per tick stay under the budget

There are no per-skill targets. Facts about the REFERENCE (blade angle, torso lean, ...) are judged on skill-compare,
not here. Plan dump (written by the runtime, build/skill-lint/<skill>/plan.json): docs/contributing/vfx-team/skill-lint.md.
"""
import argparse
import datetime
import json
import math
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

# Rig defaults when the dump predates `bones.*.scale` (box size in blocks): 4 x 12 x 4 px boxes at PLAYER_SCALE.
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
        self.steps = self.doc["phases"]["cast"]
        self.vfx = {}  # vfx_id -> (delay, doc)
        for s in self.steps:
            if s.get("type") == "play_vfx":
                self.vfx[s["vfx_id"]] = (int(lit(s.get("delay_ticks"))), load_yaml("content/vfx/%s.yaml" % s["vfx_id"]))


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
def check_one_weapon(plan):
    c = "1 one weapon"
    if plan is None:
        emit(c, "FAIL", "no plan dump: cannot count visible weapons (run make skill-lint-plan)")
        return
    bad = [(t["tick"], t.get("visibleWeapons")) for t in plan["ticks"] if t.get("visibleWeapons") != 1]
    n = len(plan["ticks"])
    if plan.get("simulated"):
        emit(c, "NOT CHECKED", "the plan dump is a Python simulation (no runtime lease model): visibleWeapons is not measured")
    elif bad:
        emit(c, "FAIL", "visibleWeapons != 1 on %d/%d ticks, first t%d = %s" % (len(bad), n, bad[0][0], bad[0][1]), ticks=[b[0] for b in bad])
    else:
        emit(c, "PASS", "visibleWeapons == 1 on all %d ticks" % n)


# ---- check 2 ------------------------------------------------------------------------------------------------------
def left_hand_end(bone):
    """Hand end of the left arm box: its centre + the box's -Y half length, in the box's own rotation (caster-local)."""
    size = bone.get("scale") or DEFAULT_ARM_SIZE
    return add(tuple(bone["pos"]), qrot(bone["rot"], (0.0, -size[1] / 2.0, 0.0)))


def check_two_hand_grip(plan):
    c = "2 two-hand grip"
    if plan is None:
        emit(c, "FAIL", "no plan dump: cannot check either hand")
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
        if left_present:
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
    if not left_present:
        emit(c + " left", "NOT CHECKED", "the plan dump has no left_arm bone on every tick: the left hand cannot be located, so the two-hand grip is NOT verified")
    elif left_far:
        worst = max(left_far, key=lambda x: x[1])
        emit(c + " left", "FAIL", "left hand end %.2f blocks from the grip at t%d (limit %.2f); %d/%d ticks off, worst %.2f at t%d" % (
            left_far[0][1], left_far[0][0], LEFT_GRIP_TOL, len(left_far), n, worst[1], worst[0]), ticks=[t for t, _ in left_far])
    else:
        emit(c + " left", "PASS", "left hand end within %.2f of the grip on all %d ticks" % (LEFT_GRIP_TOL, n))


# ---- check 3 ------------------------------------------------------------------------------------------------------
def lowest_corner_y(bone):
    size = bone.get("scale") or DEFAULT_LEG_SIZE
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
    ap.add_argument("--no-report", action="store_true")
    a = ap.parse_args()
    skill = Skill(a.skill)
    plan_path = Path(a.plan) if a.plan else ROOT / "build/skill-lint" / a.skill / "plan.json"
    plan = load_plan(plan_path)
    print("skill-lint %s  (plan dump %s)\n" % (a.skill, "present" if plan else "MISSING"))
    check_one_weapon(plan)
    check_two_hand_grip(plan)
    check_feet_on_floor(plan)
    check_display_budget(skill)
    if not a.no_report:
        write_report(a.skill, plan_path)
    fails = [r for r in RESULTS if r[1] == "FAIL"]
    unchecked = [r for r in RESULTS if r[1] == "NOT CHECKED"]
    print("\n%d checks, %d FAIL, %d NOT CHECKED. %s" % (len(RESULTS), len(fails), len(unchecked),
          "Do not capture." if fails else ("OK to capture, but the NOT CHECKED items above are unverified." if unchecked else "OK to capture.")))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
