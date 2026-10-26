#!/usr/bin/env python3
"""Draw the stick figures of beat-sheet.md (top / side / front) with PIL.
usage: python3 -I make-figures.py   (run in this directory; writes beat-NN.png, swing-1.png, overview-top.png)
The waypoints below ARE the sheet's tip column (caster-local forward,right,up in blocks; cast-time frame, origin = feet at the
START of the beat; shears length ~2.2). Edit both together. All values are [I] (hypothesis drawn from the reference).
Travel = caster displacement in blocks along cast-time forward during the beat (grey arrow).
"""
import math, os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
# id, title, t0, t1, swing, tips {name: [waypoints]}, yaw0, yaw1 (deg, + = caster's left, 180 = facing backwards), crouch (0..1), travel (blocks)
BEATS = [
    (0, "低い滑走(接近)", -17, -1, 0, {"A": [(-1.3, 0.3, 0.35)]}, 0, 0, 0.9, 6.0),
    (1, "鋏を掲げる", 0, 1, 0, {"A": [(0.1, 0.4, 3.0)]}, -90, -90, 0.0, 0.0),
    (2, "逆手に反転・開く", 2, 2, 0, {"A": [(-1.3, 0.2, 0.15)], "B": [(1.5, 0.2, 0.2)], "C": [(0.6, 0.0, 2.3)]}, -90, 180, 0.4, 0.0),
    (3, "背向け低姿勢で保持", 3, 10, 0, {"A": [(-1.3, 0.2, 0.15)], "B": [(1.5, 0.2, 0.2)], "C": [(0.6, 0.0, 2.3)]}, 180, 180, 0.4, 0.0),
    (4, "突進発進", 11, 12, 1, {"B": [(1.5, 0.2, 0.2), (3.0, 0.2, 0.3)]}, 180, 180, 0.4, 5.0),
    (5, "接触・切り抜け(フラッシュで隠れる)", 13, 15, 1, {"L": [(0.0, 0.0, 1.2), (1.5, 0.0, 1.2), (3.0, 0.0, 1.2)]}, 180, 0, 0.4, 2.5),
    (6, "通過・減速・後ろへ突き", 15, 19, 1, {"A": [(-1.2, 0.0, 1.4), (-2.6, 0.0, 1.4)]}, 0, 0, 0.5, 2.5),
    (7, "突き出し保持", 20, 37, 0, {"A": [(-2.6, 0.0, 1.4)]}, 0, 0, 0.5, 0.0),
]
SWING_BEATS = {1: [4, 5, 6]}
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
        off += b[9]
    return 70 if far <= 4.6 else max(14, min(70, int(300 / far)))


def proj(view, p, sc, ox=0.0):
    f, r, u = p
    f += ox
    if view == "top":
        return (60 + (f + 0.5) * sc if sc < 70 else W / 2 + r * sc, (W / 2 + 20 - f * sc) if sc >= 70 else W / 2 + 20 - r * sc)
    if view == "side":
        return ((60 + (f + 0.5) * sc) if sc < 70 else W / 2 + f * sc, W - 60 - u * sc)
    return (W / 2 - r * sc, W - 60 - u * sc)


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
        d.text((head[0] + 12, head[1] - 22), "正面/カメラ向き", fill=(200, 40, 40), font=FONTS)
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
    for b in beat_list:
        yaw0, yaw1, crouch, travel = b[6], b[7], b[8], b[9]
        if view == "top":
            sx, sy = top_xy(off, 0, sc) if wide else top_xy(0, 0, sc)
            ex, ey = top_xy(off + travel, 0, sc) if wide else top_xy(0, 0, sc)
            for (cx, cy, yw, c) in ((sx, sy, yaw0, (150, 150, 150)), (ex, ey, yaw1, (40, 40, 40))):
                d.ellipse([cx - 13, cy - 13, cx + 13, cy + 13], outline=c, width=3)
                a = math.radians(yw)
                # forward = screen right if wide else up; +yaw = caster's left
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
        # tip paths
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
                d.text((prev[0] + 7, prev[1] - 14), "%s t%d" % (name, b[3]), fill=col, font=FONTS)
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


def main():
    for b in BEATS:
        t = "B%d %s  t%d..%d  yaw %d→%d  移動%g  (推定 [I]; A赤 B青 C紫 L橙=接触線)" % (b[0], b[1], b[2], b[3], b[6], b[7], b[9])
        sheet([b], t).save(os.path.join(HERE, "beat-%02d.png" % b[0]))
    byid = {b[0]: b for b in BEATS}
    for s, ids in SWING_BEATS.items():
        sheet([byid[i] for i in ids], "スイング%d (B4-B6): 突進→接触→通過  上から見て 回転なし(直線の切り抜け) / 画面 回転なし" % s).save(os.path.join(HERE, "swing-%d.png" % s))
    overview().save(os.path.join(HERE, "overview.png"))


def overview():
    """Whole skill from above: caster path along cast-time forward (right), body facing and tick marks. Target at ~5 blocks after B0."""
    im = Image.new("RGB", (1140, 330), (250, 250, 246))
    d = ImageDraw.Draw(im)
    d.text((8, 6), "全体 真上 (前=右)  B0 滑走 → B1-B3 停止・背向け低姿勢 → B4-B6 突進と通過 → B7 保持。赤線=胸の向き, 橙=敵(接触位置)", fill=(0, 0, 0), font=FONT)
    sc = 60
    x0, y = 60, 190
    pos = 0.0
    d.line([(x0, y + 40), (x0 + 13 * sc, y + 40)], fill=(200, 200, 200), width=1)
    marks = []
    for b in BEATS:
        marks.append((pos, b))
        pos += b[9]
    for i, (p, b) in enumerate(marks):
        cx = x0 + p * sc
        cy = y + (i % 2) * 70 - 35
        d.ellipse([cx - 13, cy - 13, cx + 13, cy + 13], outline=(40, 40, 40), width=3)
        a = math.radians(b[6])
        d.line([(cx, cy), (cx + 26 * math.cos(a), cy - 26 * math.sin(a))], fill=(200, 40, 40), width=3)
        d.text((cx - 20, cy + 16), "B%d t%d" % (b[0], b[2]), fill=(30, 30, 30), font=FONTS)
        if b[9] > 0:
            ex = x0 + (p + b[9]) * sc
            arrow(d, (cx + 16, cy), (ex - 16, cy), (130, 130, 130), 4)
    contact = 6.0 + 5.0
    tx = x0 + contact * sc
    d.ellipse([tx - 14, y - 14 + 5, tx + 14, y + 14 + 5], outline=(230, 120, 0), width=4)
    d.text((tx - 20, y + 26), "敵 (接触 t13)", fill=(230, 120, 0), font=FONTS)
    d.text((8, 305), "移動量は [I]。参照のカメラ移動で測れないため 1H(術者の身長)=1.8 ブロックで換算した推定。接近(B0)の約 6 は 3-4H の中央値。", fill=(80, 80, 80), font=FONTS)
    return im


if __name__ == "__main__":
    main()
