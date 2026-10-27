#!/usr/bin/env python3
"""PreToolUse hook (matcher: Agent): no build brief without an understanding file (C1) and
no founder quote without a referent (C2).

Docs: docs/contributing/understanding/README.md (root cause R1).
Main session only: Claude Code adds `agent_id` to the hook stdin JSON only inside a subagent (same
detection as manager-guard.py). Fails open on internal errors. Founder override: ASTERA_UNDERSTANDING_GATE=off.
`Understanding:` and `Kind: read-only` lines are found anywhere in the prompt (briefs start with the preamble).
"""
import json, os, re, sys

EXEMPT_TYPES = {"Explore", "Plan", "claude-code-guide", "statusline-setup", "rtm-sync",
                "astera-reviewer", "limbus-reviewer", "fork"}
PLACEHOLDER = re.compile(r"TODO|TBD|\?\?|^\?$|^<[^>]*>$", re.I)
UNKNOWNS = re.compile(r"^#{2,}\s*(分からないこと|わからないこと|Unknowns)\b.*$", re.M)
REFERENT = re.compile(r"^\s*[-*]?\s*(→\s*これは|->\s*refers to)")


def check_file(path):
    """Return a list of problems with the understanding file."""
    if not os.path.isfile(path):
        return [f"file does not exist: {path}"]
    text = open(path, encoding="utf-8").read()
    problems, lines = [], text.split("\n")
    for n, ln in enumerate(lines, 1):
        s = ln.strip()
        if s.startswith("|") and s.endswith("|"):
            if re.fullmatch(r"[|\s:-]+", s):
                continue
            for cell in (c.strip() for c in s.strip("|").split("|")):
                if cell == "" or PLACEHOLDER.search(cell):
                    problems.append(f"line {n}: empty cell or placeholder ({cell or 'empty'!r})")
                    break
        elif re.search(r"TODO|TBD|\?\?", ln):
            problems.append(f"line {n}: TODO/TBD/?? placeholder")
    m = UNKNOWNS.search(text)
    if not m:
        problems.append("section `## 分からないこと` (or `## Unknowns`) missing: no unknowns means nothing was doubted")
    else:
        body = [x for x in text[m.end():].split("\n") if x.strip()]
        if not body or body[0].lstrip().startswith("#"):
            problems.append("`## 分からないこと` section is empty")
    return problems


def is_read_only(prompt):
    return re.search(r"^\s*Kind:\s*read-only\b", prompt, re.M | re.I) is not None


def unreferenced_quotes(prompt):
    lines, bad = prompt.split("\n"), []
    for i, ln in enumerate(lines):
        quotes = re.findall(r"「[^」]+」", ln)
        if quotes and not any(REFERENT.match(x) for x in lines[i + 1:i + 4]):
            bad += quotes
    return bad


def decide(d):
    if os.environ.get("ASTERA_UNDERSTANDING_GATE", "") == "off" or d.get("agent_id"):
        return None
    if d.get("tool_name") not in ("Agent", "Task"):
        return None
    ti = d.get("tool_input") or {}
    if ti.get("subagent_type") in EXEMPT_TYPES:
        return None
    prompt = ti.get("prompt") or ""
    if is_read_only(prompt):
        return None
    msgs = []
    m = re.search(r"^\s*Understanding:\s*(/\S+)\s*$", prompt, re.M)
    if not m:
        msgs.append("brief has no `Understanding: <absolute path>` line. Fill a template from "
                    "docs/contributing/understanding/ (thing.md / system.md / ui-doc.md) first, or add a "
                    "`Kind: read-only` line if the agent edits nothing.")
    else:
        msgs += [f"Understanding file: {p}" for p in check_file(m.group(1))]
    bad = unreferenced_quotes(prompt)
    if bad:
        msgs.append("founder quotes without a referent (add a line starting `→ これは <ledger row / screen / function> の "
                    "<count|colour|direction|position>` within 3 lines after the quote): " + " ".join(bad))
    if not msgs:
        return None
    return ("BLOCKED by understanding-gate (docs/contributing/understanding/README.md):\n- " + "\n- ".join(msgs)
            + "\nOverride only by the founder: ASTERA_UNDERSTANDING_GATE=off.")


def main():
    try:
        msg = decide(json.load(sys.stdin))
    except Exception as e:  # fail open
        print(f"understanding-gate internal error (allowed): {e}", file=sys.stderr)
        return 0
    if msg:
        print(msg, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
