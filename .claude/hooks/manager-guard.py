#!/usr/bin/env python3
"""PreToolUse hook: the MAIN interactive session is a manager, not an implementer.

Rule: .claude/rules/manager-mode.md  (founder rule 2026-10-23, permanent).

Main vs subagent: Claude Code adds `agent_id` (and `agent_type`) to the hook
stdin JSON ONLY when the hook fires inside a subagent
(https://code.claude.com/docs/en/hooks, "Common input fields"). No agent_id =>
main session => guarded. Subagents (any cwd, incl. .claude/worktrees/*) and
Codex (separate process, no Claude hooks) are never affected.

Blocks (main session only): Edit/Write/NotebookEdit/MultiEdit and Bash commands
that write files, except the allowlisted write targets below.
Fails open on internal errors. Founder override: ASTERA_MANAGER_MODE=off in the
environment that launched Claude Code.
"""
import json, os, re, shlex, sys

ALLOWED_PREFIXES = ("/tmp/claude-1000/", "/dev/fd/")
ALLOWED_EXACT = ("/dev/null", "/dev/stderr", "/dev/stdout", "/tmp/claude-1000")
MEMORY_RE = re.compile(r"^/home/node/\.claude-accounts/[^/]+/(?:.*/)?memory/")
# One baton per track, in the primary checkout's state dir only (not a worktree copy).
HANDOFF_RE = re.compile(r"^(?!.*/\.claude/worktrees/).*/\.claude/state/handoffs/[a-z0-9][a-z0-9-]*\.md$")

MAKE_OK = re.compile(r"^(dev-content|dev-plugins|dev-deploy|dev-pack|docker-up|dev-hotswap\S*|help|check|report-check|skill-compare-(?:up|down|status)|vfx-auto-(?:up|down|status|once))$")
GIT_DENY = {"mv", "rm", "apply", "am", "clean"}
MUTATORS = {"cp", "mv", "rm", "rmdir", "ln", "install", "touch", "mkdir", "truncate", "chmod",
            "chown", "patch", "rsync", "unzip", "sponge", "ed", "ex", "shred"}
INTERP = {"python", "python3", "node", "bun", "ruby", "perl"}
SEP = {";", "&&", "||", "|", "&", "(", ")", "|&", ";;"}
WRAPPERS = {"sudo", "env", "command", "time", "nohup", "nice", "exec", "builtin"}
PY_WRITE = re.compile(
    r"""open\([^)]*(?:,|mode=)\s*['"][rb]*[wax+]|\.write_text|\.write_bytes|shutil\.|os\.(remove|rename|unlink|replace|makedirs|mkdir|rmdir|system)"""
    r"""|subprocess|\.(touch|mkdir|unlink|rename)\(|fs\.(write|append|rm|unlink|rename|mkdir|copy)|writeFile|appendFile|Bun\.write|File\.(write|open)""")

MSG = ("BLOCKED by manager-mode: the main session is a manager and must not edit "
       "product code/content/tests/build/infra ({why}). Delegate instead: spawn a subagent "
       "(Agent tool, e.g. astera-worker/general-purpose, prompt prefixed with "
       ".claude/prompts/agent-preamble.md) or run Codex via "
       "`scripts/codex-task.sh <task-id> <prompt-file>`. Rule: .claude/CLAUDE.md "
       "'Main session = manager only' + .claude/rules/manager-mode.md. "
       "Harness changes (.claude/**) also go through an agent.")


class Block(Exception):
    pass


def norm(p, cwd):
    p = os.path.expanduser(p)
    if not os.path.isabs(p):
        p = os.path.join(cwd, p)
    return os.path.normpath(p)


def path_ok(p, cwd):
    if not p or "$" in p or "`" in p:
        return False
    n = norm(p, cwd)
    if n.startswith(ALLOWED_PREFIXES) or n in ALLOWED_EXACT:
        return True
    return bool(MEMORY_RE.match(n) or HANDOFF_RE.search(n))


