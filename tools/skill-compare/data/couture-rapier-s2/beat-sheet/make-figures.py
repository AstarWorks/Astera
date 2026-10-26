#!/usr/bin/env python3
"""Stick figures of beat-sheet.md (top / side / front) for couture-rapier-s2.
usage: python3 -I make-figures.py   (run in this directory; writes overview.png, swing-1.png, swing-2.png, swing-3.png)
All coordinates are caster-local (forward, right, up) blocks, origin = feet at cast time, H = 1.8 blocks, enemy at forward 5.4.
Everything is [I] (hypothesis drawn from the reference). The numbers ARE the tip/path columns of beat-sheet.md.
Views: top = forward to the screen RIGHT, caster's right at the BOTTOM (so top CW stays CW on screen);
side = from the caster's right (forward = screen right); front = seen from the enemy (caster's right = screen left).
Labels are placed by a collision-avoiding helper (S1's overview.png had overlapping labels).
"""
import math, os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FP = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
try:
    F = ImageFont.truetype(FP, 15); FS = ImageFont.truetype(FP, 12)
except Exception:
    F = FS = ImageFont.load_default()

ENEMY = 5.4
COL = {"blade": (200, 40, 40), "b2": (40, 90, 210), "b3": (120, 60, 160), "line": (230, 120, 0), "ring": (150, 110, 200), "dash": (110, 110, 110), "hit": (230, 120, 0)}


class Canvas:
    def __init__(self, w, h, title):
        self.im = Image.new("RGB", (w, h), (250, 250, 246))
        self.d = ImageDraw.Draw(self.im)
        self.w, self.h = w, h
        self.boxes = []
        self.d.text((8, 6), title, fill=(0, 0, 0), font=F)
        self.boxes.append((0, 0, w, 26))

    def label(self, x, y, text, col=(30, 30, 30), font=None):
        font = font or FS
        bb = self.d.textbbox((0, 0), text, font=font)
        tw, th = bb[2] - bb[0], bb[3] - bb[1] + 4
        for dy in [0, 14, -14, 28, -28, 42, -42, 56, -56, 70, -70]:
            for dx in [0, 40, -40]:
                r = (x + dx, y + dy, x + dx + tw, y + dy + th)
                if r[0] < 2 or r[2] > self.w - 2 or r[1] < 28 or r[3] > self.h - 2:
                    continue
                if all(r[2] < b[0] or r[0] > b[2] or r[3] < b[1] or r[1] > b[3] for b in self.boxes):
                    self.d.text((r[0], r[1]), text, fill=col, font=font)
                    self.boxes.append(r)
                    return
        self.d.text((x, y), text, fill=col, font=font)

    def arrow(self, a, b, col, w=3):
        self.d.line([a, b], fill=col, width=w)
        ang = math.atan2(b[1] - a[1], b[0] - a[0])
        for s in (0.5, -0.5):
            self.d.line([b, (b[0] - 11 * math.cos(ang + s), b[1] - 11 * math.sin(ang + s))], fill=col, width=w)


def make_view(view, x0, y0, sc):
    """returns projector (f, r, u) -> px"""
    if view == "top":
        return lambda p: (x0 + p[0] * sc, y0 + p[1] * sc)
    if view == "side":
        return lambda p: (x0 + p[0] * sc, y0 - p[2] * sc)
    return lambda p: (x0 - p[1] * sc, y0 - p[2] * sc)


def draw_body(c, pr, view, yaw=0.0, crouch=0.0, at=(0, 0, 0), col=(40, 40, 40)):
    d = c.d
    h = 1.8 - 0.8 * crouch
    lean = 0.5 * crouch
    f0, r0, _ = at
    foot = (f0, r0, 0); hip = (f0, r0, 0.9 - 0.4 * crouch); head = (f0 + lean, r0, h)
    if view == "top":
        px, py = pr((f0, r0, 0))
        d.ellipse([px - 10, py - 10, px + 10, py + 10], outline=col, width=3)
        a = math.radians(yaw)  # + = caster's left; forward = screen right, left = screen up
        d.line([(px, py), (px + 22 * math.cos(a), py - 22 * math.sin(a))], fill=(200, 40, 40), width=3)
    else:
        pts = [pr(foot), pr(hip), pr(head)]
        d.line(pts, fill=col, width=4)
        hx, hy = pts[2]
        d.ellipse([hx - 8, hy - 8, hx + 8, hy + 8], outline=col, width=3)
        gx, gy = pr(foot)
        d.line([(gx - 20, gy), (gx + 20, gy)], fill=(130, 130, 130), width=2)


