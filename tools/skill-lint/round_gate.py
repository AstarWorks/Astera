#!/usr/bin/env python3
"""round-gate: a build round of a skill is finished only when its round note carries the mandatory facts.

    python3 tools/skill-lint/round_gate.py <skill-id> [--file <round.md>] [--case <case>] [--stage author|manager]
    make round-gate SKILL=<skill-id> [FILE=<round.md>] [CASE=<case>] [STAGE=manager]

Template and meaning of every section: docs/contributing/vfx-team/round-template.md. Why: retro-2026-10-27.md.

Checks (PASS / FAIL / NOT CHECKED per line; exit 1 on any FAIL):
  1 sections       every mandatory section exists and is not empty
  2 compare        the ref | this | previous sheet comes from the compare API (sheet.png beside the note, source line);
                   compare.json (when present) has no regression flag set
  3 trace          every vfx_id the skill plays is quoted with its server-side `cast+N`; a moving skill quotes `travel:` from trace
  4 scene          capture scene facts: DISTANCE stated, skippedTicks=0
  5 framing        caster bbox share `this` is within 0.5x-2x of `ref` (review validity)
  6 fidelity       every mechanic type in the skill YAML has a shown / not shown line
  7 regression     `weaker than previous: none` (or `previous: none` on the first round)
  8 review         (stage manager only) review.json reviewer.overall == ok and reviewer.regression == none

The gate never judges quality. It only refuses a round whose facts are missing, so nobody can report a round
that was not compared with the reference and with the previous round, or whose timing was read by eye.
"""
import argparse
import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]

SECTIONS = {
    "compare": "Compare",
    "trace": "Trace",
    "scene": "Capture scene",
    "framing": "Framing",
    "fidelity": "Tool fidelity",
    "regression": "Regression",
    "point": "One point to look at",
}
MOVE_MECHANICS = {"dash_strike", "propel", "leap", "set_velocity"}
# mechanic types the review capture may not be able to show; each needs a fidelity line
FIDELITY_MECHANICS = {"screen_flash", "send_title", "camera_shake", "play_sound"}
FRAMING_MIN, FRAMING_MAX = 0.5, 2.0

failures = []


def report(check, status, detail=""):
    print(f"{status:<11} {check:<10} {detail}")
    if status == "FAIL":
        failures.append(check)


def split_sections(text):
    """`## Heading` -> body. Heading match is case-insensitive on the words, extra text after the name allowed."""
    out = {}
    current = None
    for line in text.splitlines():
        m = re.match(r"^##\s+(.*)$", line)
        if m:
            current = m.group(1).strip().lower()
            out[current] = []
        elif current is not None:
            out[current].append(line)
    return {k: "\n".join(v).strip() for k, v in out.items()}


def find_section(sections, name):
    key = name.lower()
    for heading, body in sections.items():
        if heading.startswith(key):
            return body
    return None


def walk(node, out):
    if isinstance(node, dict):
        t = node.get("type")
        if isinstance(t, str):
            out["types"].add(t)
        if node.get("type") == "play_vfx" and isinstance(node.get("vfx_id"), str):
            out["vfx"].append(node["vfx_id"])
        for v in node.values():
            walk(v, out)
    elif isinstance(node, list):
        for v in node:
            walk(v, out)


def load_skill(skill):
    path = ROOT / "content" / "skills" / f"{skill}.yaml"
    if not path.exists():
        print(f"round-gate: no skill YAML at {path}")
        sys.exit(2)
    data = yaml.safe_load(path.read_text())
    out = {"types": set(), "vfx": []}
    walk(data, out)
    vfx_texts = []
    for vid in out["vfx"]:
        vp = ROOT / "content" / "vfx" / f"{vid}.yaml"
        if vp.exists():
            vfx_texts.append(vp.read_text())
    joined = "\n".join(vfx_texts)
    out["particles"] = "particle" in joined or "spawn_particle" in out["types"]
    out["sprites"] = "sprite" in joined or "glyph" in joined
    return out


