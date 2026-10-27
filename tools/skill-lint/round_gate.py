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
  5 framing        caster height (`framing[].casterHeightPct`) `this` is within 0.8x-1.25x of `ref` (review validity)
  6 fidelity       every mechanic type in the skill YAML has a shown / not shown line
  7 regression     `weaker than previous: none` (or `previous: none` on the first round); fx_pct / coverage is never
                   quoted there as an improvement (coverage is a floor signal, not quality)
  8 sibling        a row of accepted frames from a sibling skill of the same weapon (`sibling: <case>`), the six
                   art-language lines (stroke/fill, bands, taper, core line, glow, outline) and `art language: same`
  9 medium         right medium per element: `main body:` names what each beat's main body is made of, `sprites:` says where
                   2D TextDisplay sprites are used (right for slash arcs on the real swing plane and small custom particles;
                   wrong for pillars / beams, plates, filled starbursts, big flat fills, overlays, large center stamps),
                   `overlay:` is none or justified, `sticker check: no` (no such misuse reads as a pasted sprite in `side`);
                   `particle concept:` one line per beat (intent | anchor: tip/edge/contact/feet/dash/landing | params),
                   particles being designed from the skill concept, not copied from the reference;
                   a per-element medium table (| element | medium | reason |) whose media are vanilla particle /
                   display particle / BlockDisplay / ItemDisplay model / fixed-plane sprite / center-billboard sprite /
                   screen overlay; read from the skill's vfx YAML: every `billboard: center` sprite has a
                   `center-billboard sprite` row with a reason (disallowed by default), center-billboard elements are
                   at most CENTER_MAX_COUNT and CENTER_MAX_PCT of the display elements, a sprite whose estimated size
                   exceeds SPRITE_CAP_BLOCKS has a row whose reason states its size in blocks, and a pillar / beam /
                   column sprite over the cap fails outright (pillars and beams = particles + thin solids)
 10 gameplay       respectful remake, not a replica: `startup:` ticks to the first hit (<= STARTUP_MAX_TICKS unless the line
                   says `justified:`), `reach:` hit area in blocks, `projectile:` (none, or speed / gravity-arc / 3D hit /
                   max range when the skill fires one), `dash:` (when the skill moves the caster), `pvp:`, `pve:`,
                   `deviation from reference:` none or the list (gameplay wins over fidelity, the deviation is noted)
 11 review         (stage manager only) review.json reviewer.overall == ok, reviewer.regression == none,
                   reviewer.sibling.v == ok