def poly(c, pr, pts, col, arrow=True, w=3, dots=True):
    q = [pr(p) for p in pts]
    for a, b in zip(q, q[1:]):
        if arrow:
            c.arrow(a, b, col, w)
        else:
            c.d.line([a, b], fill=col, width=w)
    if dots:
        c.d.ellipse([q[-1][0] - 4, q[-1][1] - 4, q[-1][0] + 4, q[-1][1] + 4], fill=col)
    return q


def ring(c, pr, view, center, rad, col, a0=None, a1=None):
    """horizontal ring (top: circle, side/front: flat ellipse)"""
    d = c.d
    pts = []
    n = 72
    s0, s1 = (0, 360) if a0 is None else (a0, a1)
    for i in range(n + 1):
        a = math.radians(s0 + (s1 - s0) * i / n)
        pts.append(pr((center[0] + rad * math.cos(a), center[1] + rad * math.sin(a), center[2])))
    d.line(pts, fill=col, width=2)


def enemy(c, pr, view, at=(ENEMY, 0, 0)):
    d = c.d
    col = (230, 120, 0)
    if view == "top":
        x, y = pr(at)
        d.ellipse([x - 11, y - 11, x + 11, y + 11], outline=col, width=3)
    else:
        a, b = pr(at), pr((at[0], at[1], 1.8))
        d.line([a, b], fill=col, width=4)
        d.ellipse([b[0] - 8, b[1] - 8, b[0] + 8, b[1] + 8], outline=col, width=3)


def panels(title, builders, W=470, H=360, footers=None):
    out = Image.new("RGB", (W * 3, H + 30), (250, 250, 246))
    od = ImageDraw.Draw(out)
    od.text((8, 6), title, fill=(0, 0, 0), font=F)
    for i, view in enumerate(("top", "side", "front")):
        c = Canvas(W, H, "")
        c.boxes = [(0, 0, W, 4)]
        builders[view](c)
        lab = {"top": "真上 (前=右, 術者の右=下) [I]", "side": "横 (術者の右から。前=右) [I]", "front": "正面 (敵側から。術者の右=画面左) [I]"}[view]
        c.d.text((8, H - 20), lab, fill=(80, 80, 80), font=FS)
        out.paste(c.im, (i * W, 30))
    return out


def swing1():
    """B2-B3 (t1..16): backhand wind-up, ring sweep, forward line, hit 1 at t9"""
    tip0, tip1 = (-2.0, 0.2, 1.1), (-2.3, 0.2, 0.85)

    def top(c):
        sc = 32; pr = make_view("top", 170, 200, sc)
        ring(c, pr, "top", (0, 0, 0), 3.3, COL["ring"])
        # CCW hypothesis arrow (forward -> left -> back -> right) drawn on the ring, [U]
        ang = [math.radians(a) for a in (60, 20, -20, -60)]
        pts = [(3.3 * math.cos(a), -3.3 * math.sin(a), 0) for a in (-50, -20, 10, 40, 70)]
        # screen: forward right, left = up. CCW (top) = forward -> left -> back: go up then around the back
        pts = [(3.3 * math.cos(math.radians(a)), -3.3 * math.sin(math.radians(a)) * -1, 0) for a in (20, 70, 120, 170, 220)]
        pts = [(3.3 * math.cos(math.radians(a)), 3.3 * math.sin(math.radians(a)) * 1, 0) for a in (-20, -70, -120, -170, -220)]
        poly(c, pr, pts, COL["ring"], arrow=True, w=2)
        draw_body(c, pr, "top", yaw=0, crouch=0.4)
        poly(c, pr, [(0.1, 0.2, 0), tip0, tip1], COL["blade"], arrow=False)
        poly(c, pr, [(0.5, 0, 0), (6.0, 0, 0)], COL["line"], arrow=True, w=2)
        enemy(c, pr, "top")
        c.label(8, 40, "リング 直径 約 6.6 ブロック (紫線)。向き CCW 仮 [U]", COL["ring"])
        c.label(8, 58, "赤 = 刃 (後ろへ保持)  橙 = 前方の細線 5.4+ ブロック", COL["blade"])
        q = pr((ENEMY, 0, 0)); c.label(q[0] - 30, q[1] + 18, "敵 t9 接触", COL["hit"])
        q = pr(tip1); c.label(q[0] - 10, q[1] + 14, "刃先 後ろ", COL["blade"])

    def side(c):
        sc = 40; pr = make_view("side", 120, 290, sc)
        draw_body(c, pr, "side", crouch=0.4)
        poly(c, pr, [(0.1, 0.2, 1.0), tip0, tip1], COL["blade"], arrow=False)
        ring(c, pr, "side", (0, 0, 0.3), 3.3, COL["ring"])
        poly(c, pr, [(0.8, 0, 0.2), (7.0, 0, 2.5)], COL["line"], arrow=True, w=2)
        enemy(c, pr, "side")
        c.label(8, 40, "細線: 足元 → 敵の頭上 (t11-15)", COL["line"])
        q = pr(tip1); c.label(q[0] - 30, q[1] + 16, "刃先 (-2.3, 0.85)", COL["blade"])
        q = pr((ENEMY, 0, 1.8)); c.label(q[0] - 20, q[1] - 22, "敵", COL["hit"])

    def front(c):
        sc = 55; pr = make_view("front", 235, 300, sc)
        draw_body(c, pr, "front", crouch=0.4)
        ring(c, pr, "front", (0, 0, 0.3), 3.3, COL["ring"])
        c.d.ellipse([235 - 14 - 20, 300 - 0.9 * sc - 14, 235 + 14 - 20, 300 - 0.9 * sc + 14], outline=COL["blade"], width=2)
        c.label(8, 40, "刃は奥行き(後ろ)。左右への広がりは小さい", COL["blade"])
        c.label(8, 58, "リングは足元の水平な楕円 (紫)", COL["ring"])
    return panels("スイング1 (コイン1, t1-16): 逆手で後ろへ構える → 水平リング+前方の細線。接触 t9 (f811)。ポーズ切替 t1 (f798)。リング回転 top CCW 仮 [U]", {"top": top, "side": side, "front": front})


