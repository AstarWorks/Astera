#!/usr/bin/env python3
"""Session budget (ADR-0323). Long sessions are structurally refused.

Modes (argv[1]):
  prompt  (default) UserPromptSubmit: warn >= WARN_TOKENS, block >= BLOCK_TOKENS or
          >= BLOCK_COMPACTIONS compactions. Handoff prompts always pass.
  compact SessionStart (matcher "compact"): bump the per-session compaction counter;
          from the 2nd compaction tell the model to write the handoff and stop.
  status  statusLine: print "ctx <N>k / compactions <M>".

Override: ASTERA_SESSION_BUDGET=off. Any internal error fails open (exit 0, no output).
State: .claude/state/session-budget/<session_id>.json (gitignored via .claude/state/*).
"""
import json
import os
import re
import sys

WARN_TOKENS = 120_000
BLOCK_TOKENS = 200_000
BLOCK_COMPACTIONS = 2
TAIL_BYTES = 4_000_000
HANDOFF_RE = re.compile(r"handoff|引き継ぎ|引継ぎ|^\s*/clear", re.IGNORECASE)


def state_path(session_id):
    base = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
    override = os.environ.get("ASTERA_SESSION_BUDGET_DIR")
    d = override or os.path.join(base, ".claude", "state", "session-budget")
    return os.path.join(d, re.sub(r"[^A-Za-z0-9_.-]", "_", session_id) + ".json")


def read_compactions(session_id):
    try:
        with open(state_path(session_id)) as f:
            return int(json.load(f).get("compactions", 0))
    except Exception:
        return 0


def context_tokens(transcript_path):
    """Last main-thread assistant usage; fallback bytes/4."""
    size = os.path.getsize(transcript_path)
    with open(transcript_path, "rb") as f:
        f.seek(max(0, size - TAIL_BYTES))
        data = f.read().decode("utf-8", "replace")
    for line in reversed(data.splitlines()):
        if '"usage"' not in line:
            continue
        try:
            d = json.loads(line)
        except Exception:
            continue
        if d.get("type") != "assistant" or d.get("isSidechain"):
            continue
        u = (d.get("message") or {}).get("usage")
        if isinstance(u, dict):
            return (int(u.get("input_tokens") or 0)
                    + int(u.get("cache_read_input_tokens") or 0)
                    + int(u.get("cache_creation_input_tokens") or 0))
    return size // 4


def emit(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False))


def prompt_mode(inp):
    sid = inp.get("session_id") or "unknown"
    tp = inp.get("transcript_path")
    tokens = context_tokens(tp) if tp and os.path.exists(tp) else 0
    comps = read_compactions(sid)
    text = inp.get("prompt") or ""
    if HANDOFF_RE.search(text):
        return
    k = tokens // 1000
    if tokens >= BLOCK_TOKENS or comps >= BLOCK_COMPACTIONS:
        emit({"decision": "block", "reason":
              f"Session budget (ADR-0323): ctx {k}k / compactions {comps}. "
              "Write the track handoff now (send a prompt containing 'handoff' or /handoff), "
              "then /clear and continue in a fresh session. Override: ASTERA_SESSION_BUDGET=off."})
    elif tokens >= WARN_TOKENS:
        msg = (f"Session budget (ADR-0323): ctx {k}k >= {WARN_TOKENS // 1000}k. "
               "Write the handoff and restart in a fresh session.")
        emit({"systemMessage": msg, "hookSpecificOutput": {
            "hookEventName": "UserPromptSubmit", "additionalContext": msg}})


def compact_mode(inp):
    sid = inp.get("session_id") or "unknown"
    n = read_compactions(sid) + 1
    p = state_path(sid)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w") as f:
        json.dump({"compactions": n}, f)
    if n >= BLOCK_COMPACTIONS:
        emit({"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext":
              f"Session budget (ADR-0323): this session has compacted {n} times. Do not continue the task. "
              "Write the track handoff now (.claude/CLAUDE.md section 'Session handoff'), then stop and "
              "ask the user to /clear and start a fresh session."}})


def status_mode(inp):
    sid = inp.get("session_id") or "unknown"
    tp = inp.get("transcript_path")
    tokens = context_tokens(tp) if tp and os.path.exists(tp) else 0
    sys.stdout.write(f"ctx {tokens // 1000}k / compactions {read_compactions(sid)}")


def main():
    if os.environ.get("ASTERA_SESSION_BUDGET") == "off":
        return
    mode = sys.argv[1] if len(sys.argv) > 1 else "prompt"
    inp = json.loads(sys.stdin.read() or "{}")
    {"prompt": prompt_mode, "compact": compact_mode, "status": status_mode}[mode](inp)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        pass
    sys.exit(0)
