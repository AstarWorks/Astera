#!/usr/bin/env python3
"""Draw the stick figures of beat-sheet.md (top / side / front) with PIL.
usage: python3 -I make-figures.py   (run in this directory; writes beat-NN.png, swing-N.png, overview.png)
The waypoints below ARE the sheet's tip column (caster-local forward,right,up in blocks; cast-time frame, origin = feet at the
START of the beat; shears length ~2.2). Edit both together. All values are [I] (hypothesis drawn from the reference).
Travel = caster displacement in blocks along cast-time forward during the beat (grey arrow).
A = held shears tip (red), L = remote shears / cutting line (orange; drawn length capped, real reach ~6 blocks).
"""
import math, os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
R = 1.5
RING = [(R * math.cos(math.radians(a)), R * math.sin(math.radians(a)), 1.0) for a in range(0, 361, 45)]  # top CW: forward->right->back->left
# id, title, t0, t1, swing, tips {name: [waypoints]}, yaw0, yaw1 (deg, + = caster's left), crouch (0..1), travel (blocks)
BEATS = [
    (0, "低い滑走(接近)", -9, -1, 0, {"A": [(-1.0, 0.3, 0.4)]}, 0, 0, 0.8, 3.0),
    (1, "直立・刃を前下に保持(三枚の刃が脇)", 0, 20, 0, {"A": [(1.2, 0.3, 0.6)]}, 0, 0, 0.0, 0.0),
    (2, "しゃがみ・刃を後ろ低く", 21, 28, 0, {"A": [(-1.4, 0.2, 0.4)]}, 0, 0, 0.6, 0.0),
    (3, "スイング1 旋回→前へ突き 接触t30", 29, 37, 1, {"A": RING + [(2.5, 0, 1.0)], "L": [(2.5, 0, 1.0), (3.4, 0, 1.0)]}, 0, 0, 0.5, 1.0),
    (4, "再しゃがみ・リセット", 38, 41, 0, {"A": [(-1.4, 0.2, 0.4)]}, 0, 0, 0.6, 0.0),
    (5, "スイング2 前進ブラー", 42, 44, 2, {"A": [(-1.4, 0.2, 0.4), (1.0, 0, 1.0)]}, 0, 0, 0.5, 1.5),
    (6, "スイング2 二重旋回→突き 接触t46", 45, 52, 2, {"A": RING + [(2.5, 0, 1.0)], "L": [(2.5, 0, 1.0), (3.4, 0, 1.0)]}, 0, 0, 0.5, 0.5),
    (7, "細線の残りと待機", 53, 57, 0, {"A": [(1.0, 0, 1.0)]}, 0, 0, 0.2, 0.0),
    (8, "刃を頭上へ(三日月)", 58, 63, 0, {"A": [(0.2, 0.3, 0.6), (0.2, 0.2, 3.0)]}, 0, -45, 0.0, 0.0),
    (9, "頭上で保持(先端は右上)", 64, 73, 0, {"A": [(0.8, 0.2, 2.9)]}, -45, -90, 0.0, 0.0),
    (10, "胸の前で水平に構える", 74, 77, 0, {"A": [(0.8, 0.2, 2.9), (1.9, 0, 1.2)]}, -90, 0, 0.0, 0.0),
    (11, "溜め(桃の輪が収縮)", 78, 108, 0, {"A": [(1.9, 0, 1.2)]}, 0, 0, 0.0, 0.0),
    (12, "発射(白い編み線)", 109, 109, 3, {"A": [(1.9, 0, 1.2), (2.5, 0, 1.2)], "L": [(2.5, 0, 1.2), (3.4, 0, 1.2)]}, 0, 0, 0.0, 0.0),
    (13, "カットイン(暗転)", 110, 111, 3, {"L": [(3.4, 0, 1.2)]}, 0, 0, 0.0, 0.0),
    (14, "接触・敵の胸を貫く t113", 112, 116, 3, {"L": [(3.4, 0, 1.2)]}, 0, 0, 0.0, 0.0),
    (15, "星形フレア・黄の円", 117, 124, 3, {"L": [(3.4, 0, 1.2)]}, 0, 0, 0.0, 0.0),
    (16, "暗転", 125, 127, 0, {}, 0, 0, 0.0, 0.0),
    (17, "仕上げ 花の爆発", 127, 137, 0, {"L": [(3.4, 0, 1.2)]}, 0, 0, 0.0, 0.0),
    (18, "余韻(遠隔のハサミが敵側に残る)", 138, 147, 0, {"L": [(3.4, 0, 1.2)]}, 0, 0, 0.0, 0.0),
]
SWING_BEATS = {1: [2, 3, 4], 2: [5, 6, 7], 3: [11, 12, 14]}
CONTACTS = [(30, "接触1 t30"), (46, "接触2 t46"), (113, "接触3 t113")]
COL = {"A": (200, 40, 40), "B": (40, 90, 210), "C": (120, 60, 160), "L": (230, 120, 0)}

W = 380
try:
    FONT = ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 15)
    FONTS = ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 12)
