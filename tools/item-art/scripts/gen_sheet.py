#!/usr/bin/env python3
"""Generate an item-icon or particle-animation sheet with an image model (ADR-0236 / ADR-0237).
Human-run, never part of the build.

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
    bg = spec["background"]
    head = (
        "Use your image generation tool to create ONE image and save it as sheet.png in the current directory.\n\n"
        f"The image: a square pixel-art sprite sheet, a strict {cols}x{rows} grid of equal square cells. Plain flat "
        f"solid {bg} background everywhere, NO cell borders, NO frames, NO text, NO labels, NO drop shadows. "
        "Every drawing is centered in its cell and never crosses into neighbouring cells. "
        f"Consistent style for the whole sheet: {spec['style']}\n\n"
    )
    if "animations" in spec:
        # One row per animation, frames left to right (sprites, ADR-0237).
        anims = list(spec["animations"].items())
        if len(anims) > rows:
            sys.exit(f"{len(anims)} animations do not fit {rows} rows")
        lines = [f"Row {r + 1} ({name}): {cols} animation frames left to right, same size and position in every "
                 f"cell so the frames play back as a flipbook: {desc}" for r, (name, desc) in enumerate(anims)]
        return head + "\n".join(lines) + "\n"
    cells = list(spec["cells"].items())
    if len(cells) > cols * rows:
        sys.exit(f"{len(cells)} cells do not fit a {cols}x{rows} grid")
    lines = [f"{i + 1} {item}: {desc}" for i, (item, desc) in enumerate(cells)]
    if len(cells) < cols * rows:
        lines.append(f"{len(cells) + 1}-{cols * rows}: leave empty (background only)")
    return (
        head + "Each icon fills about 80% of its cell.\n\nCells in reading order (row by row, left to right):\n"
        + "\n".join(lines) + "\n"
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
    if "animations" in spec:
        print("\nsprites.yaml rows:")
        for r, name in enumerate(spec["animations"]):
            print(f"  {name}: {{ from: {spec['name']}, row: {r}, frames: {cols} }}")
    else:
        print("    cells: [" + ", ".join(spec["cells"]) + "]")


if __name__ == "__main__":
    main()
