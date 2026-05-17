#!/usr/bin/env python3
"""Resize + palette-quantize PNGs for Minecraft texture commit.

Usage:
    palette_quantize.py FILE [FILE...] --size W,H --out DIR
                        [--colors N] [--background keep|remove]
                        [--dark-max N] [--margin N]

Pipeline per file:
    1. Open as RGBA
    2. (if --background remove) threshold-based bg removal:
       any pixel with R+G+B < --dark-max becomes fully transparent.
       Robust against PNG compression / grid-line artifacts that broke
       the previous corner-equality heuristic.
    3. Detect content bbox via the alpha channel; crop tight.
       Scale to fit inside (size - 2*margin) preserving aspect ratio
       (LANCZOS for downscale, NEAREST for upscale).
       Paste centered onto a fresh transparent RGBA canvas of `size`.
    4. Quantize to N colors, preserving alpha.
    5. Save as PNG to DIR.

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


def _remove_background_threshold(img: Image.Image, dark_max: int) -> Image.Image:
    """Threshold-based bg removal: pixels with R+G+B < dark_max → fully transparent.

    Operates on the input resolution (no resize first) so faint subject edges
    survive better than they would after downscaling.
    """
    if img.mode != "RGBA":
        img = img.convert("RGBA")
    w, h = img.size
    out = Image.new("RGBA", (w, h))
    src_px = img.load()
    out_px = out.load()
    for y in range(h):
        for x in range(w):
            p = src_px[x, y]
            if p[0] + p[1] + p[2] < dark_max:
                out_px[x, y] = (0, 0, 0, 0)
            else:
                out_px[x, y] = p
    return out


def _fit_centered(
    img: Image.Image,
    target: tuple[int, int],
    margin: int,
) -> Image.Image:
    """Crop to content bbox, scale to fit (target - 2*margin) preserving aspect,
    paste centered onto a fresh transparent canvas of `target` size.

    If `img` has no opaque pixels (entirely transparent), returns a blank
    transparent canvas.
    """
    bbox = img.getbbox()
    if bbox is None:
        return Image.new("RGBA", target, (0, 0, 0, 0))
    cropped = img.crop(bbox)
    tw, th = target
    inner_w = max(1, tw - 2 * margin)
    inner_h = max(1, th - 2 * margin)
    iw, ih = cropped.size
    scale = min(inner_w / iw, inner_h / ih)
    new_w = max(1, int(round(iw * scale)))
    new_h = max(1, int(round(ih * scale)))
    resample = Image.LANCZOS if scale < 1 else Image.NEAREST
    resized = cropped.resize((new_w, new_h), resample)
    canvas = Image.new("RGBA", target, (0, 0, 0, 0))
    cx = (tw - new_w) // 2
    cy = (th - new_h) // 2
    canvas.paste(resized, (cx, cy), resized)
    return canvas


def _quantize(img: Image.Image, n_colors: int) -> Image.Image:
    if img.mode != "RGBA":
        img = img.convert("RGBA")
    alpha = img.getchannel("A")
    rgb = img.convert("RGB")
    quantized = rgb.quantize(colors=n_colors, method=Image.MEDIANCUT).convert("RGBA")
    quantized.putalpha(alpha)
    return quantized


def process_file(
    src: Path,
    out_dir: Path,
    size: tuple[int, int],
    colors: int,
    remove_bg: bool,
    dark_max: int,
    margin: int,
) -> Path:
    img = Image.open(src).convert("RGBA")
    if remove_bg:
        img = _remove_background_threshold(img, dark_max)
    img = _fit_centered(img, size, margin)
    img = _quantize(img, colors)
    out_path = out_dir / src.name
    img.save(out_path, "PNG", optimize=True)
    return out_path


def main() -> int:
    p = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
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
        help="threshold-based background removal (default: keep)",
    )
    p.add_argument(
        "--dark-max",
        type=int,
        default=30,
        help="when --background=remove, pixels with R+G+B<this become transparent (default 30)",
    )
    p.add_argument(
        "--margin",
        type=int,
        default=2,
        help="px of empty padding inside the target canvas (default 2)",
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
            dark_max=args.dark_max,
            margin=args.margin,
        )
        print(f"  {src.name} → {out_path}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