def need(p, cwd, why):
    if not path_ok(p, cwd):
        raise Block(f"{why} -> {p}")


def strip_heredocs(cmd):
    """Remove heredoc bodies; return (text, [bodies])."""
    bodies = []
    while True:
        m = re.search(r"<<-?\s*['\"]?(\w+)['\"]?", cmd)
        if not m:
            return cmd, bodies
        nl = cmd.find("\n", m.end())
        if nl < 0:
            return cmd[:m.start()] + cmd[m.end():], bodies
        end = re.search(r"\n[ \t]*" + re.escape(m.group(1)) + r"[ \t]*(?=\n|$|\))", cmd[nl:])
        stop = nl + end.start() if end else len(cmd)
        bodies.append(cmd[nl + 1:stop])
        cmd = cmd[:m.start()] + " " + cmd[m.end():nl] + cmd[stop:]


def tokenize(cmd):
    lex = shlex.shlex(cmd.replace("\n", " ; "), posix=True, punctuation_chars=True)
    lex.whitespace_split = True
    lex.commenters = ""
    return list(lex)


PUNCT = set("<>&|;()")


def segments(tokens, cwd):
    """Split into argv segments; check output redirection targets."""
    seg, outs, res = [], [], []
    i = 0
    while i < len(tokens):
        t = tokens[i]
        if t in SEP:
            if seg:
                res.append(seg)
            seg = []
        elif t and set(t) <= PUNCT and ">" in t:
            if t.endswith("&"):
                i += 1  # fd dup like >&2
            elif i + 1 < len(tokens):
                outs.append(tokens[i + 1])
                i += 1
        elif t and set(t) <= PUNCT and t.startswith("<"):
            if i + 1 < len(tokens) and not t.endswith("<"):
                i += 1  # input redirect target
        elif re.fullmatch(r"\d+", t) and i + 1 < len(tokens) and ">" in tokens[i + 1]:
            pass  # fd number prefix
        else:
            seg.append(t)
        i += 1
    if seg:
        res.append(seg)
    for o in outs:
        need(o, cwd, "output redirection")
    return res


def analyze(cmd, cwd, depth=0):
    if depth > 4:
        raise Block("nesting too deep")
    text, bodies = strip_heredocs(cmd)
    try:
        toks = tokenize(text)
    except ValueError:
        raise Block("cannot parse command; simplify it")
    for argv in segments(toks, cwd):
        check_argv(argv, cwd, bodies, depth)