def check_sections(sections):
    missing = [title for title in SECTIONS.values() if not find_section(sections, title)]
    if missing:
        report("sections", "FAIL", "missing or empty: " + ", ".join(missing))
    else:
        report("sections", "PASS", "all mandatory sections present")


def check_compare(sections, note_dir, case):
    body = find_section(sections, SECTIONS["compare"]) or ""
    img = re.search(r"!\[[^\]]*\]\(([^)]+sheet\.png)\)", body)
    src = re.search(r"source:\s*(\S+)", body)
    problems = []
    if not img:
        problems.append("no ![..](..sheet.png) image")
    elif not (note_dir / img.group(1)).exists():
        problems.append(f"sheet not found beside the note: {img.group(1)}")
    if not src or "/api/cases/" not in src.group(1):
        problems.append("source: must be the compare API (/api/cases/<id>/compare...)")
    elif case not in src.group(1):
        problems.append(f"source: names another case (expected {case})")
    if not re.search(r"\bprevious:\s*\S+", body):
        problems.append("previous: <revision> (or none) missing")
    cmp_json = note_dir / "compare" / "compare.json"
    flags = []
    if cmp_json.exists():
        try:
            data = json.loads(cmp_json.read_text())
            reg = data.get("regression") or {}
            flags = [k for k, v in (reg.items() if isinstance(reg, dict) else []) if v]
        except (json.JSONDecodeError, AttributeError) as e:
            problems.append(f"compare.json unreadable: {e}")
    if flags:
        problems.append("compare.json regression flags set: " + ", ".join(flags))
    if problems:
        report("compare", "FAIL", "; ".join(problems))
    else:
        report("compare", "PASS", "API sheet present" + (" (compare.json flags clear)" if cmp_json.exists() else " (no compare.json yet)"))


def check_trace(sections, skill_info):
    body = find_section(sections, SECTIONS["trace"]) or ""
    missing = []
    for vid in dict.fromkeys(skill_info["vfx"]):
        found = any(vid in line and re.search(r"cast\+\d+", line) for line in body.splitlines())
        if not found:
            missing.append(vid)
    problems = []
    if missing:
        problems.append("no `cast+N` trace line for: " + ", ".join(missing))
    if skill_info["types"] & MOVE_MECHANICS:
        travel = re.search(r"^travel:.*\d.*trace", body, re.M | re.I)
        if not travel:
            problems.append("skill moves the caster (" + ", ".join(sorted(skill_info["types"] & MOVE_MECHANICS)) + ") but no `travel: ... from trace` line")
    if problems:
        report("trace", "FAIL", "; ".join(problems))
    else:
        report("trace", "PASS", f"{len(set(skill_info['vfx']))} vfx ids traced")


def check_scene(sections):
    body = find_section(sections, SECTIONS["scene"]) or ""
    problems = []
    if not re.search(r"DISTANCE\s*=\s*[\d.]+", body):
        problems.append("DISTANCE=<blocks> (case intent enemy x) missing")
    if not re.search(r"skippedTicks\s*=\s*0\b", body):
        problems.append("skippedTicks=0 missing")
    if problems:
        report("scene", "FAIL", "; ".join(problems))
    else:
        report("scene", "PASS", "distance and tick coverage stated")


def check_framing(sections):
    body = find_section(sections, SECTIONS["framing"]) or ""
    ref = re.search(r"ref\s*([\d.]+)\s*%", body)
    this = re.search(r"this\s*([\d.]+)\s*%", body)
    if not ref or not this:
        report("framing", "FAIL", "caster bbox: ref X% | this Y% missing")
        return
    r, t = float(ref.group(1)), float(this.group(1))
    if r <= 0:
        report("framing", "FAIL", "ref bbox share must be > 0")
        return
    ratio = t / r
    if FRAMING_MIN <= ratio <= FRAMING_MAX:
        report("framing", "PASS", f"caster bbox this/ref = {ratio:.2f} (within {FRAMING_MIN}-{FRAMING_MAX})")
    else:
        report("framing", "FAIL", f"caster bbox this/ref = {ratio:.2f}, outside {FRAMING_MIN}-{FRAMING_MAX}: capture is not review-valid, reframe (FOV_DOLLY / distance)")


