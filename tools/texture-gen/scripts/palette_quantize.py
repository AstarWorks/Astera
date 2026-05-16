#!/usr/bin/env python3
"""Resize + palette-quantize PNGs for Minecraft texture commit.

Usage:
    palette_quantize.py FILE [FILE...] --colors N --size W,H --out DIR
                        [--background remove] [--alpha-threshold T]

Pipeline per file:
    1. Open as RGBA
    2. (optional) Remove near-white / near-solid background → transparency
    3. Resize to (W, H) using NEAREST when downscaling small ratios,
       LANCZOS when downscaling large ratios (>4x)
    4. Quantize to N colors preserving alpha
    5. Save as PNG to DIR

Boundary: human-run, not part of Astera build/runtime.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image


def _parse_size(s: str) -> tuple[int, int]:
    try:
        w, h = (int(x) for x in s.split(","))
        return w, h
    except Exception as exc:
        raise argparse.ArgumentTypeError(
            f"--size must be W,H integers (got {s!r})"
        ) from exc


def _remove_background(img: Image.Image, threshold: int) -> Image.Image:
    """Make pixels with alpha < threshold or near-pure-black/white transparent.

    Conservative: only touches pixels at the literal corners' color if they
    look like a flat fill. Avoids destroying texture pixels.
    """
    px = img.load()
    w, h = img.size
    corner_colors = {px[0, 0], px[w - 1, 0], px[0, h - 1], px[w - 1, h - 1]}
    # Only act if all four corners agree on a "background color"
    if len(corner_colors) == 1:
        bg = next(iter(corner_colors))
        r, g, b, a = bg
        # Treat as background if it's near solid (no semi-transparency)
        if a > 240:
            new = Image.new("RGBA", (w, h))
            for y in range(h):
                for x in range(w):
                    p = px[x, y]
                    if (
                        abs(p[0] - r) < 8
                        and abs(p[1] - g) < 8
                        and abs(p[2] - b) < 8
                    ):
                        new.putpixel((x, y), (0, 0, 0, 0))
                    else:
                        new.putpixel((x, y), p)
            return new
    return img


def _resize(img: Image.Image, target: tuple[int, int]) -> Image.Image:
    w, h = img.size
    tw, th = target
    if w == tw and h == th:
        return img
    ratio = max(w / tw, h / th)
    # Large downscale → LANCZOS to preserve detail before nearest-snap;
    # small ratio → NEAREST to keep crisp pixel edges.
    resample = Image.LANCZOS if ratio > 4 else Image.NEAREST
    return img.resize(target, resample)


def _quantize(img: Image.Image, n_colors: int) -> Image.Image:
    # Split alpha; quantize RGB only; recombine.
    if img.mode != "RGBA":
        img = img.convert("RGBA")
    alpha = img.getchannel("A")
    rgb = img.convert("RGB")
    quantized = rgb.quantize(colors=n_colors, method=Image.MEDIANCUT).convert(
        "RGBA"
    )
    quantized.putalpha(alpha)
    return quantized


def process_file(
    src: Path,
    out_dir: Path,
    size: tuple[int, int],
    colors: int,
    remove_bg: bool,
    alpha_threshold: int,
) -> Path:
    img = Image.open(src).convert("RGBA")
    if remove_bg:
        img = _remove_background(img, alpha_threshold)
    img = _resize(img, size)
    img = _quantize(img, colors)
    out_path = out_dir / src.name
    img.save(out_path, "PNG", optimize=True)
    return out_path


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("files", nargs="+", type=Path, help="input PNG(s)")
    p.add_argument("--colors", type=int, default=32, help="palette size (≤256)")
    p.add_argument(
        "--size",
        type=_parse_size,
        required=True,
        help="target size as W,H (e.g. 32,32)",
    )
    p.add_argument("--out", type=Path, required=True, help="output dir")
    p.add_argument(
        "--background",
        choices=["keep", "remove"],
        default="keep",
        help="remove near-corner-color background as transparency",
    )
    p.add_argument(
        "--alpha-threshold",
        type=int,
        default=240,
        help="corner pixel alpha must exceed this to count as bg (0-255)",
    )
    args = p.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)

    for src in args.files:
        if not src.is_file():
            print(f"skip (not a file): {src}", file=sys.stderr)
            continue
        out_path = process_file(
            src=src,
            out_dir=args.out,
            size=args.size,
            colors=args.colors,
            remove_bg=(args.background == "remove"),
            alpha_threshold=args.alpha_threshold,
        )
        print(f"  {src.name} → {out_path}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