def check_argv(argv, cwd, bodies, depth):
    while argv and (re.fullmatch(r"\w+=.*", argv[0]) or argv[0] in WRAPPERS):
        argv = argv[1:]
    if not argv:
        return
    c, a = os.path.basename(argv[0]), argv[1:]
    flags = [x for x in a if x.startswith("-")]
    paths = [x for x in a if not x.startswith("-")]

    if c in ("bash", "sh", "zsh") and "-c" in a:
        k = a.index("-c")
        if k + 1 < len(a):
            analyze(a[k + 1], cwd, depth + 1)
        return
    if c == "flock":
        if "-c" in a:
            k = a.index("-c")
            if k + 1 < len(a):
                analyze(a[k + 1], cwd, depth + 1)
        else:
            check_argv(a[2:] if len(a) > 2 else [], cwd, bodies, depth)
        return
    if c == "git":
        rest = list(a)
        while rest and rest[0].startswith("-"):
            rest = rest[2:] if rest[0] in ("-C", "-c") else rest[1:]
        sub = rest[0] if rest else ""
        args = rest[1:]
        if sub in GIT_DENY:
            raise Block(f"git {sub}")
        if sub == "checkout" and "--" in args:
            raise Block("git checkout -- <path>")
        if sub == "restore" and "--staged" not in args:
            raise Block("git restore (worktree)")
        if sub == "reset" and "--hard" in args:
            raise Block("git reset --hard")
        return
    if c == "make":
        for t in [x for x in a if not x.startswith("-") and "=" not in x]:
            if not MAKE_OK.match(t):
                raise Block(f"make {t} (allowed: dev-content/dev-plugins/dev-deploy/dev-pack/docker-up/dev-hotswap*/skill-compare-up|down|status/vfx-auto-up|down|status|once/help/check)")
        return
    if c in ("codex", "docker", "gh", "codex-task.sh"):
        return
    if c == "sed" and any(f.startswith("--in-place") or re.fullmatch(r"-[a-zA-Z]*i[a-zA-Z.]*", f) for f in flags):
        raise_if_bad = [p for p in paths[1:] if not path_ok(p, cwd)] or ([] if paths[1:] else ["<stdin>"])
        if raise_if_bad:
            raise Block(f"sed -i -> {raise_if_bad[0]}")
        return
    if c == "perl" and any(re.fullmatch(r"-[a-zA-Z]*i\S*", f) for f in flags):
        bad = [p for p in paths[1:] if not path_ok(p, cwd)] or ([] if paths[1:] else ["<stdin>"])
        if bad:
            raise Block(f"perl -i -> {bad[0]}")
        return
    if c == "awk" and "inplace" in " ".join(a):
        raise Block("awk -i inplace")
    if c == "tee":
        for p in paths:
            need(p, cwd, "tee")
        return
    if c == "dd":
        for x in a:
            if x.startswith("of="):
                need(x[3:], cwd, "dd of=")
        return
    if c in ("curl", "wget"):
        for k, x in enumerate(a):
            if x in ("-o", "-O", "--output") and k + 1 < len(a):
                need(a[k + 1], cwd, f"{c} output")
        return
    if c == "tar" and a and "x" in a[0]:
        raise Block("tar extract")
    if c == "find":
        if "-delete" in a:
            raise Block("find -delete")
        if "-exec" in a:
            sub = []
            for x in a[a.index("-exec") + 1:]:
                if x in (";", "+", "\\;"):
                    break
                sub.append(x)
            check_argv(sub, cwd, bodies, depth)
        return
    if c == "xargs":
        sub = [x for x in a if not x.startswith("-")]
        if sub:
            check_argv(sub, cwd, bodies, depth)
        return
    if c in MUTATORS:
        for p in paths:
            need(p, cwd, c)
        return
    if c in INTERP or re.fullmatch(r"python3?\.\d+", c):
        code = "\n".join(bodies)
        for k, x in enumerate(a):
            if x in ("-c", "-e", "-pe", "-ne") and k + 1 < len(a):
                code += "\n" + a[k + 1]
        if PY_WRITE.search(code):
            lits = re.findall(r"""['"](/[^'"\s]*)['"]""", code)
            if not lits or not all(path_ok(l, cwd) for l in lits):
                raise Block(f"{c} script that writes files")
        return
    # read-only / unknown commands: allowed


def decide(d):
    if os.environ.get("ASTERA_MANAGER_MODE", "") == "off":
        return None
    if d.get("agent_id"):  # inside a subagent -> never restricted
        return None
    tool, ti = d.get("tool_name", ""), d.get("tool_input") or {}
    cwd = d.get("cwd") or os.getcwd()
    try:
        if tool in ("Edit", "Write", "MultiEdit"):
            need(ti.get("file_path", ""), cwd, tool)
        elif tool == "NotebookEdit":
            need(ti.get("notebook_path", ""), cwd, tool)
        elif tool == "Bash":
            analyze(ti.get("command", ""), cwd)
    except Block as b:
        return MSG.format(why=str(b))
    return None


def main():
    try:
        msg = decide(json.load(sys.stdin))
    except Exception as e:  # fail open
        print(f"manager-guard internal error (allowed): {e}", file=sys.stderr)
        return 0
    if msg:
        print(msg, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