def check_fidelity(sections, skill_info):
    body = (find_section(sections, SECTIONS["fidelity"]) or "").lower()
    needed = sorted(skill_info["types"] & FIDELITY_MECHANICS)
    if skill_info["particles"]:
        needed.append("particles")
    if skill_info["sprites"]:
        needed.append("sprites")
    missing = [n for n in needed if not re.search(rf"^{re.escape(n)}\s*:", body, re.M)]
    if missing:
        report("fidelity", "FAIL", "no shown / not shown line for: " + ", ".join(missing))
    else:
        report("fidelity", "PASS", "covered: " + ", ".join(needed))


def check_regression(sections, header):
    body = find_section(sections, SECTIONS["regression"]) or ""
    first_round = re.search(r"previous\s*(round)?\s*:?\s*none", header, re.I) or re.search(r"^previous:\s*none", body, re.M | re.I)
    m = re.search(r"^weaker than previous:\s*(.+)$", body, re.M | re.I)
    if first_round and not m:
        report("regression", "PASS", "first round (previous: none)")
        return
    if not m:
        report("regression", "FAIL", "`weaker than previous: none` line missing")
        return
    value = m.group(1).strip()
    if value.lower() == "none":
        report("regression", "PASS", "weaker than previous: none")
    else:
        report("regression", "FAIL", f"round is weaker than the previous one: {value}")


def check_review(case, stage):
    path = ROOT / "tools" / "skill-compare" / "data" / case / "review.json"
    if stage != "manager":
        report("review", "NOT CHECKED", "author stage (reviewer runs after the author)")
        return
    if not path.exists():
        report("review", "FAIL", f"{path.relative_to(ROOT)} missing")
        return
    try:
        data = json.loads(path.read_text())
    except json.JSONDecodeError as e:
        report("review", "FAIL", f"review.json unreadable: {e}")
        return
    rev = data.get("reviewer") or {}
    overall = rev.get("overall")
    regression = rev.get("regression")
    problems = []
    if not isinstance(overall, dict) or overall.get("v") not in ("ok", "ng") or not overall.get("reason"):
        problems.append("reviewer.overall {v: ok|ng, reason} missing (whole-impression verdict)")
    elif overall["v"] != "ok":
        problems.append("reviewer.overall is ng: " + str(overall.get("reason")))
    if regression is None:
        problems.append("reviewer.regression missing (\"none\" or list)")
    elif regression not in ("none", [], None) and regression:
        problems.append("reviewer.regression: " + json.dumps(regression, ensure_ascii=False))
    if problems:
        report("review", "FAIL", "; ".join(problems))
    else:
        report("review", "PASS", "reviewer overall ok, regression none")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("skill")
    ap.add_argument("--file", help="round note (default tools/skill-compare/data/<case>/round.md)")
    ap.add_argument("--case", help="case id (default = skill id)")
    ap.add_argument("--stage", choices=["author", "manager"], default="author")
    args = ap.parse_args()
    case = args.case or args.skill
    note = Path(args.file) if args.file else ROOT / "tools" / "skill-compare" / "data" / case / "round.md"
    if not note.exists():
        print(f"round-gate: round note not found: {note}")
        sys.exit(2)
    text = note.read_text()
    header = text.split("\n## ", 1)[0]
    sections = split_sections(text)
    skill_info = load_skill(args.skill)

    print(f"round-gate {args.skill} ({note.relative_to(ROOT) if note.is_relative_to(ROOT) else note}, stage {args.stage})")
    check_sections(sections)
    check_compare(sections, note.parent, case)
    check_trace(sections, skill_info)
    check_scene(sections)
    check_framing(sections)
    check_fidelity(sections, skill_info)
    check_regression(sections, header)
    check_review(case, args.stage)
    if failures:
        print(f"round-gate: FAIL ({', '.join(failures)}): the round is not finished; do not report it")
        sys.exit(1)
    print("round-gate: PASS")


if __name__ == "__main__":
    main()
