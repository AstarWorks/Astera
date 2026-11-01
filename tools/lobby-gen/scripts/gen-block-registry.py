#!/usr/bin/env python3
"""Regenerates kernel/runtime/src/main/resources/{block,item}-registry.txt from the vanilla data report of the Paper server jar.

Run after bumping `paper` in gradle/libs.versions.toml:

    java -DbundlerMainClass=net.minecraft.data.Main -jar paper.jar --reports --output out
    python3 -I tools/lobby-gen/scripts/gen-block-registry.py out/reports/blocks.json out/reports/registries.json
"""
import json
import pathlib
import sys

blocks = json.load(open(sys.argv[1]))
registries = json.load(open(sys.argv[2]))
resources = pathlib.Path(__file__).resolve().parents[3] / "kernel/runtime/src/main/resources"
resources.mkdir(parents=True, exist_ok=True)
with open(resources / "block-registry.txt", "w") as out:
    for name in sorted(blocks):
        props = blocks[name].get("properties", {})
        out.write(name + "\t" + ",".join(f"{k}={'|'.join(v)}" for k, v in sorted(props.items())) + "\n")
items = sorted(registries["minecraft:item"]["entries"])
with open(resources / "item-registry.txt", "w") as out:
    out.writelines(name + "\n" for name in items)
print(f"wrote {resources} ({len(blocks)} blocks, {len(items)} items)")
