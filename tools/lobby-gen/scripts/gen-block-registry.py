#!/usr/bin/env python3
"""Regenerates src/main/resources/block-registry.txt from the vanilla data report of the Paper server jar.

Run after bumping `paper` in gradle/libs.versions.toml:

    java -DbundlerMainClass=net.minecraft.data.Main -jar paper.jar --reports --output out
    python3 -I tools/lobby-gen/scripts/gen-block-registry.py out/reports/blocks.json
"""
import json
import pathlib
import sys

blocks = json.load(open(sys.argv[1]))
target = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/block-registry.txt"
target.parent.mkdir(parents=True, exist_ok=True)
with open(target, "w") as out:
    for name in sorted(blocks):
        props = blocks[name].get("properties", {})
        out.write(name + "\t" + ",".join(f"{k}={'|'.join(v)}" for k, v in sorted(props.items())) + "\n")
print(f"wrote {target} ({len(blocks)} blocks)")
