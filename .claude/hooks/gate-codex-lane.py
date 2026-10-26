#!/usr/bin/env python3
"""PreToolUse/Bash gate: a Codex lane runs in the foreground of a background Bash call.

Ported from AstarHarness plugin/scripts/hooks/gate-raw-codex-lane.sh (see its
docs/engineering/lane-operating-model.md). A detached Codex run (nohup / setsid / disown /
trailing &) notifies nobody when it exits. Launched with Bash `run_in_background: true`, the
foreground launcher's exit re-invokes the session with a single zero-token notification.

Denies (main session AND subagents alike):
  1. scripts/codex-task.sh unless tool_input.run_in_background is true.
  2. scripts/codex-task.sh or `codex exec|review` detached (nohup/setsid/disown/trailing &).
  3. raw `codex exec|review` that can write (anything but an explicit read-only sandbox,
     e.g. `-p astar-implement`, `-s danger-full-access`): implementation goes through the launcher.
Allows: `codex login|mcp|--version`, and `timeout N codex exec -s|--sandbox read-only ...`
in the foreground. Fails OPEN on any payload/parse error. Test: gate-codex-lane.test.sh.
"""
import json, os, re, shlex, sys

DETACHERS = {"setsid", "nohup", "disown"}
WRAPPERS = {"bash", "sh", "env", "command", "exec", "time", "nice", "sudo"}
SUBCMDS = {"exec", "review"}
LAUNCHER = "codex-task.sh"


def allow():
    print("{}")
    raise SystemExit(0)


def deny(reason):
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse", "permissionDecision": "deny",
        "permissionDecisionReason": reason}}, ensure_ascii=False, separators=(",", ":")))
    raise SystemExit(0)


def segments(tokens):
    cur = []
    for t in tokens:
        if t in ("&&", "||", ";", "|", "&", "|&", ";;"):
            if cur:
                yield cur, t
            cur = []
        else:
            cur.append(t)
    if cur:
        yield cur, ""


def split_ops(command):
    """shlex with punctuation_chars so `a&&b`, `a&` split into operators."""
    lex = shlex.shlex(command, posix=True, punctuation_chars=True)
    lex.whitespace_split = True
    lex.commenters = "#"
    return list(lex)


def codex_args(words):
    for i, w in enumerate(words):
        if os.path.basename(w) == "codex":
            return words[i + 1:]
    return None


def read_only(args):
    sb = []
    for i, a in enumerate(args):
        if a == "--":
            break
        if a in ("-s", "--sandbox") and i + 1 < len(args):
            sb.append(args[i + 1])
        elif a.startswith("--sandbox="):
            sb.append(a.partition("=")[2])
        elif a in ("-p", "--profile", "--dangerously-bypass-approvals-and-sandbox", "--full-auto") \
                or a.startswith("--profile="):
            return False
    return sb == ["read-only"]


def check(command, bg, depth=0):
    tokens = split_ops(command)
    for words, term in segments(tokens):
        w = list(words)
        detached = False
        while w:
            head = os.path.basename(w[0])
            if "=" in w[0] and not w[0].startswith(("-", "/")):
                w.pop(0)
            elif head in DETACHERS:
                detached = True
                w.pop(0)
            elif head == "timeout":
                w.pop(0)
                while w and w[0].startswith("-"):
                    if w.pop(0) in ("-k", "--kill-after", "-s", "--signal") and w:
                        w.pop(0)
                if w and re.fullmatch(r"\d+(?:\.\d+)?[smhd]?", w[0]):
                    w.pop(0)
            elif head in WRAPPERS:
                w.pop(0)
                if head in ("bash", "sh") and len(w) >= 2 and w[0] == "-c" and depth < 3:
                    check(w[1], bg, depth + 1)  # inner command analysed recursively
                    w = []
            else:
                break
        if not w:
            continue
        backgrounded = detached or term in ("&", "|&")
        if os.path.basename(w[0]) == LAUNCHER:
            if backgrounded:
                deny("Do not detach scripts/codex-task.sh (nohup/setsid/disown/trailing &): a detached "
                     "Codex run notifies nobody when it exits. Launch it as ONE Bash call with "
                     "run_in_background: true and wait for the single exit notification.")
            if not bg:
                deny("scripts/codex-task.sh runs Codex in the foreground and must be launched with Bash "
                     "run_in_background: true. Relaunch the same command with run_in_background: true, "
                     "then wait for the one completion notification (no polling/tail/sleep).")
            continue
        args = codex_args(w)
        if args is None:
            continue
        head_args = args[: args.index("--")] if "--" in args else args
        if not any(x in SUBCMDS for x in head_args):
            continue
        if backgrounded:
            deny("Do not detach a Codex run (nohup/setsid/disown/trailing &): its exit notifies nobody. "
                 "Use scripts/codex-task.sh <task-id> <prompt-file> as one Bash call with "
                 "run_in_background: true.")
        if not read_only(args):
            deny("Raw write-capable `codex exec` is not allowed. Implementation goes through "
                 "scripts/codex-task.sh <task-id> <prompt-file>, launched with Bash run_in_background: true. "
                 "Only `timeout N codex exec --sandbox read-only ...` may be run directly.")


def main():
    p = json.load(sys.stdin)
    if p.get("tool_name") != "Bash":
        allow()
    ti = p.get("tool_input") or {}
    cmd = ti.get("command") or ""
    if "codex" not in cmd:
        allow()
    try:
        check(cmd, ti.get("run_in_background") is True)
    except ValueError:
        allow()
    allow()


try:
    main()
except SystemExit:
    raise
except Exception:
    print("{}")
