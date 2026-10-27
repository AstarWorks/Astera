#!/usr/bin/env python3
"""skill-compare comparison engine: per-frame metrics + regression flags + the labelled sheet (called by lib/compare.mjs).

stdin  JSON  {"out": "/abs/sheet.png"|null, "cell": [w, h], "zoom": "fit"|"native", "crop": null|"auto"|[x0,y0,x1,y1], "title": str, "ticks": [..],
              "rows": [{"id","label","sub","pair": <index of the row this one is compared against>|null,
                        "frames": [{"tick": int, "path": str|null, "note": str}], "bg": [paths]}]}
stdout JSON  {"rows": [{"id", "native": [w, h]|null, "crop": {"norm": [..], "px": [..]}|null, "cells": [{"tick", "metrics": {...}|null, "boxes": {...}, "delta": {...}|null, "flags": [..]}]}], "sheetError": str|null}

Viewing never resamples when zoom = "native": every cell is the source pixels (optionally cropped to `crop`, fractions of the frame; "auto" = a square-in-fraction
window around the caster + fx boxes of the row, so it keeps the frame aspect) pasted 1:1; cells are as large as the biggest crop, smaller sources are padded, never scaled.
zoom = "fit" is the downscaled overview. The metrics below always use a <=320 px copy.

Metrics (all on the frame downscaled to <=320 px wide, so they are comparable between sources of different resolution):
  mean_luma / peak_luma   mean and 99th percentile of Rec.709 luma, 0-255
  fx_pct                  % of pixels that are emissive AND new vs the clip's median background: luma >= 190, or saturated and bright (max-min >= 140 and max >= 160)
  mover_h_pct             vertical extent of the rows that differ from the clip's median background, as % of the frame height
                          (the caster/weapon silhouette in the fixed-camera views; null when >50% of the frame changed = camera cut/move)
caster_bbox_pct         height of the caster's key-colour mask (compact saturated hair/outfit hues found in the pre-cast tick-0 frame of the same source,
                          or case.json caster_key) as % of the frame height; a stable, motion-independent framing measure. null when the mask is empty.
Regression flags (cell vs the paired row at the same tick): fx_pct / mean_luma / peak_luma down more than 20% (relative; ignored below
a floor so noise on near-black frames does not flag).
"""
import json
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

W = 320
DROP, FLOOR = 0.20, {"fx_pct": 0.3, "mean_luma": 8.0, "peak_luma": 30.0}


def load(path):
    try:
        im = Image.open(path).convert("RGB")
    except Exception:
        return None
    if im.width > W:
        im = im.resize((W, max(1, round(im.height * W / im.width))), Image.BILINEAR)
    return im


def luma(a):
    return 0.2126 * a[..., 0] + 0.7152 * a[..., 1] + 0.0722 * a[..., 2]