def swing2():
    """B5-B9 (t19..49): raise, release, orbit, align, volley, hit 2 at t41"""
    def top(c):
        sc = 40; pr = make_view("top", 70, 190, sc)
        draw_body(c, pr, "top", yaw=-90)
        poly(c, pr, [(0.2, 0.4, 0), (0.2, 0.4, 0)], COL["blade"], arrow=False, dots=True)
        for k, r in enumerate((-0.35, 0.0, 0.35)):
            poly(c, pr, [(0.0, r, 0), (ENEMY, r * 0.2, 0)], (COL["blade"], COL["b2"], COL["b3"])[k], arrow=True, w=2)
        enemy(c, pr, "top")
        ring(c, pr, "top", (0.1, 0, 0), 0.9, COL["ring"])
        c.label(8, 40, "t22-35 頭上で周回 (紫輪)。t41 鋏3本が敵へ一直線", COL["blade"])
        q = pr((ENEMY, 0, 0)); c.label(q[0] - 40, q[1] + 18, "敵 t41 接触", COL["hit"])

    def side(c):
        sc = 40; pr = make_view("side", 70, 290, sc)
        draw_body(c, pr, "side", yaw=-90)
        poly(c, pr, [(0.2, 0.4, 1.0), (0.2, 0.4, 1.9), (0.2, 0.4, 3.9)], COL["blade"], arrow=True, w=2)  # raise t19-21 (CCW: forward-low -> up)
        ring(c, pr, "side", (0.2, 0, 3.6), 0.9, COL["ring"])
        for k, dz in enumerate((0.3, 0.0, -0.3)):
            poly(c, pr, [(0.5, 0, 3.6 + dz), (ENEMY, 0, 1.2 + dz * 0.3)], (COL["blade"], COL["b2"], COL["b3"])[k], arrow=True, w=2)
        enemy(c, pr, "side")
        c.label(8, 40, "t19-21 掲げ→放す  t22-39 頭上(高さ約3.6)で周回  t41 斜めに降下", COL["blade"])
        q = pr((0.2, 0.4, 3.9)); c.label(q[0] + 12, q[1] - 4, "t21 放す", COL["blade"])

    def front(c):
        sc = 55; pr = make_view("front", 235, 310, sc)
        draw_body(c, pr, "front", yaw=-90)
        for k, rr in enumerate((-0.9, 0.0, 0.9)):
            a = pr((0.0, rr, 3.4 + (0.2 if k == 1 else 0))); b = pr((0.0, rr, 3.4 + (0.2 if k == 1 else 0)))
            c.d.line([(a[0] - 10, a[1] + 12), (a[0] + 10, a[1] - 12)], fill=(COL["blade"], COL["b2"], COL["b3"])[k], width=4)
        ring(c, pr, "front", (0, 0, 3.5), 1.1, COL["ring"])
        c.label(8, 40, "頭上に鋏 3 本 (主 + 白い刃 2)。左右に約 0.9 離れる", COL["blade"])
        c.label(8, 58, "自由な腕は敵へ伸ばす (t41-)", COL["hit"])
    return panels("スイング2 (コイン2, t19-49): 掲げて放す → 頭上で周回 → 整列 → 3 本の鋏が敵へ。接触 t41 (f858)。周回の向きは [U] (左右で鏡像逆回転)", {"top": top, "side": side, "front": front})