The gate never judges quality. It only refuses a round whose facts are missing, so nobody can report a round
that was not compared with the reference and with the previous round, or whose timing was read by eye.
"""
import argparse
import fnmatch
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
    "sibling": "Sibling",
    "medium": "Medium",
    "gameplay": "Gameplay",
    "point": "One point to look at",
}
# Right medium per element (founder, S3 rebuild, corrected the same day: 「3Dを主体にしろってわけじゃない。2Dを使うのに適している
# 場面(カスタム「パーティクル」と斬撃)だよねって話」): 2D TextDisplay sprites are the right medium for slash arcs on the real
# swing plane and for small custom particles, the wrong one for pillars / beams, plates, filled starbursts, big flat fills,
# screen overlays and large center-billboard stamps. The gate never fails a skill because its slash body is sprites.
MEDIUM_LINES = ["main body", "sprites", "overlay", "sticker check"]
# Medium selection (founder, S3 r7: 「TextDisplay にするならプレイヤーに常に向く奴はあんまり作っちゃいけない、大きすぎると
# チープ具合がバレる。光の柱が特にひどい」; r7 had 98 sprite elements, 32 of them billboard: center). Every display element
# names its medium in a table; center-billboard sprites are disallowed unless justified; sprites have a size cap.
MEDIA = ["vanilla particle", "display particle", "blockdisplay", "itemdisplay model", "fixed-plane sprite",
         "center-billboard sprite", "screen overlay"]
CENTER_MAX_COUNT = 10        # center-billboard sprite elements per skill, hard cap (justification does not lift it)
CENTER_MAX_PCT = 30          # ... and at most this % of the skill's display elements
SPRITE_CAP_BLOCKS = 2.0      # estimated long side of a sprite at its largest keyframe; above it a size reason is required
# size estimate: max(scale.x, scale.y) * sprite px * 0.025 block/px (TextDisplay glyph; vfx.md r7 measured 256 px at
# scale 0.8906 = 5.7 blocks). An estimate, not a ruler: it triggers the justification row, it does not judge the look.
BLOCKS_PER_PX = 0.025
PILLAR_RE = re.compile(r"pillar|beam|column|(^|-)col(-|$)", re.I)
# art-language checklist (docs/contributing/vfx-team/reviewer.md "Art language"): one line each in the Sibling section
ART_LANGUAGE = ["stroke/fill", "bands", "taper", "core line", "glow", "outline"]
MOVE_MECHANICS = {"dash_strike", "propel", "leap", "set_velocity"}
# mechanic types the review capture may not be able to show; each needs a fidelity line
FIDELITY_MECHANICS = {"screen_flash", "send_title", "camera_shake", "play_sound"}
FRAMING_MIN, FRAMING_MAX = 0.8, 1.25
# Gameplay (founder: 「再現に夢中になりすぎて、普通に武器の挙動として弱いとかっていうのは避けたい。飛び道具が上手く飛ばない、発生フレームが
# 遅すぎる、範囲が狭すぎるetc… Limbusをリスペクトして、このゲームに合う形でリメイクする」): the reference sets the identity, play wins.
STARTUP_MAX_TICKS = 10        # ticks from cast to the first hit for a normal skill; above it the line needs `justified: <why>`
PROJECTILE_MECHANICS = {"projectile", "launch_projectile", "fire_projectile", "shoot"}
GAMEPLAY_LINES = ["startup", "reach", "projectile", "pvp", "pve", "deviation from reference"]

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
    out["displays"] = []
    for vid in dict.fromkeys(out["vfx"]):
        vp = ROOT / "content" / "vfx" / f"{vid}.yaml"
        if vp.exists():
            vfx_texts.append(vp.read_text())
            out["displays"].extend(scan_displays(vid, vp))
    joined = "\n".join(vfx_texts)
    out["particles"] = "particle" in joined or "spawn_particle" in out["types"]
    out["sprites"] = "sprite" in joined or "glyph" in joined
    return out


def sprite_px(sprite_id):
    sp = ROOT / "content" / "sprites" / f"{sprite_id}.yaml"
    if not sp.exists():
        return 256
    try:
        return int((yaml.safe_load(sp.read_text()) or {}).get("size", 256))
    except (ValueError, TypeError, yaml.YAMLError):
        return 256


def scan_displays(vid, path):
    """Non-particle entries of `elements:` in one vfx file: id, kind, sprite id, billboard, estimated size in blocks."""
    try:
        data = yaml.safe_load(path.read_text()) or {}
    except yaml.YAMLError:
        return []
    found = []
    for el in data.get("elements") or []:
        if not isinstance(el, dict):
            continue
        kind = el.get("kind") if isinstance(el.get("kind"), dict) else {}
        ktype = str(kind.get("type") or el.get("type") or "")
        if ktype in ("particle", "emit", ""):
            continue
        sprite = str(kind.get("sprite") or "")
        scale = 0.0
        for kf in el.get("keyframes") or []:
            s = kf.get("scale") if isinstance(kf, dict) else None
            if isinstance(s, list) and len(s) >= 2:
                try:
                    scale = max(scale, abs(float(s[0])), abs(float(s[1])))
                except (TypeError, ValueError):
                    pass
        blocks = scale * sprite_px(sprite) * BLOCKS_PER_PX if ktype == "sprite" and sprite else 0.0
        found.append({
            "vfx": vid,
            "id": str(el.get("id") or "?"),
            "kind": ktype,
            "sprite": sprite,
            "billboard": str(el.get("billboard") or "fixed").lower(),
            "blocks": blocks,
        })
    return found


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
    # compare.json = the JSON of GET /api/cases/<id>/compare (tools/skill-compare/lib/compare.mjs):
    # `regression: true` + `regressions: [{tick, view, round, vs, flags: ["fx_pct 2.37 -> 0.4 (-83%)", ..]}]`.
    cmp_json = note_dir / "compare" / "compare.json"
    flags = []
    if cmp_json.exists():
        try:
            data = json.loads(cmp_json.read_text())
            if data.get("regression"):
                for r in data.get("regressions") or []:
                    flags.append(f"t{r.get('tick')} {r.get('view')}: " + ", ".join(r.get("flags") or []))
                flags = flags or ["regression: true"]
        except (json.JSONDecodeError, AttributeError) as e:
            problems.append(f"compare.json unreadable: {e}")
    if flags:
        problems.append("compare API flags a regression vs the previous round: " + "; ".join(flags))
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
        report("framing", "FAIL", "caster height: ref X% | this Y% missing (compare API framing[].casterHeightPct)")
        return
    r, t = float(ref.group(1)), float(this.group(1))
    if r <= 0:
        report("framing", "FAIL", "ref caster height must be > 0")
        return
    ratio = t / r
    if FRAMING_MIN <= ratio <= FRAMING_MAX:
        report("framing", "PASS", f"caster height this/ref = {ratio:.2f} (within {FRAMING_MIN}-{FRAMING_MAX})")
    else:
        report("framing", "FAIL", f"caster height this/ref = {ratio:.2f}, outside {FRAMING_MIN}-{FRAMING_MAX}: capture is not review-valid, reframe (FOV_DOLLY / distance)")


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
    if re.search(r"fx_pct|coverage", body, re.I):
        report("regression", "FAIL", "coverage is not quality: fx_pct / coverage must not appear in the Regression section (it is a floor signal in Compare only)")
    elif value.lower() == "none":
        report("regression", "PASS", "weaker than previous: none")
    else:
        report("regression", "FAIL", f"round is weaker than the previous one: {value}")


def check_sibling(sections, note_dir):
    body = find_section(sections, SECTIONS["sibling"]) or ""
    problems = []
    sib = re.search(r"^sibling:\s*(.+)$", body, re.M | re.I)
    if not sib:
        problems.append("`sibling: <case>[, <case>]` line missing (or `sibling: none - <why no accepted sibling exists>`)")
    elif sib.group(1).strip().lower().startswith("none"):
        if "-" not in sib.group(1):
            problems.append("`sibling: none` needs a reason after ` - `")
    else:
        img = re.search(r"!\[[^\]]*\]\(([^)]+\.png)\)", body)
        src = re.search(r"source:\s*(\S+)", body)
        if not img:
            problems.append("no ![..](sibling/..png) row of the sibling's accepted frames")
        elif not (note_dir / img.group(1)).exists():
            problems.append(f"sibling sheet not found beside the note: {img.group(1)}")
        if not src or "/api/cases/" not in src.group(1):
            problems.append("source: must name the sibling frames on skill-compare (/api/cases/<sibling>/...)")
    missing = [k for k in ART_LANGUAGE if not re.search(rf"^{re.escape(k)}\s*:\s*\S", body, re.M | re.I)]
    if missing:
        problems.append("art-language lines missing: " + ", ".join(missing))
    verdict = re.search(r"^art language:\s*(\S+)", body, re.M | re.I)
    if not verdict:
        problems.append("`art language: same` line missing")
    elif verdict.group(1).strip().lower() != "same":
        problems.append(f"art language differs from the sibling ({verdict.group(1).strip()}): restore the weapon's language before the round is finished")
    if problems:
        report("sibling", "FAIL", "; ".join(problems))
    else:
        report("sibling", "PASS", f"sibling {sib.group(1).strip()}, art language same, six checklist lines")


def check_medium(sections, skill_info):
    body = find_section(sections, SECTIONS["medium"]) or ""
    problems = []
    missing = [k for k in MEDIUM_LINES if not re.search(rf"^{re.escape(k)}\s*:\s*\S", body, re.M | re.I)]
    if missing:
        problems.append("lines missing: " + ", ".join(missing))
    main = re.search(r"^main body:\s*(.+)$", body, re.M | re.I)
    if main and len(main.group(1).strip()) < 15:
        problems.append("main body: say what each beat's main body is made of (swing-plane sprite arc, particles, BlockDisplay solids, ...)")
    if not skill_info.get("particles"):
        problems.append("the skill's vfx files spawn no particles: every skill carries concept-designed particles anchored to the animation (particle concept)")
    overlay = re.search(r"^overlay:\s*(.+)$", body, re.M | re.I)
    if overlay and overlay.group(1).strip().lower() != "none" and len(overlay.group(1).strip()) < 20:
        problems.append("overlay: a screen overlay (screen_flash / full-screen quad / title) needs a one-line justification, or `none`")
    sticker = re.search(r"^sticker check:\s*(\S+)", body, re.M | re.I)
    if sticker and sticker.group(1).strip().lower() not in ("no", "none"):
        problems.append(f"sticker check (side view): {sticker.group(1)} - an effect that reads as a flat pasted sprite from the side is not finished")
    problems.extend(check_medium_table(body, skill_info.get("displays") or []))
    if skill_info.get("particles"):
        concept = re.search(r"^particle concept\s*:(.*?)(?=^(?![-|\s])|\Z)", body, re.M | re.S | re.I)
        beats = [ln for ln in (concept.group(1).splitlines() if concept else [])
                 if re.search(r"anchor\s*:\s*\S", ln, re.I) and re.search(r"params\s*:\s*\S", ln, re.I)]
        if not beats:
            problems.append("`particle concept:` missing: per beat one line `- <beat>: <intent> | anchor: tip/edge/contact/feet/"
                            "dash/landing | params: type, count, speed, spread, life, colour` (particles are designed from the "
                            "skill concept and anchored to the animation, not copied from the reference)")
        else:
            bad_anchor = [ln.strip()[:40] for ln in beats if not re.search(r"anchor\s*:\s*[^|]*(tip|edge|blade|contact|hit|feet|foot|hand|grip|dash|landing|land|caster|weapon|rig|body)", ln, re.I)]
            if bad_anchor:
                problems.append("particle concept anchors must be a point of the animation / weapon (tip, edge, contact, feet, dash, landing): " + "; ".join(bad_anchor))
    if problems:
        report("medium", "FAIL", "; ".join(problems))
    else:
        n = len(skill_info.get("displays") or [])
        c = sum(1 for d in skill_info.get("displays") or [] if d["billboard"] == "center")
        report("medium", "PASS", f"medium lines + particle concept present, no sticker from the side, medium table covers {n} display elements ({c} center-billboard)")


def parse_medium_table(body):
    """`| element | medium | reason |` rows of the Medium section -> list of (patterns, medium, reason)."""
    rows = []
    header_seen = False
    for line in body.splitlines():
        if not line.strip().startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if not header_seen:
            if len(cells) >= 2 and "element" in cells[0].lower() and "medium" in cells[1].lower():
                header_seen = True
            continue
        if all(set(c) <= set("-: ") for c in cells):
            continue
        if len(cells) < 3:
            rows.append(([c.strip("` ") for c in cells[0].split(",")], cells[1] if len(cells) > 1 else "", ""))
            continue
        rows.append(([c.strip("` ") for c in cells[0].split(",")], cells[1], cells[2]))
    return header_seen, rows


def row_matches(row, display):
    for pat in row[0]:
        if not pat:
            continue
        if fnmatch.fnmatchcase(display["id"], pat) or (display["sprite"] and fnmatch.fnmatchcase(display["sprite"], pat)):
            return True
    return False


def ids(displays, limit=8):
    names = [f"{d['id']} ({d['vfx']}, {d['blocks']:.1f} blocks)" if d["blocks"] else f"{d['id']} ({d['vfx']})" for d in displays]
    return ", ".join(names[:limit]) + (f" ... +{len(names) - limit}" if len(names) > limit else "")


def check_medium_table(body, displays):
    """Per-element medium table vs the skill's vfx YAML (center-billboard sprites, sprite size cap, pillars / beams)."""
    problems = []
    header_seen, rows = parse_medium_table(body)
    if not header_seen:
        problems.append("no `| element | medium | reason |` table: every display element names its medium "
                        "(vanilla particle / display particle / BlockDisplay / ItemDisplay model / fixed-plane sprite / "
                        "center-billboard sprite / screen overlay) with a one-line reason")
        if not displays:
            return problems
    bad_media = []
    for pats, medium, reason in rows:
        parts = [p.strip().lower() for p in re.split(r"[+,/]", medium) if p.strip()]
        if not parts or any(not any(p.startswith(m) or m.startswith(p) for m in MEDIA) for p in parts):
            bad_media.append(f"{','.join(pats)}: `{medium}`")
        elif len(reason.strip()) < 15:
            bad_media.append(f"{','.join(pats)}: reason missing")
    if bad_media:
        problems.append("medium table rows must use the media list with a reason: " + "; ".join(bad_media[:6]))
    if not displays:
        return problems

    def rows_for(d):
        return [r for r in rows if row_matches(r, d)]

    uncovered = [d for d in displays if not rows_for(d)]
    if uncovered:
        problems.append("display elements without a medium row (element id, sprite id or `glob*` patterns, comma-separated): " + ids(uncovered))

    center = [d for d in displays if d["billboard"] == "center"]
    unjustified = [d for d in center if not any("center-billboard" in r[1].lower() for r in rows_for(d))]
    if unjustified:
        problems.append("center-billboard sprites are disallowed by default (always face the player, read as a sticker); "
                        "each needs a `center-billboard sprite` row with its reason: " + ids(unjustified))
    pct = 100.0 * len(center) / len(displays)
    if len(center) > CENTER_MAX_COUNT or pct > CENTER_MAX_PCT:
        problems.append(f"{len(center)} center-billboard elements of {len(displays)} displays ({pct:.0f}%) exceed the cap "
                        f"({CENTER_MAX_COUNT} or {CENTER_MAX_PCT}%): rebuild them as particles / BlockDisplay / fixed-plane sprites")

    big = [d for d in displays if d["kind"] == "sprite" and d["blocks"] > SPRITE_CAP_BLOCKS]
    pillars = [d for d in big if PILLAR_RE.search(d["id"]) or PILLAR_RE.search(d["sprite"])]
    if pillars:
        problems.append(f"pillar / beam / column as one big sprite (> {SPRITE_CAP_BLOCKS} blocks): build it from particles "
                        "plus thin solids (BlockDisplay), never one sprite: " + ids(pillars))
    size_re = re.compile(r"\d+(\.\d+)?\s*blocks?", re.I)
    unsized = [d for d in big if d not in pillars and not any(size_re.search(r[2]) for r in rows_for(d))]
    if unsized:
        problems.append(f"sprites over the {SPRITE_CAP_BLOCKS}-block cap (estimated long side at the largest keyframe) need a row "
                        "whose reason states the size in blocks and why: " + ids(unsized))
    return problems


