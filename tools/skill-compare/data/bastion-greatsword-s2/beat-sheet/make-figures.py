#!/usr/bin/env python3
"""Draw the stick figures of beat-sheet.md (top / side / front) with PIL.
usage: python3 -I make-figures.py   (run in this directory; writes beat-NN.png and swing-N.png, overview-top.png)
The waypoints below ARE the sheet's tip column (caster-local forward,right,up in blocks, blade ~2.0). Edit both together.
"""
import math, os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
# id, title, t0, t1, swing, tip waypoints, yaw0, yaw1 (deg, + = caster's left), hip drop (blocks), status of the path
BEATS = [
    (0, "構え", 0, 3, 0, [(-1.7, 0.6, 0.05)], -30, -30, 0.2),
    (1, "沈み込み・捻り", 3, 5, 0, [(-1.7, 0.6, 0.05), (-2.0, 0.0, 0.9)], -30, 90, 0.35),
    (2, "溜め保持", 5, 11, 0, [(-2.0, 0.0, 0.9)], 90, 90, 0.35),
    (3, "輪1 発生", 11, 13, 1, [(-2.0, 0.0, 0.9), (0.0, -2.0, 0.8), (2.0, 0.0, 0.8)], 90, 0, 0.35),
    (4, "輪1 接触・残像", 13, 21, 1, [(2.0, 0.0, 0.8)], 0, 0, 0.35),
    (5, "輪1 収束", 21, 24, 1, [(2.0, 0.0, 0.8), (0.0, 2.0, 0.5), (-1.8, 0.6, 0.05)], 0, 0, 0.2),
    (6, "立ち直り", 24, 28, 0, [(-1.8, 0.6, 0.05)], 0, 0, 0.0),
    (7, "背向けの捻り", 28, 30, 0, [(-1.8, 0.6, 0.05)], 0, 120, 0.2),
    (8, "輪2 発生・接触", 30, 38, 2, [(-1.8, 0.6, 0.05), (0.0, -2.0, 0.6), (2.0, 0.0, 0.8)], 120, 0, 0.35),
    (9, "輪2 収束", 38, 41, 2, [(2.0, 0.0, 0.8), (0.0, 2.0, 0.5), (-1.8, 0.6, 0.05)], 0, 0, 0.2),
    (10, "掲げ", 41, 43, 0, [(-1.8, 0.6, 0.05), (-1.9, 0.0, 0.7), (-1.7, 0.0, 1.4)], 0, -90, 0.0),
    (11, "頭上保持", 43, 51, 0, [(-1.7, 0.0, 1.4), (-1.6, 0.0, 1.7)], -90, -90, 0.0),
    (12, "縦斬り", 51, 54, 3, [(-1.6, 0.0, 1.7), (0.0, 0.0, 2.8), (2.0, 0.0, 0.5)], -90, 0, 0.3),
    (13, "低い突き抜け", 54, 62, 3, [(2.0, 0.0, 0.5), (2.2, 0.0, 0.1)], 0, 0, 0.5),
    (14, "突き立て保持", 62, 75, 0, [(2.2, 0.0, 0.1)], 0, 0, 0.5),
]
SWING_BEATS = {1: [2, 3, 4, 5], 2: [7, 8, 9], 3: [11, 12, 13]}
CW_ARC = {1: "上から見て 時計回り(CW) / 画面 CW", 2: "上から見て 時計回り(CW) / 画面 CW", 3: "上から見て none(縦面) / 画面 CW"}

W = 380
try:
    FONT = ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 15)
    FONTS = ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 12)
except Exception:
    FONT = FONTS = ImageFont.load_default()


def arrow(d, a, b, col, w=3):
    d.line([a, b], fill=col, width=w)
    ang = math.atan2(b[1] - a[1], b[0] - a[0])
    for s in (+2.6, -2.6):
        d.line([b, (b[0] - 11 * math.cos(ang + s * 0.2 + (0 if s > 0 else 0)) , b[1] - 11 * math.sin(ang + s * 0.2))], fill=col, width=w)


def proj(view, p):
    f, r, u = p
    if view == "top":      # forward up on screen, right = right
        return (W / 2 + r * 70, W / 2 + 20 - f * 70)
    if view == "side":     # camera on the caster's right: forward = screen right, up = up
        return (W / 2 + f * 70, W - 60 - u * 70)
    return (W / 2 - r * 70, W - 60 - u * 70)   # front: seen from the enemy, caster's right = screen left