def swing3():
    """B12-B15 (t64..87): crouch charge, dash through, hit 3 at t72-75, pass-through"""
    def top(c):
        sc = 33; pr = make_view("top", 40, 190, sc)
        draw_body(c, pr, "top", yaw=180, crouch=0.8)
        poly(c, pr, [(0, 0, 0), (ENEMY, 0, 0), (9.0, 0, 0)], COL["dash"], arrow=True, w=4)
        enemy(c, pr, "top")
        draw_body(c, pr, "top", yaw=0, crouch=0.5, at=(9.0, 0, 0), col=(150, 150, 150))
        # crescent behind the caster at the end: "(" convex backwards
        pts = [pr((9.0 - 2.0 * math.cos(math.radians(a)), 2.0 * math.sin(math.radians(a)), 0)) for a in range(-60, 61, 10)]
        c.d.line(pts, fill=(231, 191, 201), width=4)
        poly(c, pr, [(9.0, 0.1, 0), (7.2, 0.1, 0)], COL["blade"], arrow=False)
        c.label(8, 40, "t72 発進 → 敵 5.4 → t75 通過 → 約 9 で停止 (t78-87)", COL["dash"])
        q = pr((ENEMY, 0, 0)); c.label(q[0] - 30, q[1] + 18, "敵 t72-75", COL["hit"])
        q = pr((9.0, 0, 0)); c.label(q[0] - 70, q[1] + 24, "停止 刃は後ろ", COL["blade"])

    def side(c):
        sc = 33; pr = make_view("side", 40, 290, sc)
        draw_body(c, pr, "side", crouch=0.8)
        poly(c, pr, [(0.3, 0.1, 0.5), (-1.8, 0.2, 0.3)], COL["blade"], arrow=False)
        poly(c, pr, [(0.5, 0, 1.0), (ENEMY, 0, 1.0), (9.0, 0, 1.0)], COL["dash"], arrow=True, w=4)
        enemy(c, pr, "side")
        draw_body(c, pr, "side", crouch=0.5, at=(9.0, 0, 0), col=(150, 150, 150))
        poly(c, pr, [(9.0, 0.1, 1.3), (7.2, 0.1, 1.3)], COL["blade"], arrow=False)
        c.label(8, 40, "t64-71 低い構え (胸は左 = 背向き)  t72-75 突進 (胸の高さ)", COL["dash"])
        q = pr((ENEMY, 0, 1.8)); c.label(q[0] - 30, q[1] - 24, "X + 黄色の輪", COL["hit"])

    def front(c):
        sc = 55; pr = make_view("front", 235, 310, sc)
        draw_body(c, pr, "front", crouch=0.8)
        d = c.d
        x, y = pr((0, 0, 1.0))
        d.line([(x - 70, y - 70), (x + 70, y + 70)], fill=COL["hit"], width=3)
        d.line([(x + 70, y - 70), (x - 70, y + 70)], fill=COL["hit"], width=3)
        c.label(8, 40, "敵から見た命中: 白い斜めの切断線 2 本 + 縦 1 本 (X)", COL["hit"])
        c.label(8, 58, "黄色の輪 (#E8DD49) は敵の胸を囲む", (150, 130, 0))
    return panels("スイング3 (コイン3, t64-87): 低く構え → 突進 → 接触 t72-75 (f905-910) → 敵を通り過ぎて停止。背向き(胸は左)で突進し smear 内で振り向く [U]", {"top": top, "side": side, "front": front}, H=340)


