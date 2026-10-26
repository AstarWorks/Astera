#!/usr/bin/env python3
"""Skill ids affected by a commit range (capture farm): changed content/{skills,vfx,animations,sounds,poses}/** files -> skills.

  changed-skills.py [--root DIR] [--list-all] FROM TO     (FROM may be "" / 0000 = just TO's own diff vs its parent)

A changed skill file maps to its own `id:`. Any other changed file is a node in a reference graph: file G references file F when
G's text mentions one of F's names (its stem, its `id:`, the part of the id after `astera:`) as a whole word. Every skill that
reaches a changed file through that graph is affected (skill -> vfx -> animation/sound/sprite, and vfx -> vfx). Prints one skill id per
line, sorted. Skills that no longer exist are dropped.
"""
import argparse
import os
import re
import subprocess
import sys

DIRS = ("skills", "vfx", "animations", "sounds", "poses")
WORD_RE = re.compile(r"[A-Za-z0-9_-]+")
ID_RE = re.compile(r'^\s*id:\s*["\']?([A-Za-z0-9_:/.-]+)', re.M)


def names_of(path, text):
    stem = os.path.splitext(os.path.basename(path))[0]
    out = {stem}
    for m in ID_RE.finditer(text):
        v = m.group(1)
        out.add(v)
        if ":" in v:
            out.add(v.split(":", 1)[1])
    # compared as whole [A-Za-z0-9_-] words, so "astera:skills/fire-impact" matches through its last path component
    out |= {w for n in out for w in WORD_RE.findall(n)}
    return {n for n in out if len(n) >= 4 and WORD_RE.fullmatch(n)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--list-all", action="store_true", help="print every skill id and exit")
    ap.add_argument("frm", nargs="?", default="")
    ap.add_argument("to", nargs="?", default="HEAD")
    a = ap.parse_args()
    root = a.root

    files = {}  # relpath -> text ("" for binaries)
    for d in DIRS:
        for dp, _, fs in os.walk(os.path.join(root, "content", d)):
            for f in fs:
                p = os.path.join(dp, f)
                rel = os.path.relpath(p, root)
                text = ""
                if f.endswith((".yaml", ".yml", ".json")):
                    try:
                        text = open(p, encoding="utf-8").read()
                    except OSError:
                        pass
                files[rel] = text
    skills = {}  # relpath -> skill id
    for rel, text in files.items():
        if rel.startswith("content/skills/") and rel.endswith((".yaml", ".yml")):
            m = ID_RE.search(re.sub(r"^\s*#.*$", "", text, flags=re.M))
            skills[rel] = m.group(1) if m else os.path.splitext(os.path.basename(rel))[0]
    if a.list_all:
        print("\n".join(sorted(skills.values())))
        return 0

    base = a.frm if a.frm and not set(a.frm) <= {"0"} else a.to + "^"
    diff = subprocess.run(["git", "-C", root, "diff", "--name-only", base, a.to, "--", *[f"content/{d}" for d in DIRS]],
                          capture_output=True, text=True)
    if diff.returncode != 0:
        print(diff.stderr, file=sys.stderr)
        return 2
    changed = {l for l in diff.stdout.split("\n") if l}

    names = {rel: names_of(rel, text) for rel, text in files.items()}
    for rel in changed - set(files):  # deleted file: its stem still names what used to be referenced (skills mentioning it get re-shot)
        names[rel] = names_of(rel, "")
    # token -> files whose text contains it; F's users = files containing one of F's names
    tok_files = {}
    for g, gtext in files.items():
        for t in set(WORD_RE.findall(gtext)):
            tok_files.setdefault(t, set()).add(g)
    users = {f: set().union(*(tok_files.get(n, set()) for n in ns)) - {f} if ns else set() for f, ns in names.items()}
    seen, stack = set(), list(changed)
    while stack:
        f = stack.pop()
        if f in seen:
            continue
        seen.add(f)
        stack.extend(users.get(f, ()))
    hit = sorted({skills[f] for f in seen if f in skills})
    print("\n".join(hit))
    return 0


if __name__ == "__main__":
    sys.exit(main())
