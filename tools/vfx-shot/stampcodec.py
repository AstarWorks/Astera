"""Decoder for the tick stamp (stamp-pack boss bar title): shared by framestamp.py and director.py."""
import numpy as np

MOD = 1024
CELLS = 12
TOL = 40            # per-channel distance to the stamp colours (x264 4:4:4 round trip is within ~5)
STAMP_HALF_W = 100  # gui px around the screen centre that the title + bar can occupy
STAMP_H = 34        # gui px from the top that contain title + bar
MASK_HALF_W = 100
MASK_TOP_H = 26     # rows blanked in the saved PNGs (title text 3..12, bar 12..17, lerp slack)
MASK_BOTTOM_H = 26  # hotbar (22) + 1 px offset + slack, survival viewers only


def decode_stamp(img):
    """img: HxWx3 uint8 crop of the stamp region -> game time mod 1024, or None when no valid stamp is on screen."""
    r, g, b = (img[..., i].astype(np.int16) for i in range(3))
    magenta = (r >= 255 - TOL) & (g <= TOL) & (b >= 255 - TOL)
    cyan = (r <= TOL) & (g >= 255 - TOL) & (b >= 255 - TOL)
    col_m, col_c = magenta.sum(axis=0), cyan.sum(axis=0)
    xm = np.nonzero(col_m >= 3)[0]
    if len(xm) == 0:
        return None
    x0 = int(xm[0])
    xc = np.nonzero(col_c[x0 + 1:] >= 3)[0]
    if len(xc) == 0:
        return None
    pitch = int(xc[0]) + 1
    if pitch < 4 or x0 + pitch * CELLS > img.shape[1] + pitch // 2:
        return None
    union = (magenta | cyan)[:, x0:x0 + pitch * CELLS]
    rows = np.nonzero(union.sum(axis=1) >= pitch * 4)[0]
    if len(rows) == 0:
        return None
    ys = slice(int(rows[0]) + (len(rows) // 3), int(rows[-1]) + 1 - (len(rows) // 3))
    bits = []
    for i in range(CELLS):
        cx = x0 + int((i + 0.5) * pitch)
        half = max(1, pitch // 4)
        xs = slice(max(0, cx - half), min(img.shape[1], cx + half + 1))
        m, c = int(magenta[ys, xs].sum()), int(cyan[ys, xs].sum())
        area = magenta[ys, xs].size
        if max(m, c) < area * 0.6:
            return None
        bits.append(1 if c > m else 0)
    if bits[0] != 0 or bits[1] != 1:
        return None
    return sum(bit << i for i, bit in enumerate(bits[2:]))
