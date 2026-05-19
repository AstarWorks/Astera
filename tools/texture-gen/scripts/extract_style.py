#!/usr/bin/env python3
"""Extract style anchor from a set of accepted texture PNGs.

Aggregates dominant palette + outline-color heuristic across input PNGs,
writes the result to a JSON file that future batches can read back to
constrain prompts for visual consistency.

Output schema:
    {
      "palette":   [[r,g,b], ...],   # top N by pixel frequency
      "outline":   [r,g,b],          # mode color along opaque/transparent boundary
      "n_colors":  N,
      "n_sources": K,
      "generated_from": ["path1.png", ...]
    }

Usage:
    extract_style.py FILE [FILE...] --out PATH [--n-colors N]

Boundary: human / skill-invoked. Not part of Astera build/runtime.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

import numpy as np
from PIL import Image


def _opaque_rgb_pixels(img: Image.Image) -> np.ndarray:
    if img.mode != "RGBA":
        img = img.convert("RGBA")
    arr = np.array(img)
    opaque = arr[arr[..., 3] > 0]
    return opaque[..., :3]


def _boundary_rgb_pixels(img: Image.Image) -> np.ndarray:
    """Pixels that are opaque AND have at least one transparent 4-neighbor.

    These form the visible silhouette outline. Returns Nx3 RGB array.
    """
    if img.mode != "RGBA":
        img = img.convert("RGBA")
    arr = np.array(img)
    alpha = arr[..., 3]
    opaque = alpha > 0
    if not opaque.any():
        return np.zeros((0, 3), dtype=np.uint8)
    transparent = ~opaque

    # Shift each direction by 1 with zero padding so the edge of the
    # canvas counts as a transparent neighbor (silhouette flush against
    # the edge still contributes outline pixels).
    h, w = alpha.shape
    has_t_neighbor = np.zeros_like(opaque)
    has_t_neighbor[:-1] |= transparent[1:]
    has_t_neighbor[1:] |= transparent[:-1]
    has_t_neighbor[:, :-1] |= transparent[:, 1:]
    has_t_neighbor[:, 1:] |= transparent[:, :-1]
    # Edge pixels treat the off-canvas as transparent
    edge = np.zeros_like(opaque)
    edge[0, :] = True
    edge[-1, :] = True
    edge[:, 0] = True
    edge[:, -1] = True
    has_t_neighbor |= edge

    boundary = opaque & has_t_neighbor
    return arr[boundary][..., :3]


def aggregate(
    sources: list[Path],
    n_colors: int,
) -> tuple[list[list[int]], list[int] | None]:
    palette_counter: Counter[tuple[int, int, int]] = Counter()
    outline_counter: Counter[tuple[int, int, int]] = Counter()

    for src in sources:
        img = Image.open(src)
        for px in _opaque_rgb_pixels(img):
            palette_counter[tuple(int(v) for v in px)] += 1
        for px in _boundary_rgb_pixels(img):
            outline_counter[tuple(int(v) for v in px)] += 1

    palette = [list(c) for c, _ in palette_counter.most_common(n_colors)]
    outline = list(outline_counter.most_common(1)[0][0]) if outline_counter else None
    return palette, outline


def main() -> int:
    p = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    p.add_argument("files", nargs="+", type=Path, help="accepted PNG(s)")
    p.add_argument("--out", type=Path, required=True, help="output .style.json path")
    p.add_argument(
        "--n-colors",
        type=int,
        default=16,
        help="number of palette colors to retain (default 16)",
    )
    args = p.parse_args()

    valid_sources: list[Path] = []
    for f in args.files:
        if f.is_file():
            valid_sources.append(f)
        else:
            print(f"skip (not a file): {f}", file=sys.stderr)
    if not valid_sources:
        print("error: no valid input files", file=sys.stderr)
        return 2

    palette, outline = aggregate(valid_sources, args.n_colors)
    if not palette:
        print("error: no opaque pixels found across inputs", file=sys.stderr)
        return 1

    payload = {
        "palette": palette,
        "outline": outline,
        "n_colors": len(palette),
        "n_sources": len(valid_sources),
        "generated_from": sorted(str(p) for p in valid_sources),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, indent=2) + "\n")
    print(f"wrote {args.out} (palette={len(palette)}, outline={outline}, sources={len(valid_sources)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