def check_gameplay(sections, skill_info):
    body = find_section(sections, SECTIONS["gameplay"]) or ""
    problems = []
    missing = [k for k in GAMEPLAY_LINES if not re.search(rf"^{re.escape(k)}\s*:\s*\S", body, re.M | re.I)]
    if skill_info["types"] & MOVE_MECHANICS and not re.search(r"^dash\s*:\s*\S", body, re.M | re.I):
        missing.append("dash")
    if missing:
        problems.append("lines missing: " + ", ".join(missing))
    startup = re.search(r"^startup:\s*(.*)$", body, re.M | re.I)
    if startup:
        n = re.search(r"(\d+)\s*ticks?", startup.group(1))
        if not n:
            problems.append("startup: state the ticks from cast to the first hit (`N ticks`, from the trace)")
        elif int(n.group(1)) > STARTUP_MAX_TICKS and not re.search(r"justified\s*:\s*\S", startup.group(1), re.I):
            problems.append(f"startup {n.group(1)} ticks > {STARTUP_MAX_TICKS}: a normal skill hits within {STARTUP_MAX_TICKS} ticks; "
                            "compress the reference timing or add `justified: <why this is a slow heavy>`")
    reach = re.search(r"^reach:\s*(.*)$", body, re.M | re.I)
    if reach and not re.search(r"\d+(\.\d+)?\s*blocks?", reach.group(1), re.I):
        problems.append("reach: give the hit area / range in blocks (cone or box, from the skill YAML)")
    proj = re.search(r"^projectile:\s*(.*)$", body, re.M | re.I)
    if skill_info["types"] & PROJECTILE_MECHANICS:
        if proj and proj.group(1).strip().lower().startswith("none"):
            problems.append("projectile: the skill fires one; state speed, gravity / arc, 3D hit detection and max range")
        elif proj:
            need = [w for w, rx in (("speed", r"speed"), ("gravity/arc", r"gravity|arc|straight"), ("3D hit", r"3d|hitbox|hit detection|hit box"), ("range", r"range")) if not re.search(rx, proj.group(1), re.I)]
            if need:
                problems.append("projectile line missing: " + ", ".join(need) + " (Minecraft-sensible 3D flight, never the reference's frame timing or 2D path)")
    if problems:
        report("gameplay", "FAIL", "; ".join(problems))
    else:
        report("gameplay", "PASS", "startup, reach, projectile, pvp / pve and deviation stated")


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
    sibling = rev.get("sibling")
    problems = []
    if not isinstance(overall, dict) or overall.get("v") not in ("ok", "ng") or not overall.get("reason"):
        problems.append("reviewer.overall {v: ok|ng, reason} missing (whole-impression verdict)")
    elif overall["v"] != "ok":
        problems.append("reviewer.overall is ng: " + str(overall.get("reason")))
    if not isinstance(sibling, dict) or sibling.get("v") not in ("ok", "ng") or not sibling.get("reason"):
        problems.append("reviewer.sibling {v: ok|ng, reason} missing (same art language, at least as refined as the sibling)")
    elif sibling["v"] != "ok":
        problems.append("reviewer.sibling is ng: " + str(sibling.get("reason")))
    if regression is None:
        problems.append("reviewer.regression missing (\"none\" or list)")
    elif regression not in ("none", [], None) and regression:
        problems.append("reviewer.regression: " + json.dumps(regression, ensure_ascii=False))
    if problems:
        report("review", "FAIL", "; ".join(problems))
    else:
        report("review", "PASS", "reviewer overall ok, regression none, sibling ok")


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
    check_sibling(sections, note.parent)
    check_medium(sections, skill_info)
    check_gameplay(sections, skill_info)
    check_review(case, args.stage)
    if failures:
        print(f"round-gate: FAIL ({', '.join(failures)}): the round is not finished; do not report it")
        sys.exit(1)
    print("round-gate: PASS")


if __name__ == "__main__":
    main()
