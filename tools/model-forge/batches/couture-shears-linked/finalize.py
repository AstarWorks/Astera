#!/usr/bin/env python3
"""Add display transforms to the forge export and install content/models/items/couture-shears-linked.model.json
+ manifest entry. usage: finalize.py <export_dir> <repo_root>"""
import json, sys
from pathlib import Path
exp, root = Path(sys.argv[1]), Path(sys.argv[2])
m = json.loads((exp/"model.json").read_text())
for e in m["elements"]:   # keep element names for review/debug
    pass
m["display"] = {
    # VFX item element uses fixed: model is ~2.9 blocks long, centred; scale 1 = true size, the element's scale multiplies.
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "gui": {"rotation": [0, 0, -40], "translation": [0, 0, 0], "scale": [0.3, 0.3, 0.3]},
    "ground": {"rotation": [75, 0, -90], "translation": [0, 2, 0], "scale": [0.14, 0.14, 0.14]},
    "firstperson_righthand": {"rotation": [0, -35, 12], "translation": [0.5, 1.0, -4.5], "scale": [0.16, 0.16, 0.16]},
    "firstperson_lefthand": {"rotation": [0, -35, 12], "translation": [0.5, 1.0, -4.5], "scale": [0.16, 0.16, 0.16]},
    "thirdperson_righthand": {"rotation": [35, -90, 0], "translation": [0, 2.4, 1.5], "scale": [0.26, 0.26, 0.26]},
    "thirdperson_lefthand": {"rotation": [35, -90, 0], "translation": [0, 2.4, 1.5], "scale": [0.26, 0.26, 0.26]},
}
m["gui_light"] = "front"
m["ambientocclusion"] = False
dst = root/"content/models/items/couture-shears-linked.model.json"
dst.write_text(json.dumps(m, indent=2) + "\n")
mf = root/"content/models/manifest.json"
mm = json.loads(mf.read_text())
mm["models"]["items/couture-shears-linked"] = {"path": "content/models/items/couture-shears-linked.model.json",
                                               "updated_at": "2026-10-27T00:00:00.000Z"}
mf.write_text(json.dumps(mm, indent=2) + "\n")
print("wrote", dst, len(m["elements"]), "elements")