def background(paths):
    if not paths:
        return None
    step = max(1, len(paths) // 16)
    ims = [load(p) for p in paths[::step][:16]]
    ims = [i for i in ims if i is not None]
    if len(ims) < 3:
        return None
    size = ims[0].size
    return np.median(np.stack([np.asarray(i.resize(size)) for i in ims]).astype(np.float32), axis=0)


def metrics(im, bg):
    a = np.asarray(im).astype(np.float32)
    y = luma(a)
    mx, mn = a.max(axis=2), a.min(axis=2)
    emissive = (y >= 190) | (((mx - mn) >= 140) & (mx >= 160))
    if bg is not None and bg.shape == a.shape:
        emissive = emissive & (np.abs(a - bg).max(axis=2) > 40)  # only what the clip itself added over its own median background
    ys, xs = np.where(emissive)
    if len(ys) >= 20:
        h_, w_ = emissive.shape
        out_box = [round(float(np.percentile(xs, 1)) / w_, 4), round(float(np.percentile(ys, 1)) / h_, 4), round(float(np.percentile(xs, 99) + 1) / w_, 4), round(float(np.percentile(ys, 99) + 1) / h_, 4)]
    else:
        out_box = None
    out = {"mean_luma": round(float(y.mean()), 1), "peak_luma": round(float(np.percentile(y, 99)), 1), "fx_pct": round(float(emissive.mean() * 100), 2), "mover_h_pct": None}
    if bg is not None and bg.shape == a.shape:
        diff = np.abs(a - bg).max(axis=2) > 40
        if diff.mean() <= 0.5:
            rows = np.where(diff.sum(axis=1) >= max(2, 0.01 * diff.shape[1]))[0]
            if len(rows):
                out["mover_h_pct"] = round(float((rows.max() - rows.min() + 1) / a.shape[0] * 100), 1)
    return out, out_box


def hsv_planes(im):
    hsv = np.asarray(im.convert("HSV")).astype(np.float32)
    return hsv[..., 0] * 360 / 255, hsv[..., 1] / 255, hsv[..., 2] / 255


CELL = 3
CUT = 0.74  # rows below this fraction hold subtitles / hotbar / health bar


def biggest_cluster(m):
    """Largest 8-connected group of >=25%-filled CELLxCELL blocks of mask m (drops sparse particles / specks).
    Returns (pixels, (x0, y0, x1, y1)) in pixel coords, or None."""
    H, Wd = m.shape
    gh, gw = H // CELL, Wd // CELL
    if gh < 2 or gw < 2:
        return None
    fill = m[: gh * CELL, : gw * CELL].reshape(gh, CELL, gw, CELL).mean(axis=(1, 3)) >= 0.25
    seen = np.zeros_like(fill)
    best = None
    for y in range(gh):
        for x in range(gw):
            if not fill[y, x] or seen[y, x]:
                continue
            stack, cells = [(y, x)], []
            seen[y, x] = True
            while stack:
                cy, cx = stack.pop()
                cells.append((cy, cx))
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        ny, nx = cy + dy, cx + dx
                        if 0 <= ny < gh and 0 <= nx < gw and fill[ny, nx] and not seen[ny, nx]:
                            seen[ny, nx] = True
                            stack.append((ny, nx))
            if best is None or len(cells) > len(best):
                best = cells
    if not best:
        return None
    ys = [c[0] for c in best]
    xs = [c[1] for c in best]
    return len(best) * CELL * CELL, (min(xs) * CELL, min(ys) * CELL, (max(xs) + 1) * CELL, (max(ys) + 1) * CELL)


def caster_key(im, override=None):
    """Hue ranges (degrees) of the caster, taken from the pre-cast frame: the biggest compact saturated colour clusters (hair/outfit).
    Sky/water blue and grass/foliage green are never the caster; clusters wider than 45% or taller than 55% of the frame are scenery.
    `override` = [lo, hi] from case.json caster_key."""
    if override:
        return [(float(override[0]), float(override[1]))]
    h, s, v = hsv_planes(im)
    H, Wd = h.shape
    cand = (s >= 0.45) & (v >= 0.30)
    cand[: int(H * 0.10)] = False
    cand[int(H * CUT):] = False
    scored = []
    for b in range(36):
        if 90 <= b * 10 < 250:
            continue
        cl = biggest_cluster(cand & (h >= b * 10) & (h < b * 10 + 10))
        if not cl:
            continue
        px, (x0, y0, x1, y1) = cl
        if (x1 - x0) > 0.45 * Wd or (y1 - y0) > 0.55 * H:
            continue
        scored.append((px, b * 10))
    if not scored:
        return []
    scored.sort(reverse=True)
    return [(b, b + 10) for px, b in scored if px >= 0.4 * scored[0][0]][:3]


def caster_bbox(im, key):
    """Height/width of the caster's key-colour cluster as % of the frame (sparse fx pixels are ignored); null when there is none."""
    if not key:
        return None
    h, s, v = hsv_planes(im)
    m = np.zeros(h.shape, bool)
    for lo, hi in key:
        m |= (h >= lo) & (h < hi)
    m &= (s >= 0.40) & (v >= 0.25)
    m[int(h.shape[0] * CUT):] = False
    cl = biggest_cluster(m)
    if not cl:
        return {"caster_bbox_pct": None}
    px, (x0, y0, x1, y1) = cl
    return {"caster_bbox_pct": round(float((y1 - y0) / h.shape[0] * 100), 1), "caster_bbox_w_pct": round(float((x1 - x0) / h.shape[1] * 100), 1), "caster_mask_px": px,
            "_box": [round(float(x0) / h.shape[1], 4), round(float(y0) / h.shape[0], 4), round(float(x1) / h.shape[1], 4), round(float(y1) / h.shape[0], 4)]}


def compare(cur, prev):
    d, flags = {}, []
    for k in ("fx_pct", "mean_luma", "peak_luma"):
        a, b = cur.get(k), prev.get(k)
        if a is None or b is None:
            continue
        d[k] = round(a - b, 2)
        if k == "mover_h_pct":
            continue  # reported only (noisy on camera moves), never a regression flag
        if b >= FLOOR[k] and (b - a) / b > DROP:
            flags.append(f"{k} {b:g} -> {a:g} ({(a - b) / b * 100:+.0f}%)")
    return d, flags


def font(size):
    try:
        return ImageFont.load_default(size=size)
    except TypeError:
        return ImageFont.load_default()


MAX_SIDE = 16000  # px; a sheet bigger than this is refused instead of being downscaled


def native_size(path):
    try:
        with Image.open(path) as im:
            return im.size
    except Exception:
        return None


def auto_crop(boxes):
    """Square-in-fraction window (= the frame's own aspect) around the union of the caster / fx boxes, padded 30%, at least 25% of the frame."""
    boxes = [b for b in boxes if b]
    if not boxes:
        return None
    x0, y0 = min(b[0] for b in boxes), min(b[1] for b in boxes)
    x1, y1 = max(b[2] for b in boxes), max(b[3] for b in boxes)
    s = min(1.0, max((x1 - x0) * 1.3, (y1 - y0) * 1.3, 0.25))
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    bx, by = min(max(cx - s / 2, 0.0), 1 - s), min(max(cy - s / 2, 0.0), 1 - s)
    return [round(bx, 4), round(by, 4), round(bx + s, 4), round(by + s, 4)]


def crop_px(norm, size):
    w, h = size
    return [int(round(norm[0] * w)), int(round(norm[1] * h)), int(round(norm[2] * w)), int(round(norm[3] * h))]


def sheet(spec, rows, result):
    """Returns an error string (sheet not written) or None."""
    native = spec.get("zoom") == "native"
    ticks = spec["ticks"]
    gutter, head, strip, pad = 170, 26, 30, 4
    if native:
        cw = ch = 1
        for r in result:
            if r["native"]:
                b = crop_px(r["crop"]["norm"], r["native"]) if r["crop"] else [0, 0, *r["native"]]
                cw, ch = max(cw, b[2] - b[0]), max(ch, b[3] - b[1])
    else:
        cw, ch = spec.get("cell", [288, 162])
    width = gutter + len(ticks) * (cw + pad)
    height = 34 + head + len(rows) * (ch + strip + pad)
    if width > MAX_SIDE or height > MAX_SIDE:
        return f"native sheet would be {width}x{height} px (cell {cw}x{ch}); use fewer ticks / rows or a tighter crop (crop=x0,y0,x1,y1 fractions, or crop=auto)"
    rh = ch + strip + pad
    canvas = Image.new("RGB", (width, height), (18, 20, 26))
    dr = ImageDraw.Draw(canvas)
    f9, f11, f13 = font(10), font(11), font(14)
    dr.text((8, 8), spec.get("title", "") + ("   NATIVE PIXELS (no resampling)" if native else "   overview (downscaled; zoom=native for source pixels)"), fill=(235, 235, 240), font=f13)
    for ci, t in enumerate(ticks):
        dr.text((gutter + ci * (cw + pad) + 4, 34 + 6), f"t{t}", fill=(255, 200, 80), font=f13)
    for ri, row in enumerate(rows):
        y0 = 34 + head + ri * rh
        dr.text((8, y0 + 4), row["label"], fill=(235, 235, 240), font=f11)
        sub = list(row.get("sub", []))
        nat = result[ri]["native"]
        if nat:
            sub.append(f"source {nat[0]}x{nat[1]}")
            if result[ri]["crop"]:
                sub.append("crop px " + ",".join(str(v) for v in result[ri]["crop"]["px"]))
        for li, line in enumerate(sub):
            dr.text((8, y0 + 20 + li * 13), line, fill=(150, 156, 170), font=f9)
        for ci, fr in enumerate(row["frames"]):
            x0 = gutter + ci * (cw + pad)
            cell = result[ri]["cells"][ci]
            im = None
            if fr.get("path"):
                try:
                    im = Image.open(fr["path"]).convert("RGB")
                except Exception:
                    im = None
            if im is None:
                dr.rectangle([x0, y0, x0 + cw, y0 + ch], fill=(30, 33, 40), outline=(70, 74, 86))
                dr.text((x0 + 8, y0 + ch // 2 - 6), fr.get("note") or "no frame", fill=(200, 120, 120), font=f11)
            else:
                if result[ri]["crop"]:
                    im = im.crop(tuple(crop_px(result[ri]["crop"]["norm"], im.size)))
                if native:
                    canvas.paste(im, (x0, y0))
                    if im.width < cw or im.height < ch:
                        dr.rectangle([x0, y0, x0 + cw - 1, y0 + ch - 1], outline=(70, 74, 86))
                else:
                    canvas.paste(im.resize((cw, ch), Image.BILINEAR), (x0, y0))
            bad = bool(cell["flags"])
            if bad:
                dr.rectangle([x0, y0, x0 + cw - 1, y0 + ch - 1], outline=(235, 70, 70), width=3)
            m = cell["metrics"]
            cb = m.get("caster_bbox_pct") if m else None
            txt = "-" if not m else f"fx {m['fx_pct']:.1f}%  L {m['mean_luma']:.0f}/{m['peak_luma']:.0f}  caster {cb if cb is not None else '-'}%"
            dr.text((x0 + 2, y0 + ch + 2), txt, fill=(235, 235, 240), font=f9)
            if bad:
                dr.text((x0 + 2, y0 + ch + 14), "REGRESSION " + "; ".join(cell["flags"])[: cw // 5], fill=(255, 110, 110), font=f9)
            elif cell.get("delta"):
                dl = cell["delta"]
                dr.text((x0 + 2, y0 + ch + 14), f"vs prev  fx {dl.get('fx_pct', 0):+g}  L {dl.get('mean_luma', 0):+g}", fill=(120, 200, 140), font=f9)
    canvas.save(spec["out"], "PNG", optimize=False)
    return None


def main():
    spec = json.load(sys.stdin)
    rows = spec["rows"]
    crop_spec = spec.get("crop")
    result = []
    for row in rows:
        bg = background(row.get("bg") or [])
        kim = load(row["keyPath"]) if row.get("keyPath") else None
        key = caster_key(kim, row.get("hue")) if kim is not None else []
        cells, boxes = [], []
        nat = None
        for fr in row["frames"]:
            im = load(fr["path"]) if fr.get("path") else None
            if fr.get("path") and nat is None:
                nat = native_size(fr["path"])
            mt, bx = (metrics(im, bg) if im is not None else (None, None))
            cb = None
            if mt is not None:
                cbb = caster_bbox(im, key) or {"caster_bbox_pct": None}
                cb = cbb.pop("_box", None)
                mt.update(cbb)
            cells.append({"tick": fr["tick"], "metrics": mt, "boxes": {"caster": cb, "fx": bx}, "delta": None, "flags": []})
            boxes += [cb, bx]
        crop = None
        if crop_spec == "auto":
            norm = auto_crop(boxes)
        else:
            norm = crop_spec
        if norm and nat:
            crop = {"norm": norm, "px": crop_px(norm, nat)}
        result.append({"id": row["id"], "native": list(nat) if nat else None, "crop": crop, "casterKey": [list(k) for k in key], "cells": cells})
    for ri, row in enumerate(rows):
        p = row.get("pair")
        if p is None:
            continue
        for ci, cell in enumerate(result[ri]["cells"]):
            a, b = cell["metrics"], result[p]["cells"][ci]["metrics"]
            if a and b:
                cell["delta"], cell["flags"] = compare(a, b)
    err = sheet(spec, rows, result) if spec.get("out") else None
    json.dump({"rows": result, "sheetError": err}, sys.stdout)


main()
