#!/usr/bin/env python3
"""Subject-first slicer for AI-generated grid images.

Abandons the "math grid" concept entirely. Instead of dividing the
image by `rows × cols` and hoping AI respected the boundaries, this
tool *finds each subject directly* via connected-component detection,
crops a square around each one, and outputs N independent PNGs.

Works on any AI-generated grid (with or without visible cell frames),
any subject count (handles AI giving you 96 or 103 instead of 100),
any subject size. The grid is the AI's organizational scaffold for
laying out subjects; once they exist on the canvas, the grid is
discarded as scaffolding usually should be.

Pipeline:
  1. Build a subject mask: pixel is "subject" if it's not the black
     background AND not the gray cell frame (if any). This single
     exclusion is why pure CC works here — without removing the
     frame mesh, every cell + its frame becomes one giant blob.
  2. Label connected components on the mask.
  3. Drop noise blobs and optionally merge spatially-close ones
     (handles articulated subjects whose pieces split at threshold).
  4. For each surviving blob:
       a. Take its bbox and centroid.
       b. Build a SQUARE crop centered on the centroid, side =
          max(bbox_w, bbox_h) + margin*2.
       c. Crop, resize to (size - 2*margin, size - 2*margin), paste
          centered onto a transparent canvas of `size`.
       d. Threshold bg-removal (R+G+B < dark_max → transparent).
       e. Optional palette quantization.
  5. Sort blobs in reading order (row-major if --rows/--cols given,
     else top-to-bottom then left-to-right). Name accordingly.

Boundary: human/skill-invoked. Not part of Astera build/runtime.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Optional

import numpy as np
from PIL import Image
from scipy.ndimage import binary_dilation, label as cc_label


def _parse_size(s: str) -> tuple[int, int]:
    try:
        w, h = (int(x) for x in s.split(","))
        return w, h
    except Exception as exc:
        raise argparse.ArgumentTypeError(
            f"--size must be W,H integers (got {s!r})"
        ) from exc


def _subject_mask(
    rgb: np.ndarray,
    dark_max: int,
    frame_lo: int,
    frame_hi: int,
    frame_color_tol: int,
) -> np.ndarray:
    """Pixels that look like subject content (not bg, not gray frame).

    Strategy — POSITION-based frame exclusion, not color-based:
      1. Build a "candidate frame pixel" mask using color (gray-in-range).
      2. Project the candidate mask onto rows and cols. Frame LINES
         show up as rows/cols where almost every pixel is a candidate.
         (Subject pixels that happen to be gray do not — they're
         scattered, not aligned in a line.)
      3. Mark a pixel as actual-frame only if its row OR column is a
         detected frame line.
      4. Subject mask = not-bg AND not-actual-frame.

    This means a gray-steel wand body is correctly KEPT as subject,
    because its rows/cols are not dominated by gray pixels (the wand
    is a 2D shape, not a 1D line).
    """
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    brightness = r.astype(int) + g.astype(int) + b.astype(int)
    not_bg = brightness >= dark_max

    # Candidate frame pixels (color-based, may include subject pixels)
    in_frame_range = (
        (r >= frame_lo) & (r <= frame_hi)
        & (g >= frame_lo) & (g <= frame_hi)
        & (b >= frame_lo) & (b <= frame_hi)
    )
    max_dev = np.maximum.reduce([
        np.abs(r.astype(int) - g.astype(int)),
        np.abs(g.astype(int) - b.astype(int)),
        np.abs(r.astype(int) - b.astype(int)),
    ])
    candidate_frame = in_frame_range & (max_dev <= frame_color_tol)

    # Position-based promotion: a row/col is a real frame line only if
    # candidate frame pixels cover most of it (>= 60%). Else the gray
    # pixels are subject content (a metal wand, a steel blade) — let
    # them through.
    H, W = candidate_frame.shape
    row_frac = candidate_frame.sum(axis=1) / W
    col_frac = candidate_frame.sum(axis=0) / H
    frame_threshold = 0.6
    row_is_frame = row_frac >= frame_threshold
    col_is_frame = col_frac >= frame_threshold

    is_actual_frame = candidate_frame & (
        row_is_frame[:, None] | col_is_frame[None, :]
    )
    return not_bg & ~is_actual_frame


def _detect_blobs(
    mask: np.ndarray,
    min_size: int,
    merge_eps: int,
    dilate: int,
) -> list[tuple[int, int, int, int, float, float]]:
    """Return list of (x0, y0, x1, y1, cx, cy) — bbox + centroid for
    each surviving (and possibly merged) blob.

    `dilate` (px): morphological dilation applied to the mask BEFORE
    CC labeling. Closes small internal gaps in a subject (dithering
    artifacts) so the subject becomes one blob. Does NOT bridge across
    the gray-frame gap because the frame was already excluded from the
    mask. Tune so that 2*dilate < typical between-subject gap.
    """
    if dilate > 0:
        mask = binary_dilation(mask, iterations=dilate)
    labeled, count = cc_label(mask)
    if count == 0:
        return []
    sizes = np.bincount(labeled.ravel())
    keep_ids = [i for i in range(1, count + 1) if sizes[i] >= min_size]
    if not keep_ids:
        return []

    raw: list[tuple[int, int, int, int, float, float]] = []
    for b in keep_ids:
        ys, xs = np.where(labeled == b)
        x0, x1 = int(xs.min()), int(xs.max()) + 1
        y0, y1 = int(ys.min()), int(ys.max()) + 1
        # Centroid weighted by uniform mask presence
        cx = float(xs.mean())
        cy = float(ys.mean())
        raw.append((x0, y0, x1, y1, cx, cy))

    if merge_eps <= 0:
        return raw

    # Spatial merge: union-find on bboxes that are close in both dims
    n = len(raw)
    parent = list(range(n))

    def find(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def union(i: int, j: int) -> None:
        ri, rj = find(i), find(j)
        if ri != rj:
            parent[ri] = rj

    for i in range(n):
        for j in range(i + 1, n):
            ax0, ay0, ax1, ay1, _, _ = raw[i]
            bx0, by0, bx1, by1, _, _ = raw[j]
            dx = max(0, max(bx0 - ax1, ax0 - bx1))
            dy = max(0, max(by0 - ay1, ay0 - by1))
            if dx <= merge_eps and dy <= merge_eps:
                union(i, j)

    groups: dict[int, list[int]] = {}
    for i in range(n):
        groups.setdefault(find(i), []).append(i)

    merged: list[tuple[int, int, int, int, float, float]] = []
    for members in groups.values():
        x0 = min(raw[m][0] for m in members)
        y0 = min(raw[m][1] for m in members)
        x1 = max(raw[m][2] for m in members)
        y1 = max(raw[m][3] for m in members)
        # Centroid = average of member centroids weighted by member area
        weights = [(raw[m][2] - raw[m][0]) * (raw[m][3] - raw[m][1]) for m in members]
        total_w = sum(weights) or 1
        cx = sum(raw[m][4] * w for m, w in zip(members, weights)) / total_w
        cy = sum(raw[m][5] * w for m, w in zip(members, weights)) / total_w
        merged.append((x0, y0, x1, y1, cx, cy))
    return merged


def _square_crop_bounds(
    cx: float, cy: float,
    bbox_w: int, bbox_h: int,
    img_w: int, img_h: int,
    pad_frac: float,
) -> tuple[int, int, int, int]:
    """Square bounds around (cx, cy), side = max(w, h) * (1 + pad_frac),
    clamped to image. Returns (x0, y0, x1, y1)."""
    side = int(round(max(bbox_w, bbox_h) * (1.0 + pad_frac)))
    half = side // 2
    x0 = int(round(cx)) - half
    y0 = int(round(cy)) - half
    x1 = x0 + side
    y1 = y0 + side
    # Shift to stay within image; if shifting both sides isn't enough,
    # clamp (resulting crop is smaller than requested square but still
    # contains the subject)
    if x0 < 0:
        x1 -= x0
        x0 = 0
    if y0 < 0:
        y1 -= y0
        y0 = 0
    if x1 > img_w:
        x0 -= x1 - img_w
        x1 = img_w
        x0 = max(0, x0)
    if y1 > img_h:
        y0 -= y1 - img_h
        y1 = img_h
        y0 = max(0, y0)
    return x0, y0, x1, y1


def _threshold_bg_remove(img: Image.Image, dark_max: int) -> Image.Image:
    """Pixels with R+G+B < dark_max become transparent."""
    if img.mode != "RGBA":
        img = img.convert("RGBA")
    arr = np.array(img)
    rgb_sum = arr[..., 0].astype(int) + arr[..., 1].astype(int) + arr[..., 2].astype(int)
    bg = rgb_sum < dark_max
    arr[bg, :] = 0
    return Image.fromarray(arr, mode="RGBA")


def _quantize(img: Image.Image, n_colors: int) -> Image.Image:
    if img.mode != "RGBA":
        img = img.convert("RGBA")
    alpha = img.getchannel("A")
    rgb = img.convert("RGB")
    quantized = rgb.quantize(colors=n_colors, method=Image.MEDIANCUT).convert("RGBA")
    quantized.putalpha(alpha)
    return quantized


def extract(
    src_path: Path,
    out_dir: Path,
    prefix: str,
    size: tuple[int, int],
    rows: Optional[int],
    cols: Optional[int],
    margin: int,
    dark_max: int,
    frame_lo: int,
    frame_hi: int,
    frame_color_tol: int,
    min_blob: int,
    merge_eps: int,
    dilate: int,
    pad_frac: float,
    colors: int,
    do_quantize: bool,
) -> int:
    """Extract subjects from src_path → out_dir. Returns count saved."""
    img = Image.open(src_path).convert("RGB")
    rgb = np.array(img)
    img_h, img_w = rgb.shape[:2]

    full_mask = _subject_mask(rgb, dark_max, frame_lo, frame_hi, frame_color_tol)

    if rows and cols:
        # Per-cell CC: walk the math grid, but for each cell apply mask
        # + CC to find ONLY that cell's subject. No cross-cell merging.
        # Math grid is the assignment scaffold; subject mask + per-cell
        # bbox-from-blobs do the actual subject crop.
        row_h = img_h / rows
        col_w = img_w / cols
        blobs = []
        order = []
        for r_idx in range(rows):
            for c_idx in range(cols):
                cy0 = int(r_idx * row_h)
                cy1 = int((r_idx + 1) * row_h)
                cx0 = int(c_idx * col_w)
                cx1 = int((c_idx + 1) * col_w)
                cell_mask = full_mask[cy0:cy1, cx0:cx1]
                if dilate > 0:
                    cell_mask = binary_dilation(cell_mask, iterations=dilate)
                labeled, count = cc_label(cell_mask)
                if count == 0:
                    continue
                sizes = np.bincount(labeled.ravel())
                # Use UNION of all blobs >= min_blob within the cell as the
                # subject bbox (handles articulated subjects without
                # needing dilation-based merging across the whole image).
                valid_ids = [i for i in range(1, count + 1) if sizes[i] >= min_blob]
                if not valid_ids:
                    continue
                keep_mask = np.isin(labeled, valid_ids)
                ys, xs = np.where(keep_mask)
                if len(xs) == 0:
                    continue
                bx0, bx1 = int(xs.min()) + cx0, int(xs.max()) + cx0 + 1
                by0, by1 = int(ys.min()) + cy0, int(ys.max()) + cy0 + 1
                cx = float(xs.mean()) + cx0
                cy = float(ys.mean()) + cy0
                blobs.append((bx0, by0, bx1, by1, cx, cy))
                order.append((r_idx, c_idx))
    else:
        # No grid hint: fall back to global CC + ordering by position
        blobs_global = _detect_blobs(full_mask, min_blob, merge_eps, dilate)
        if not blobs_global:
            return 0
        blobs_global.sort(key=lambda b: (b[5], b[4]))
        blobs = blobs_global
        order = []

    out_dir.mkdir(parents=True, exist_ok=True)
    tw, th = size
    inner_w = max(1, tw - 2 * margin)
    inner_h = max(1, th - 2 * margin)

    for idx, (x0, y0, x1, y1, cx, cy) in enumerate(blobs):
        # Square crop around centroid
        bbox_w = x1 - x0
        bbox_h = y1 - y0
        sx0, sy0, sx1, sy1 = _square_crop_bounds(
            cx, cy, bbox_w, bbox_h, img_w, img_h, pad_frac
        )
        # Crop from source AND from mask in the same region — then use
        # the mask to zero out non-subject pixels (gray frame, etc.)
        # so they don't survive into the output.
        crop_rgb = np.array(img.crop((sx0, sy0, sx1, sy1)).convert("RGBA"))
        crop_mask = full_mask[sy0:sy1, sx0:sx1]
        crop_rgb[~crop_mask, :3] = 0  # frame / bg → black (then bg-removed)
        crop_rgb[~crop_mask, 3] = 0   # and alpha 0 directly
        crop = Image.fromarray(crop_rgb, mode="RGBA")

        # Resize subject to fit inside (inner_w, inner_h) preserving aspect
        cw, ch = crop.size
        scale = min(inner_w / cw, inner_h / ch)
        new_w = max(1, int(round(cw * scale)))
        new_h = max(1, int(round(ch * scale)))
        resample = Image.LANCZOS if scale < 1 else Image.NEAREST
        resized = crop.resize((new_w, new_h), resample)

        # Paste centered on transparent canvas of `size`
        canvas = Image.new("RGBA", size, (0, 0, 0, 0))
        px = (tw - new_w) // 2
        py = (th - new_h) // 2
        canvas.paste(resized, (px, py), resized)

        # Final bg sweep (handles black pixels that LANCZOS may have
        # introduced at subject edges from anti-aliasing)
        canvas = _threshold_bg_remove(canvas, dark_max)

        if do_quantize:
            canvas = _quantize(canvas, colors)

        # Name
        if rows and cols and idx < len(order):
            r_idx, c_idx = order[idx]
            name = f"r{r_idx + 1:02}_c{c_idx + 1:02}"
        else:
            name = f"subj_{idx + 1:03}"

        canvas.save(out_dir / f"{prefix}_{name}.png", "PNG", optimize=True)

    return len(blobs)


def main() -> int:
    p = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    p.add_argument("source", type=Path, help="source grid PNG")
    p.add_argument("--out", type=Path, required=True, help="output dir")
    p.add_argument("--prefix", default="tile", help="filename prefix")
    p.add_argument(
        "--size",
        type=_parse_size,
        default=(32, 32),
        help="target tile size W,H (default 32,32)",
    )
    p.add_argument(
        "--rows",
        type=int,
        default=None,
        help="expected rows for reading-order naming (optional)",
    )
    p.add_argument(
        "--cols",
        type=int,
        default=None,
        help="expected cols for reading-order naming (optional)",
    )
    p.add_argument("--margin", type=int, default=2, help="inner padding px (default 2)")
    p.add_argument(
        "--dark-max",
        type=int,
        default=30,
        help="pixels with R+G+B < this count as background black (default 30)",
    )
    p.add_argument(
        "--frame-lo",
        type=int,
        default=80,
        help="gray-frame brightness lower bound per channel (default 80)",
    )
    p.add_argument(
        "--frame-hi",
        type=int,
        default=170,
        help="gray-frame brightness upper bound per channel (default 170)",
    )
    p.add_argument(
        "--frame-color-tol",
        type=int,
        default=20,
        help="max channel-difference for 'gray' classification (default 20)",
    )
    p.add_argument(
        "--min-blob",
        type=int,
        default=120,
        help="discard blobs smaller than N px (default 120 — tuned for ~125px cells)",
    )
    p.add_argument(
        "--merge-eps",
        type=int,
        default=0,
        help="merge blobs whose bboxes are within N px in BOTH dims (default 0; prefer --dilate instead)",
    )
    p.add_argument(
        "--dilate",
        type=int,
        default=2,
        help="morphological dilation iterations before CC. Closes within-subject pixel gaps from dithering. Tune so 2*dilate < between-subject gap (frame width + gutter). Default 2.",
    )
    p.add_argument(
        "--pad-frac",
        type=float,
        default=0.15,
        help="fraction of subject extent to add as breathing room before resize (default 0.15)",
    )
    p.add_argument(
        "--colors",
        type=int,
        default=16,
        help="palette size for quantization (default 16)",
    )
    p.add_argument(
        "--no-quantize",
        action="store_true",
        help="skip the palette quantization step",
    )
    args = p.parse_args()

    if not args.source.is_file():
        print(f"error: source not found: {args.source}", file=sys.stderr)
        return 2

    n = extract(
        src_path=args.source,
        out_dir=args.out,
        prefix=args.prefix,
        size=args.size,
        rows=args.rows,
        cols=args.cols,
        margin=args.margin,
        dark_max=args.dark_max,
        frame_lo=args.frame_lo,
        frame_hi=args.frame_hi,
        frame_color_tol=args.frame_color_tol,
        min_blob=args.min_blob,
        merge_eps=args.merge_eps,
        dilate=args.dilate,
        pad_frac=args.pad_frac,
        colors=args.colors,
        do_quantize=not args.no_quantize,
    )
    print(f"extracted {n} subjects to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