except Exception:
    FONT = FONTS = ImageFont.load_default()


def arrow(d, a, b, col, w=3):
    d.line([a, b], fill=col, width=w)
    ang = math.atan2(b[1] - a[1], b[0] - a[0])
    for s in (0.5, -0.5):
        d.line([b, (b[0] - 11 * math.cos(ang + s), b[1] - 11 * math.sin(ang + s))], fill=col, width=w)


def scale_for(beat_list):
    far = 0.0
    off = 0.0
    for b in beat_list:
        far = max(far, off + b[9] + 3.2)
        for pts in b[5].values():
            for p in pts:
                far = max(far, off + p[0] + 1.0)
        off += b[9]
    return 70 if far <= 4.6 else max(14, min(70, int(300 / far)))


def top_xy(f, r, sc):
    """top view: wide beats lay the travel axis horizontally (forward = screen right), others forward = up"""
    if sc < 70:
        return (50 + (f + 1.0) * sc, W / 2 + 20 - r * sc)
    return (W / 2 + r * sc, W / 2 + 20 - f * sc)


def side_xy(f, u, sc):
    if sc < 70:
        return (50 + (f + 1.0) * sc, W - 60 - u * sc)
    return (W / 2 + f * sc, W - 60 - u * sc)


def figure_side(d, sc, fwd_off, yaw, crouch, col=(40, 40, 40)):
    h = 1.8 - 0.9 * crouch
    lean = 0.55 * crouch
    face = math.cos(math.radians(yaw))
    face = 1 if face > 0.3 else (-1 if face < -0.3 else 0)
    x0, y0 = side_xy(fwd_off, 0, sc)
    hip = (x0, y0 - (0.9 - 0.45 * crouch) * sc)
    head = (x0 + (face if face else 0.0) * lean * sc, y0 - h * sc)
    d.line([(x0, y0), hip, head], fill=col, width=4)
    d.ellipse([head[0] - 9, head[1] - 9, head[0] + 9, head[1] + 9], outline=col, width=3)
    if face:
        arrow(d, (head[0], head[1] - 16), (head[0] + face * 22, head[1] - 16), (200, 40, 40), 2)
    else:
        d.text((head[0] + 12, head[1] - 22), "正面向き", fill=(200, 40, 40), font=FONTS)
    d.line([(x0 - 22, y0), (x0 + 22, y0)], fill=(120, 120, 120), width=2)


def panel(beat_list, view, title):
    im = Image.new("RGB", (W, W), (250, 250, 246))
    d = ImageDraw.Draw(im)
    d.text((8, 6), title, fill=(0, 0, 0), font=FONT)
    sc = scale_for(beat_list)
    wide = sc < 70
    if view == "top" and not wide:
        for k in range(-3, 4):
            c = (225, 225, 220)
            d.line([(W / 2 + k * sc, 30), (W / 2 + k * sc, W)], fill=c)
            d.line([(0, W / 2 + 20 + k * sc), (W, W / 2 + 20 + k * sc)], fill=c)
    off = 0.0
    label_y = {}
    for b in beat_list:
        yaw0, yaw1, crouch, travel = b[6], b[7], b[8], b[9]
        if view == "top":
            sx, sy = top_xy(off, 0, sc) if wide else top_xy(0, 0, sc)
            ex, ey = top_xy(off + travel, 0, sc) if wide else top_xy(0, 0, sc)
            for (cx, cy, yw, c) in ((sx, sy, yaw0, (150, 150, 150)), (ex, ey, yaw1, (40, 40, 40))):
                d.ellipse([cx - 13, cy - 13, cx + 13, cy + 13], outline=c, width=3)
                a = math.radians(yw)
                if wide:
                    v = (26 * math.cos(a), -26 * math.sin(a))
                else:
                    v = (-26 * math.sin(a), -26 * math.cos(a))
                d.line([(cx, cy), (cx + v[0], cy + v[1])], fill=(200, 40, 40), width=3)
            if travel > 0 and wide:
                arrow(d, (sx + 16, sy + 22), (ex - 16, ey + 22), (130, 130, 130), 4)
                d.text(((sx + ex) / 2 - 20, sy + 26), "%gブロック" % travel, fill=(110, 110, 110), font=FONTS)
        elif view == "side":
            figure_side(d, sc, off if wide else 0, yaw0, crouch, (150, 150, 150))
            if travel > 0 and wide:
                figure_side(d, sc, off + travel, yaw1, crouch)
                a = side_xy(off, 0.05, sc)
                bb = side_xy(off + travel, 0.05, sc)
                arrow(d, (a[0] + 16, a[1] + 12), (bb[0] - 16, bb[1] + 12), (130, 130, 130), 4)
            else:
                figure_side(d, sc, off if wide else 0, yaw1, crouch)
        else:
            x0 = W / 2
            base = W - 60
            h = 1.8 - 0.9 * crouch
            d.line([(x0, base), (x0, base - (0.9 - 0.45 * crouch) * sc), (x0, base - h * sc)], fill=(40, 40, 40), width=4)
            d.ellipse([x0 - 9, base - h * sc - 9, x0 + 9, base - h * sc + 9], outline=(40, 40, 40), width=3)
            d.line([(x0 - 22, base), (x0 + 22, base)], fill=(120, 120, 120), width=2)
        for name, pts in b[5].items():
            col = COL.get(name, (0, 0, 0))
            prev = None
            for p in pts:
                if view == "top":
                    q = top_xy(p[0] + off, p[1], sc) if wide else top_xy(p[0], p[1], sc)
                elif view == "side":
                    q = side_xy(p[0] + (off if wide else 0), p[2], sc)
                else:
                    q = (W / 2 - p[1] * sc, W - 60 - p[2] * sc)
                if prev is not None and abs(q[0] - prev[0]) + abs(q[1] - prev[1]) > 1:
                    arrow(d, prev, q, col)
                prev = q
            if prev is not None:
                d.ellipse([prev[0] - 5, prev[1] - 5, prev[0] + 5, prev[1] + 5], fill=col)
                # stack end labels so they never overlap
                key = (round(prev[0] / 60), name)
                n = label_y.get(key, 0)
                label_y[key] = n + 1
                d.text((prev[0] + 7, prev[1] - 14 - 13 * n), "%s t%d" % (name, b[3]), fill=col, font=FONTS)
        off += travel
    lab = {"top": "真上 (赤線=胸の向き, 灰=開始位置)", "side": "横 (前=画面右, 灰=開始)", "front": "正面 (敵側から, 術者の右=画面左)"}[view]
    if wide and view == "top":
        lab = "真上 (前=画面右, 赤線=胸の向き)"
    d.text((8, W - 22), lab + "  [I]", fill=(80, 80, 80), font=FONTS)
    return im


