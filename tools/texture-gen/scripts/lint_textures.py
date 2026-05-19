#!/usr/bin/env python3
"""Post-quantize lint for Minecraft texture PNGs.

Validates each input PNG against the conventions in content/textures/README.md:

  - Mode is RGBA (alpha channel preserved)
  - Size matches --size (default 32,32)
  - Distinct color count <= --max-colors (default 32)
  - At least one opaque pixel (not entirely transparent)

Designed to run between palette_quantize.py and the vision-QA loop. PNGs
that fail lint should be auto-rejected without spending vision tokens.

Exit code:
  0  every file passed
  1  at least one file failed
  2  invalid arguments / missing input

Boundary: human / skill-invoked. Not part of Astera build/runtime.
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


def _distinct_color_count(img: Image.Image) -> int:
    """Count distinct (R,G,B) tuples among opaque pixels (alpha > 0).

    Rationale: palette_quantize.py preserves the source alpha channel
    after quantizing RGB, so anti-aliased edges (LANCZOS) produce many
    distinct (R,G,B,A) tuples even when the quantized palette has ≤32
    RGB colors. The MC pixel-art constraint is on the visible palette,
    so we count RGB among opaque pixels.
    """
    import numpy as np

    if img.mode != "RGBA":
        img = img.convert("RGBA")
    arr = np.array(img)
    opaque = arr[arr[..., 3] > 0]
    if opaque.size == 0:
        return 0
    rgb = opaque[..., :3]
    return len({tuple(p) for p in rgb.tolist()})


def _has_soft_alpha(img: Image.Image) -> bool:
    """True if the alpha channel contains any value other than 0 or 255.

    MC pixel-art textures should have binary alpha (fully transparent or
    fully opaque); semi-alpha indicates LANCZOS bleed that survived the
    final bg-removal sweep.
    """
    import numpy as np

    if img.mode != "RGBA":
        return False
    alpha = np.array(img.getchannel("A"))
    return bool(((alpha != 0) & (alpha != 255)).any())


def lint_file(
    path: Path,
    expected_size: tuple[int, int],
    max_colors: int,
    strict_alpha: bool,
) -> tuple[bool, list[str]]:
    """Returns (passed, list_of_failure_reasons)."""
    failures: list[str] = []

    try:
        img = Image.open(path)
    except Exception as exc:
        return False, [f"cannot open: {exc}"]

    if img.mode != "RGBA":
        failures.append(f"mode is {img.mode!r}, expected 'RGBA'")
        img = img.convert("RGBA")

    if img.size != expected_size:
        failures.append(
            f"size {img.size} != expected {expected_size}"
        )

    distinct = _distinct_color_count(img)
    if distinct > max_colors:
        failures.append(
            f"distinct opaque RGB count {distinct} > max {max_colors}"
        )

    alpha = img.getchannel("A")
    if alpha.getbbox() is None:
        failures.append("entirely transparent (no opaque pixels)")

    if strict_alpha and _has_soft_alpha(img):
        failures.append("alpha channel has semi-transparent values (expected binary 0/255)")

    return len(failures) == 0, failures


def main() -> int:
    p = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    p.add_argument("files", nargs="+", type=Path, help="PNG file(s) to lint")
    p.add_argument(
        "--size",
        type=_parse_size,
        default=(32, 32),
        help="expected size W,H (default 32,32)",
    )
    p.add_argument(
        "--max-colors",
        type=int,
        default=32,
        help="max distinct colors allowed (default 32)",
    )
    p.add_argument(
        "--quiet",
        action="store_true",
        help="only print failures, suppress per-file pass lines",
    )
    p.add_argument(
        "--strict-alpha",
        action="store_true",
        help="also fail if alpha channel has any value other than 0 or 255",
    )
    args = p.parse_args()

    total = 0
    failed = 0
    for src in args.files:
        if not src.is_file():
            print(f"FAIL {src}: not a file", file=sys.stderr)
            failed += 1
            total += 1
            continue
        ok, reasons = lint_file(src, args.size, args.max_colors, args.strict_alpha)
        total += 1
        if ok:
            if not args.quiet:
                print(f"PASS {src}")
        else:
            failed += 1
            print(f"FAIL {src}: {'; '.join(reasons)}", file=sys.stderr)

    print(
        f"\n{total - failed}/{total} passed"
        + (f", {failed} failed" if failed else ""),
        file=sys.stderr,
    )
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
