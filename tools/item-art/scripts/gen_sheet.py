#!/usr/bin/env python3
"""Generate an item-icon sheet with an image model (ADR-0236). Human-run, never part of the build.

    python3 tools/item-art/scripts/gen_sheet.py tools/item-art/sheets/<name>.yaml [--prompt-only]

Builds one prompt for every cell of the spec (one generation = one shared style), asks Codex CLI's
image tool for it, and writes content/textures/sources/sheets/<name>.png. `./gradlew :tools:item-art:run`
then slices the sheet by blob centroid and converts each cell to a 32x32 icon. Any other generator
(ChatGPT, a hand-made sheet) works too: drop the PNG at the same path and keep the grid order.
"""
from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[3]


def prompt(spec: dict) -> str:
    cols, rows = spec["grid"]
    cells = list(spec["cells"].items())
    if len(cells) > cols * rows:
        sys.exit(f"{len(cells)} cells do not fit a {cols}x{rows} grid")
    lines = [f"{i + 1} {item}: {desc}" for i, (item, desc) in enumerate(cells)]
    if len(cells) < cols * rows:
        lines.append(f"{len(cells) + 1}-{cols * rows}: leave empty (background only)")
    bg = spec["background"]
    return (
        "Use your image generation tool to create ONE image and save it as sheet.png in the current directory.\n\n"
        f"The image: a square sprite sheet of {cols * rows} RPG item icons in pixel art style, arranged in a strict "
        f"{cols}x{rows} grid of equal square cells. Plain flat solid {bg} background everywhere, NO cell borders, "
        "NO frames, NO text, NO labels, NO drop shadows. Each icon is centered in its cell, fills about 80% of it "
        f"and never crosses into neighbouring cells. Consistent style for all icons: {spec['style']}\n\n"
        "Cells in reading order (row by row, left to right):\n" + "\n".join(lines) + "\n"
    )


def generate(text: str, out: Path) -> None:
    with tempfile.TemporaryDirectory() as work:
        log = Path(work, "codex.log")
        with log.open("w") as err:
            subprocess.run(
                ["codex", "exec", "--sandbox", "workspace-write", "--skip-git-repo-check", "--cd", work, text],
                stdin=subprocess.DEVNULL, stdout=err, stderr=subprocess.STDOUT, check=False,
            )
        made = Path(work, "sheet.png")
        if not made.is_file():
            # Nested sandboxes block the copy; the image still lands in ~/.codex/generated_images/<session>/.
            session = re.search(r"session id: ([0-9a-f-]+)", log.read_text())
            pngs = sorted(Path.home().joinpath(".codex/generated_images", session.group(1)).glob("*.png"),
                          key=lambda p: p.stat().st_mtime) if session else []
            if not pngs:
                sys.exit(f"codex produced no image; log:\n{log.read_text()[-2000:]}")
            made = pngs[-1]
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(made, out)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("spec", type=Path)
    ap.add_argument("--prompt-only", action="store_true", help="print the prompt (paste it into any image model)")
    args = ap.parse_args()
    spec = yaml.safe_load(args.spec.read_text())
    text = prompt(spec)
    if args.prompt_only:
        print(text)
        return
    out = ROOT / "content/textures/sources/sheets" / f"{spec['name']}.png"
    generate(text, out)
    cols, rows = spec["grid"]
    print(f"wrote {out.relative_to(ROOT)}. Manifest entry for content/textures/sources/items.yaml:\n")
    print(f"  {spec['name']}:\n    file: sheets/{spec['name']}.png\n    grid: [{cols}, {rows}]")
    print("    cells: [" + ", ".join(spec["cells"]) + "]")


if __name__ == "__main__":
    main()