def overview():
    W, H = 1500, 560
    c = Canvas(W, H, "全体 真上 (前=右)。t=-17 滑走 → t0 停止 → t1-16 コイン1 (ring+line) → t19-39 掲げ・周回 → t41 コイン2 → t64 構え → t72-75 コイン3 → t87 停止")
    d = c.d
    sc = 60
    x0, y0 = 60, 230
    d.line([(x0, y0 + 126), (x0 + 15 * sc, y0 + 126)], fill=(215, 215, 210), width=1)
    # tick ruler (blocks). caster origin x0 + 5.4 for approach
    start = 5.4  # approach distance: caster starts 5.4 behind the cast point
    ox = x0 + start * sc
    for k in range(0, 16, 2):
        xx = x0 + k * sc
        d.line([(xx, y0 + 126), (xx, y0 + 134)], fill=(160, 160, 160))
        d.text((xx - 6, y0 + 136), str(k - 5.4 if False else k), fill=(150, 150, 150), font=FS)
    c.boxes.append((x0, y0 + 120, x0 + 15 * sc, y0 + 156))
    d.text((x0 + 15 * sc - 200, y0 + 156), "ブロック (0 = 滑走開始)", fill=(130, 130, 130), font=FS)
    # caster stations
    stations = [
        (0.0, "t-17 滑走開始", 0, 0.9, 0),
        (5.4, "t0-8 立ち→逆手の構え", 0, 0.4, 1),
        (5.4, "", 0, 0, 0),
    ]
    # approach arrow
    c.arrow((x0 + 14, y0), (ox - 14, y0), COL["dash"], 4)
    c.label(x0 + 30, y0 - 26, "B0 滑走 約5.4 (t-17..-1)", COL["dash"])
    # caster at cast point
    for (px, col, yaw) in ((x0, (150, 150, 150), 0), (ox, (40, 40, 40), 0)):
        d.ellipse([px - 13, y0 - 13, px + 13, y0 + 13], outline=col, width=3)
        a = math.radians(yaw)
        d.line([(px, y0), (px + 26 * math.cos(a), y0 - 26 * math.sin(a))], fill=(200, 40, 40), width=3)
    # ring (coin 1)
    pts = [(ox + 3.3 * sc * math.cos(math.radians(a)), y0 + 3.3 * sc * 0.0 + 3.3 * sc * math.sin(math.radians(a)) * 0.45) for a in range(0, 361, 10)]
    d.line(pts, fill=COL["ring"], width=2)
    c.label(ox - 200, y0 - 120, "コイン1 t9: 水平リング (直径 約6.6) + 前方の細線", COL["ring"])
    # enemy
    ex = ox + ENEMY * sc
    d.ellipse([ex - 14, y0 - 14, ex + 14, y0 + 14], outline=(230, 120, 0), width=4)
    c.label(ex - 40, y0 - 40, "敵 (5.4 前)", COL["hit"])
    # volley lines (coin 2)
    for dy in (-12, 0, 12):
        c.arrow((ox + 10, y0 + dy * 0.3), (ex - 18, y0 + dy), (160, 100, 100), 2)
    c.label(ox + 30, y0 + 22, "コイン2 t41: 鋏3本が敵へ (頭上から)", (160, 60, 60))
    # dash (coin 3)
    endx = ox + 9.0 * sc
    c.arrow((ox + 18, y0 + 52), (endx - 18, y0 + 52), COL["dash"], 5)
    c.label(ox + 40, y0 + 64, "コイン3 t72-75: 突進 約9 → 敵を通過", COL["dash"])
    d.ellipse([endx - 13, y0 + 39, endx + 13, y0 + 65], outline=(150, 150, 150), width=3)
    d.line([(endx, y0 + 52), (endx + 26, y0 + 52)], fill=(200, 40, 40), width=3)
    pts = [(endx - 2.0 * sc * math.cos(math.radians(a)) * 0.5, y0 + 52 + 2.0 * sc * math.sin(math.radians(a)) * 0.35) for a in range(-60, 61, 10)]
    d.line(pts, fill=(231, 191, 201), width=4)
    c.label(endx - 110, y0 + 84, "t78-87 停止 (胸は前、刃だけ後ろ)", (90, 90, 90))
    # time notes (second row)
    notes = ["B1 t0 正面立ち 1 フレーム", "B2 t1-8 逆手の構え (刃は後ろ)", "B3 t9-16 リング+細線 (コイン1)", "B5-B8 t19-39 掲げ→放す→周回→整列",
             "B9 t41 コイン2", "B11-B12 t59-71 掲げ直し→低い構え", "B13-B14 t72-77 コイン3", "B15-B16 t78-97 通過・保持"]
    for i, t in enumerate(notes):
        d.text((20 + (i % 4) * 365, 410 + (i // 4) * 22), t, fill=(60, 60, 60), font=FS)
    d.text((20, 480), "距離は [I]: 参照のカメラが寄る/引くため 1H=1.8 ブロックで換算 (敵 3H = 5.4)。赤線 = 胸の向き、灰 = 開始位置。", fill=(110, 110, 110), font=FS)
    c.im.save(os.path.join(HERE, "overview.png"))


if __name__ == "__main__":
    swing1().save(os.path.join(HERE, "swing-1.png"))
    swing2().save(os.path.join(HERE, "swing-2.png"))
    swing3().save(os.path.join(HERE, "swing-3.png"))
    overview()
