#!/usr/bin/env python3
"""Slice a grid sheet image into individual tile PNGs.

Usage:
    slice_grid.py SHEET --rows N --cols M --out DIR [--prefix STR] [--trim]

Saves DIR/{prefix}_r{row:02}_c{col:02}.png for each cell.

Boundary: this script is run by humans; it is not invoked by Astera build
or runtime. See tools/texture-gen/README.md.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image


def slice_sheet(
    sheet_path: Path,
    rows: int,
    cols: int,
    out_dir: Path,
    prefix: str,
    trim: bool,
) -> list[Path]:
    img = Image.open(sheet_path).convert("RGBA")
    sheet_w, sheet_h = img.size

    tile_w = sheet_w // cols
    tile_h = sheet_h // rows

    if tile_w * cols != sheet_w or tile_h * rows != sheet_h:
        print(
            f"warning: sheet {sheet_w}x{sheet_h} does not divide evenly into "
            f"{cols}x{rows} grid (tile size {tile_w}x{tile_h}); "
            f"remainder columns/rows will be cropped.",
            file=sys.stderr,
        )

    out_dir.mkdir(parents=True, exist_ok=True)
    saved: list[Path] = []

    for r in range(rows):
        for c in range(cols):
            box = (c * tile_w, r * tile_h, (c + 1) * tile_w, (r + 1) * tile_h)
            tile = img.crop(box)
            if trim:
                tile = _trim_transparent(tile)
            out_path = out_dir / f"{prefix}_r{r + 1:02}_c{c + 1:02}.png"
            tile.save(out_path, "PNG")
            saved.append(out_path)

    return saved


def _trim_transparent(img: Image.Image) -> Image.Image:
    bbox = img.getbbox()
    return img.crop(bbox) if bbox else img


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("sheet", type=Path, help="grid sheet image (PNG/JPG)")
    p.add_argument("--rows", type=int, required=True)
    p.add_argument("--cols", type=int, required=True)
    p.add_argument("--out", type=Path, required=True, help="output directory")
    p.add_argument("--prefix", default="tile", help="filename prefix")
    p.add_argument(
        "--trim",
        action="store_true",
        help="trim transparent border around each tile",
    )
    args = p.parse_args()

    if not args.sheet.is_file():
        print(f"error: sheet not found: {args.sheet}", file=sys.stderr)
        return 2

    saved = slice_sheet(
        sheet_path=args.sheet,
        rows=args.rows,
        cols=args.cols,
        out_dir=args.out,
        prefix=args.prefix,
        trim=args.trim,
    )
    print(f"wrote {len(saved)} tiles to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
