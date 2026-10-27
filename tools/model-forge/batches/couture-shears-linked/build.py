#!/usr/bin/env python3
"""Drive model-forge: import raw.model.json, apply vanilla materials + tints per group, set display, export.
usage: build.py <gen_dir> <forge_project_dir>"""
import json, subprocess, sys
from pathlib import Path
gen, proj = Path(sys.argv[1]), sys.argv[2]
forge = str(Path(__file__).resolve().parent/"forge_lax.py")


def call(op, args=None):
    r = subprocess.run([sys.executable, forge, "--project", proj, "call", op, json.dumps(args or {})],
                       capture_output=True, text=True)
    if r.returncode:
        print(op, "FAILED", r.stderr[-1500:]); sys.exit(1)
    return json.loads(r.stdout) if r.stdout.strip().startswith(("{", "[")) else r.stdout


groups = json.loads((gen/"groups.json").read_text())
MAT = {  # group -> (material, tint)
    "blade": ("metal.iron_block", "#4b3a73"), "ridge": ("metal.iron_block", "#22183a"),
    "edge": ("metal.iron_block", "#efe6ff"), "accent": ("gem.amethyst_block", "#8a63d9"),
    "thread": ("cloth.white_wool", "#d9c4ff"), "ring": ("metal.iron_block", "#33274f"),
    "gem": ("gem.amethyst_block", None), "petal": ("cloth.white_wool", "#f4eaff"),
    "heart": ("cloth.pink_wool", "#d83a6a"), "chain": ("metal.iron_block", "#6a5a8c"),
    "chain2": ("metal.iron_block", "#463a66"), "gold": ("metal.gold_block", "#d9b25a"),
}
call("import", {"path": str(gen/"raw.model.json")})
sc = call("scene.get", {"fields": ["id", "name"]})
n2i = {e["name"]: e["id"] for e in sc["elements"]}
for g, names in groups.items():
    ids = [n2i[n] for n in names]
    m, t = MAT[g]
    a = {"ids": ids, "material": m, "seed": 7}
    if t:
        a["tint"] = t
    call("material.apply", a)
r = subprocess.run([sys.executable, forge, "--project", proj, "call", "validate"], capture_output=True, text=True)
v = json.loads(r.stdout)
import collections
print("valid", v["valid"], "errors", v["errors"][:5])
print(collections.Counter(w["kind"] for w in v["warnings"]))
for w in v["warnings"]:
    if w["kind"] == "floating_components_aabb":
        print("floating:", [c for c in w["components"] if len(c) < 5])