def sheet(beat_list, title):
    out = Image.new("RGB", (W * 3, W + 30), (250, 250, 246))
    for i, v in enumerate(("top", "side", "front")):
        out.paste(panel(beat_list, v, ""), (i * W, 30))
    ImageDraw.Draw(out).text((8, 6), title, fill=(0, 0, 0), font=FONT)
    return out


def overview():
    """Timeline of all beats (tick on x), staggered in 3 rows, contacts on a separate top row, legend below: no overlapping labels."""
    Wd, Ht = 1500, 570
    im = Image.new("RGB", (Wd, Ht), (250, 250, 246))
    d = ImageDraw.Draw(im)
    d.text((8, 6), "S3 全体タイムライン (横軸 tick -9..147)。バー=ビート、橙の縦線=接触 [I]", fill=(0, 0, 0), font=FONT)
    x0, sc = 40, 9.0
    X = lambda t: x0 + (t + 9) * sc
    for t in range(-9, 148, 10):
        d.line([(X(t), 70), (X(t), 250)], fill=(228, 228, 222))
        d.text((X(t) - 8, 252), str(t), fill=(110, 110, 110), font=FONTS)
    for t, lab in CONTACTS:
        d.line([(X(t), 60), (X(t), 250)], fill=(230, 120, 0), width=3)
        d.text((X(t) - 30, 38), lab, fill=(230, 120, 0), font=FONT)
    rowcol = [(200, 215, 240), (215, 235, 215), (240, 225, 200)]
    for i, b in enumerate(BEATS):
        row = i % 3
        xa, xb = X(b[2]), X(b[3] + 1)
        y = 80 + row * 55
        d.rectangle([xa, y, xb - 1, y + 38], fill=rowcol[row], outline=(60, 60, 60))
        d.text((xa + 3, y + 10), "B%d" % b[0], fill=(0, 0, 0), font=FONTS)
    for i, b in enumerate(BEATS):
        col, line = divmod(i, 10)
        d.text((40 + col * 480, 285 + line * 22), "B%d  t%d..%d  %s" % (b[0], b[2], b[3], b[1]), fill=(30, 30, 30), font=FONTS)
    d.text((8, Ht - 22), "術者-敵は約 2.5-3.5H = 約 4.5-6 ブロック [U: カメラで変わる]。突進は観察されず、細線と遠隔のハサミが敵まで届く [I]", fill=(80, 80, 80), font=FONTS)
    return im


def main():
    for b in BEATS:
        t = "B%d %s  t%d..%d  yaw %d→%d  移動%g  (推定 [I]; A赤=手持ち L橙=遠隔/切断線)" % (b[0], b[1], b[2], b[3], b[6], b[7], b[9])
        sheet([b], t).save(os.path.join(HERE, "beat-%02d.png" % b[0]))
    byid = {b[0]: b for b in BEATS}
    for s, ids in SWING_BEATS.items():
        sheet([byid[i] for i in ids], "スイング%d (ビート %s): 上から見て旋回は top CW (前→右→後→左) [I]" % (s, ",".join(str(i) for i in ids))).save(os.path.join(HERE, "swing-%d.png" % s))
    overview().save(os.path.join(HERE, "overview.png"))


if __name__ == "__main__":
    main()