def figure(d, view, yaw, hip, lean=0):
    ink = (40, 40, 40)
    if view == "top":
        cx, cy = W / 2, W / 2 + 20
        d.ellipse([cx - 14, cy - 14, cx + 14, cy + 14], outline=ink, width=3)
        a = math.radians(yaw)    # + yaw = toward caster's left = screen left
        d.line([(cx, cy), (cx - 26 * math.sin(a), cy - 26 * math.cos(a))], fill=(200, 40, 40), width=3)
        d.text((cx + 18, cy + 8), "胸の向き", fill=(200, 40, 40), font=FONTS)
        return
    base = W - 60
    h = 1.8 - hip
    x0 = W / 2
    # side: lean forward by `lean`; front: plain
    def pt(fwd, up):
        return (x0 + (fwd * 70 if view == "side" else 0), base - up * 70)
    pts = [pt(0, 0), pt(0, 0.9 - hip * 0.5), pt(0.15 * (1 + hip), h - 0.25), pt(0.2 * (1 + hip), h)]
    d.line(pts[:3], fill=ink, width=4)
    hx, hy = pts[3]
    d.ellipse([hx - 9, hy - 9, hx + 9, hy + 9], outline=ink, width=3)
    d.line([(x0 - 22, base), (x0 + 22, base)], fill=(120, 120, 120), width=2)


def panel(beat_list, view, title, label_every=True):
    im = Image.new("RGB", (W, W), (250, 250, 246))
    d = ImageDraw.Draw(im)
    d.text((8, 6), title, fill=(0, 0, 0), font=FONT)
    # grid
    for k in range(-3, 4):
        c = (225, 225, 220)
        if view == "top":
            d.line([(W / 2 + k * 70, 30), (W / 2 + k * 70, W)], fill=c); d.line([(0, W / 2 + 20 + k * 70), (W, W / 2 + 20 + k * 70)], fill=c)
    first = beat_list[0]
    figure(d, view, first[6], first[8])
    pts_all = []
    for b in beat_list:
        pts_all.extend(b[5])
    n = max(len(pts_all) - 1, 1)
    # tip path, colour from blue (start) to red (end)
    prev = None
    k = 0
    for b in beat_list:
        for p in b[5]:
            q = proj(view, p)
            if prev is not None and (abs(q[0] - prev[0]) + abs(q[1] - prev[1])) > 1:
                t = k / n
                col = (int(60 + 180 * t), 60, int(220 - 180 * t))
                arrow(d, prev, q, col)
            prev = q
            k += 1
    for b in beat_list:
        q = proj(view, b[5][-1])
        d.ellipse([q[0] - 4, q[1] - 4, q[0] + 4, q[1] + 4], fill=(30, 30, 30))
        d.text((q[0] + 6, q[1] - 14), "t%d" % b[3], fill=(30, 30, 30), font=FONTS)
    s = proj(view, beat_list[0][5][0])
    d.ellipse([s[0] - 6, s[1] - 6, s[0] + 6, s[1] + 6], outline=(0, 120, 0), width=3)
    d.text((s[0] + 8, s[1] + 4), "start t%d" % beat_list[0][2], fill=(0, 120, 0), font=FONTS)
    lab = {"top": "真上 (上=前, 右=術者の右)", "side": "横 (前=画面右)", "front": "正面 (敵側から, 術者の右=画面左)"}[view]
    d.text((8, W - 22), lab, fill=(80, 80, 80), font=FONTS)
    return im


def sheet(beat_list, title):
    out = Image.new("RGB", (W * 3, W + 30), (250, 250, 246))
    for i, v in enumerate(("top", "side", "front")):
        out.paste(panel(beat_list, v, "", True), (i * W, 30))
    d = ImageDraw.Draw(out)
    d.text((8, 6), title, fill=(0, 0, 0), font=FONT)
    return out


def main():
    for b in BEATS:
        t = "B%d %s  t%d-%d  yaw %d→%d  (推定 [I]: 参照から読んだ仮説)" % (b[0], b[1], b[2], b[3], b[6], b[7])
        sheet([b], t).save(os.path.join(HERE, "beat-%02d.png" % b[0]))
    byid = {b[0]: b for b in BEATS}
    for s, ids in SWING_BEATS.items():
        sheet([byid[i] for i in ids], "スイング%d  %s  (青=始点→赤=終点)" % (s, CW_ARC[s])).save(os.path.join(HERE, "swing-%d.png" % s))


if __name__ == "__main__":
    main()
