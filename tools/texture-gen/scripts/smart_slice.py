#!/usr/bin/env python3
"""Smart slicer for AI-generated subject grids.

Two-mode slicer that does NOT trust math grid alignment:

  1. FRAME mode (primary): detect visible gray borders the AI was
     instructed to draw around each cell. Project gray-pixel density
     per row/column to find the border lines; use those as real cell
     boundaries.
  2. CC mode (fallback): if frame detection fails (too few cell lines
     found), fall back to connected-component detection: every distinct
     opaque region is a subject. Spatial clustering merges close
     fragments. Independent of math grid; works for irregular layouts.

The output PNGs go to --out/, one per detected cell, with the cell's
inner border stripped (in FRAME mode) so downstream palette_quantize.py
sees just the subject on black.

Boundary: human/skill-invoked only. Not part of Astera build/runtime.

Usage:
    smart_slice.py SHEET --out DIR [--rows N --cols M] [--prefix STR]
                   [--method auto|frame|cc] [--border-width N]
                   [--border-color-min N --border-color-max N]
                   [--cc-min-size N] [--cc-cluster-eps PX]
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Optional

import numpy as np
from PIL import Image
from scipy.ndimage import label as cc_label


def _is_border_color(rgb: np.ndarray, lo: int, hi: int) -> np.ndarray:
    """Boolean mask: True where pixel reads as gray frame color.

    Frame pixels have all 3 channels in [lo, hi] AND roughly equal
    (no strong color cast). This catches the AI-drawn gray border
    while ignoring colored subject pixels that happen to be mid-bright.
    """
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    in_range = (r >= lo) & (r <= hi) & (g >= lo) & (g <= hi) & (b >= lo) & (b <= hi)
    max_dev = np.maximum.reduce(
        [np.abs(r.astype(int) - g.astype(int)),
         np.abs(g.astype(int) - b.astype(int)),
         np.abs(r.astype(int) - b.astype(int))]
    )
    near_gray = max_dev <= 25
    return in_range & near_gray


def _find_lines(density: np.ndarray, expected_count: int) -> list[int]:
    """Given a 1D density signal (one value per row or column), return
    peak positions that look like cell-boundary lines.

    Two-stage detection:
      Pass 1: above-threshold runs collapsed to midpoints. Detects every
              edge of every frame stroke (thick borders produce paired
              peaks — top-of-stroke and bottom-of-stroke).
      Pass 2: merge close pairs. Two peaks within (image_dim / expected /
              4) pixels of each other are coalesced to their midpoint.
              This collapses each thick frame stroke into one "line"
              regardless of stroke thickness.
    """
    if density.size == 0 or expected_count < 2:
        return []
    threshold = float(density.mean() + density.std())
    above = density >= threshold
    # Pass 1: runs of above-threshold → midpoint
    peaks: list[int] = []
    i = 0
    n = density.size
    while i < n:
        if above[i]:
            j = i
            while j < n and above[j]:
                j += 1
            peaks.append((i + j - 1) // 2)
            i = j
        else:
            i += 1
    if not peaks:
        return []
    # Pass 2: merge close pairs into midpoints
    merge_eps = max(2, n // (expected_count * 4))
    merged: list[int] = [peaks[0]]
    for p in peaks[1:]:
        if p - merged[-1] <= merge_eps:
            merged[-1] = (merged[-1] + p) // 2
        else:
            merged.append(p)
    return merged


def _strip_border_pixels(cell: Image.Image, width: int) -> Image.Image:
    """Set the outer `width` pixels of `cell` to pure black so the
    palette_quantize bg-removal sweeps them away cleanly.

    This drops the gray frame from each cell crop. Returns a copy.
    """
    out = cell.copy()
    arr = np.array(out.convert("RGBA"))
    h, w = arr.shape[:2]
    if width <= 0 or h < 2 * width or w < 2 * width:
        return out
    arr[:width, :, :3] = 0
    arr[-width:, :, :3] = 0
    arr[:, :width, :3] = 0
    arr[:, -width:, :3] = 0
    return Image.fromarray(arr, mode="RGBA")


def _snap_to_nearest(
    anchor: int, candidates: list[int], tol: int
) -> tuple[int, bool]:
    """Snap `anchor` to the nearest value in `candidates` within `tol`
    pixels. Returns (snapped_position, snapped_flag). If no candidate
    within tol, returns (anchor, False)."""
    if not candidates:
        return anchor, False
    best = min(candidates, key=lambda c: abs(c - anchor))
    if abs(best - anchor) <= tol:
        return best, True
    return anchor, False


def slice_by_frame(
    img: Image.Image,
    rows: Optional[int],
    cols: Optional[int],
    border_lo: int,
    border_hi: int,
    border_width: int,
) -> Optional[list[tuple[str, Image.Image]]]:
    """Detect visible gray frames + snap to math anchors.

    Strategy:
      1. Over-detect candidate frame lines via density peaks.
      2. Compute math anchor positions assuming a uniform `rows`×`cols`
         grid. These are the "where each line *should* be" positions.
      3. For each math anchor, snap to the nearest detected frame line
         within a tolerance (~33% of cell width). If a snap target
         exists, use the detected position (real frame); otherwise fall
         back to the math anchor (AI didn't draw a frame there).
      4. Crop cells using the snapped positions. This combines math
         grid stability (always exact rows+1 / cols+1 positions) with
         frame-detect accuracy (positions track AI's actual drawing).

    Returns None if `rows`/`cols` not provided (we need them as anchors).
    """
    if rows is None or cols is None:
        return None

    rgb = np.array(img.convert("RGB"))
    mask = _is_border_color(rgb, border_lo, border_hi)

    row_density = mask.sum(axis=1).astype(float)
    col_density = mask.sum(axis=0).astype(float)

    # Over-detect: ask for 2× expected, we'll snap & filter
    h_candidates = _find_lines(row_density, (rows + 1) * 2)
    v_candidates = _find_lines(col_density, (cols + 1) * 2)

    H, W = img.size[1], img.size[0]
    anchor_h = [int(round(i * H / rows)) for i in range(rows + 1)]
    anchor_v = [int(round(i * W / cols)) for i in range(cols + 1)]

    # Tolerance: ~30% of cell dimension. Generous enough to catch AI's
    # offset (typically 5-15px on 125px cells = ~10%), tight enough
    # not to snap to noise far from where a frame should be.
    tol_h = max(2, H // (rows * 3))
    tol_v = max(2, W // (cols * 3))

    snapped_h: list[int] = []
    snap_count_h = 0
    for a in anchor_h:
        s, ok = _snap_to_nearest(a, h_candidates, tol_h)
        snapped_h.append(s)
        if ok:
            snap_count_h += 1
    snapped_v: list[int] = []
    snap_count_v = 0
    for a in anchor_v:
        s, ok = _snap_to_nearest(a, v_candidates, tol_v)
        snapped_v.append(s)
        if ok:
            snap_count_v += 1

    # Require at least half the lines to snap to a real frame.
    # If fewer than half snapped, the image probably doesn't have a
    # frame grid at all; let caller fall back to CC.
    if snap_count_h < (rows + 1) / 2 or snap_count_v < (cols + 1) / 2:
        return None

    # Ensure ascending order (snap can occasionally reorder if two
    # anchors mapped to the same candidate)
    snapped_h = sorted(set(snapped_h))
    snapped_v = sorted(set(snapped_v))
    if len(snapped_h) < rows + 1 or len(snapped_v) < cols + 1:
        # Snap collapsed too many anchors together — fall back.
        return None

    cells: list[tuple[str, Image.Image]] = []
    for r in range(rows):
        y0, y1 = snapped_h[r], snapped_h[r + 1]
        for c in range(cols):
            x0, x1 = snapped_v[c], snapped_v[c + 1]
            cell = img.crop((x0, y0, x1, y1))
            cell = _strip_border_pixels(cell, border_width)
            cells.append((f"r{r + 1:02}_c{c + 1:02}", cell))
    return cells


def _bbox_centroid(bbox: tuple[int, int, int, int]) -> tuple[float, float]:
    return ((bbox[0] + bbox[2]) / 2, (bbox[1] + bbox[3]) / 2)


def slice_by_cc(
    img: Image.Image,
    min_size: int,
    cluster_eps: int,
    rows: Optional[int],
    cols: Optional[int],
) -> list[tuple[str, Image.Image]]:
    """Connected-component slicer: detect each subject as its own
    cluster of opaque pixels. Doesn't need a grid.

    `min_size` discards specks smaller than that many pixels.
    `cluster_eps` merges blobs whose bounding boxes are closer than
    that many pixels (handles articulated subjects whose parts split
    after threshold).
    """
    rgb = np.array(img.convert("RGB"))
    # Subject = anything not near-black
    fg = rgb.sum(axis=2) >= 30
    labeled, count = cc_label(fg)
    if count == 0:
        return []
    sizes = np.bincount(labeled.ravel())
    blob_ids = [i for i in range(1, count + 1) if sizes[i] >= min_size]
    # Compute bbox per blob
    bboxes: list[tuple[int, int, int, int]] = []
    blob_to_pixels: dict[int, np.ndarray] = {}
    for b in blob_ids:
        ys, xs = np.where(labeled == b)
        bboxes.append((int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1))

    # Spatial cluster: any two blobs whose bboxes are within cluster_eps
    # in BOTH x and y get merged.
    n = len(bboxes)
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
            ax0, ay0, ax1, ay1 = bboxes[i]
            bx0, by0, bx1, by1 = bboxes[j]
            dx = max(0, max(bx0 - ax1, ax0 - bx1))
            dy = max(0, max(by0 - ay1, ay0 - by1))
            if dx <= cluster_eps and dy <= cluster_eps:
                union(i, j)

    clusters: dict[int, list[int]] = {}
    for i in range(n):
        root = find(i)
        clusters.setdefault(root, []).append(i)

    # Compute final cluster bbox = union of member bboxes
    final: list[tuple[int, int, int, int]] = []
    for members in clusters.values():
        x0 = min(bboxes[m][0] for m in members)
        y0 = min(bboxes[m][1] for m in members)
        x1 = max(bboxes[m][2] for m in members)
        y1 = max(bboxes[m][3] for m in members)
        final.append((x0, y0, x1, y1))

    # Sort by row-major (y first then x), using approximate row buckets
    # if `rows` was provided to enforce reading order.
    img_h = img.size[1]
    if rows:
        row_height = img_h / rows
        final.sort(key=lambda b: (int(b[1] // row_height), b[0]))
    else:
        final.sort(key=lambda b: (b[1], b[0]))

    cells: list[tuple[str, Image.Image]] = []
    for i, (x0, y0, x1, y1) in enumerate(final):
        if rows and cols:
            r_idx = (i // cols) + 1
            c_idx = (i % cols) + 1
            name = f"r{r_idx:02}_c{c_idx:02}"
        else:
            name = f"cc_{i + 1:03}"
        cells.append((name, img.crop((x0, y0, x1, y1))))
    return cells


def main() -> int:
    p = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    p.add_argument("sheet", type=Path, help="source grid PNG")
    p.add_argument("--out", type=Path, required=True, help="output dir")
    p.add_argument("--rows", type=int, default=None, help="expected rows (optional, helps frame mode)")
    p.add_argument("--cols", type=int, default=None, help="expected cols (optional)")
    p.add_argument("--prefix", default="tile", help="filename prefix")
    p.add_argument(
        "--method",
        choices=["auto", "frame", "cc"],
        default="auto",
        help="auto = try frame first, fall back to cc",
    )
    p.add_argument("--border-color-min", type=int, default=90, help="min RGB for frame pixel")
    p.add_argument("--border-color-max", type=int, default=160, help="max RGB for frame pixel")
    p.add_argument("--border-width", type=int, default=3, help="px of border to strip per cell")
    p.add_argument("--cc-min-size", type=int, default=80, help="discard CC blobs smaller than N px")
    p.add_argument("--cc-cluster-eps", type=int, default=8, help="merge CC blobs whose bboxes are within N px")
    args = p.parse_args()

    if not args.sheet.is_file():
        print(f"error: sheet not found: {args.sheet}", file=sys.stderr)
        return 2

    img = Image.open(args.sheet).convert("RGBA")
    args.out.mkdir(parents=True, exist_ok=True)

    cells: Optional[list[tuple[str, Image.Image]]] = None
    used_method = "?"

    if args.method in ("auto", "frame"):
        cells = slice_by_frame(
            img,
            rows=args.rows,
            cols=args.cols,
            border_lo=args.border_color_min,
            border_hi=args.border_color_max,
            border_width=args.border_width,
        )
        if cells is not None:
            used_method = "frame"

    if cells is None and args.method in ("auto", "cc"):
        cells = slice_by_cc(
            img,
            min_size=args.cc_min_size,
            cluster_eps=args.cc_cluster_eps,
            rows=args.rows,
            cols=args.cols,
        )
        used_method = "cc"

    if cells is None or len(cells) == 0:
        print("error: no cells detected by any method", file=sys.stderr)
        return 1

    for name, cell in cells:
        out_path = args.out / f"{args.prefix}_{name}.png"
        cell.save(out_path, "PNG")
    print(f"wrote {len(cells)} cells to {args.out} via {used_method}-detect")
    return 0


if __name__ == "__main__":
    sys.exit(main())
