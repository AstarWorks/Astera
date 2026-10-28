#!/usr/bin/env python3
"""Stick figures of beat-sheet.md (top / side / front) for couture-rapier-s2.
usage: python3 -I make-figures.py   (run in this directory; writes overview.png, swing-1.png, swing-2.png, swing-3.png, and r2: b4b7-rotation.png, b4b7-strip.png (needs ffmpeg + the cached reference video))
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


# ---------------------------------------------------------------------------------------------------------------------
# r2: B4-B7 per-object rotation (b4b7-rotation.png) and annotated frame strip (b4b7-strip.png).
# Angles are MEASURED on the reference frames (screen angle of the blade tip seen from the handle, 0 = forward = screen right,
# 90 = up, counter-clockwise positive, seen from the caster's right = skill-compare camera). +-15 deg. Frames f = 29.97 fps, tick = round((f-797)*2/3).
# ---------------------------------------------------------------------------------------------------------------------
VIDEO = os.path.expanduser("~/.cache/astera-skill-compare/videos/GIe7Fna5LD4.mp4")
ANG = {
    "A": {827: 85, 828: 95, 829: 122, 830: 238, 831: 275, 832: 295, 833: 312, 834: 333, 835: 335, 840: 337, 848: 338, 852: 340},
    "B": {836: 280, 837: 290, 838: 272, 839: 267, 840: 265, 841: 235, 842: 190, 843: 140, 844: 130, 845: 95, 846: 57, 847: 31, 848: 3, 849: -8, 850: -27, 852: -30},
    "C": {836: 223, 837: 220, 838: 212, 839: 190, 840: 180, 841: 162, 842: 112, 843: 75, 844: 57, 845: 29, 846: 6, 847: -7, 848: -12, 850: -15, 852: -22},
}
OBJ_COL = {"A": (200, 40, 40), "B": (40, 90, 210), "C": (30, 140, 70)}
OBJ_NAME = {"A": "A 飾りの半身 (花の蝶番)", "B": "B 無地の半身 (桃色の糸つき)", "C": "C 無地の半身"}
OBJ_INFO = {
    "A": ("f829-835 (t21-25)", "カメラ CCW (上→後→下→前)", "約 215-250° = 0.6-0.7 回転", "f829→830 で 1 フレームに約 115° (一気に走る)、f830-834 は 24°/f = 36°/tick", "高 [O: 頭の弧 f830-834]"),
    "B": ("f841-849 (t29-34)", "カメラ CW (上→前→下→後)", "約 295° = 0.8 回転 (縦下向き 265° → -30°)", "f841-845: 35°/f = 52°/tick (ピーク 50°/f)、その後減速して f850 で停止", "中高 [O: 先端の位置 f841-850]"),
    "C": ("f836-848 (t26-34)", "カメラ CW (上→前→下→後)", "約 245° = 0.7 回転 (後ろ下 223° → -22°)", "f836-840: 11°/f ゆっくり、f840-846: 29°/f = 44°/tick、f846 以降減速", "中高 [O: 先端の位置 f838-848]"),
}


def _arc_arrow(d, cx, cy, r, a0, a1, col, w=5):
    """arc from a0 to a1 (screen angle deg, CCW positive, y up). draws a polyline + arrow head at a1"""
    n = max(8, int(abs(a1 - a0) / 4))
    pts = []
    for i in range(n + 1):
        a = math.radians(a0 + (a1 - a0) * i / n)
        pts.append((cx + r * math.cos(a), cy - r * math.sin(a)))
    d.line(pts, fill=col, width=w)
    (x0, y0), (x1, y1) = pts[-2], pts[-1]
    ang = math.atan2(y1 - y0, x1 - x0)
    for s in (0.45, -0.45):
        d.line([(x1, y1), (x1 - 18 * math.cos(ang + s), y1 - 18 * math.sin(ang + s))], fill=col, width=w)


def _blade(d, cx, cy, ang, length, col, w=4, handle=True):
    """a half (handle + blade) centred on the pivot: tip at +0.55 L, handle at -0.45 L along ang"""
    a = math.radians(ang)
    ux, uy = math.cos(a), -math.sin(a)
    tip = (cx + 0.55 * length * ux, cy + 0.55 * length * uy)
    end = (cx - 0.45 * length * ux, cy - 0.45 * length * uy)
    d.line([end, tip], fill=col, width=w)
    hx, hy = cx - 0.38 * length * ux, cy - 0.38 * length * uy
    if handle:
        px, py = -uy, ux
        d.polygon([(hx + 8 * px, hy + 8 * py), (hx + 12 * ux, hy + 12 * uy), (hx - 8 * px, hy - 8 * py), (hx - 12 * ux, hy - 12 * uy)], outline=col)
    d.ellipse([tip[0] - 5, tip[1] - 5, tip[0] + 5, tip[1] + 5], fill=col)
    return tip


def rotation_figure():
    W, Hh = 1500, 1040
    im = Image.new("RGB", (W, Hh), (250, 250, 246))
    d = ImageDraw.Draw(im)
    d.text((10, 6), "couture-rapier-s2 B4-B7 (r2): 物ごとの回転。面 = 術者ローカルの forward-up (術者の右から見た画面と同じ)。角度 = 刃先の向き (0=前, 90=上, 反時計が正)、フレームから実測 ±15°", fill=(0, 0, 0), font=F)
    fnt = FS
    for i, k in enumerate("ABC"):
        ox = 10 + i * 495
        d.rectangle([ox, 34, ox + 485, 545], outline=(190, 190, 185))
        cx, cy, R = ox + 242, 190, 105
        col = OBJ_COL[k]
        d.ellipse([cx - R, cy - R, cx + R, cy + R], outline=(205, 205, 200), width=2)
        d.line([(cx - R - 20, cy), (cx + R + 20, cy)], fill=(215, 215, 210), width=1)
        d.line([(cx, cy - R - 20), (cx, cy + R + 20)], fill=(215, 215, 210), width=1)
        d.text((cx + R + 24, cy - 8), "前", fill=(110, 110, 110), font=fnt)
        d.text((cx - R - 38, cy - 8), "後", fill=(110, 110, 110), font=fnt)
        d.text((cx - 6, cy - R - 34), "上", fill=(110, 110, 110), font=fnt)
        d.text((cx - 6, cy + R + 22), "下", fill=(110, 110, 110), font=fnt)
        fr = sorted(ANG[k])
        first, last = fr[0], fr[-1]
        # tip dots by frame
        for f in fr:
            a = math.radians(ANG[k][f])
            px, py = cx + R * math.cos(a), cy - R * math.sin(a)
            shade = int(60 + 150 * (f - first) / max(1, last - first))
            dc = tuple(min(255, int(c + (255 - c) * (shade - 60) / 200)) for c in col)
            d.ellipse([px - 5, py - 5, px + 5, py + 5], fill=dc, outline=col)
            tx, ty = cx + (R + 26) * math.cos(a) - 14, cy - (R + 26) * math.sin(a) - 7
            d.text((tx, ty), "f%d" % f, fill=(70, 70, 70), font=ImageFont.truetype(FP, 10) if os.path.exists(FP) else fnt)
        # arc arrow (inside circle) from the first to the main spin end
        spin = {"A": (122, 335), "B": (265, -30), "C": (223, -22)}[k]
        _arc_arrow(d, cx, cy, R - 26, spin[0], spin[1] if k == "A" else spin[1] + (0), col)
        _blade(d, cx, cy, spin[0], 2 * R * 0.8, (150, 150, 150), 3)
        _blade(d, cx, cy, spin[1], 2 * R * 0.8, col, 5)
        d.ellipse([cx - 6, cy - 6, cx + 6, cy + 6], fill=(30, 30, 30))
        d.text((ox + 10, 40), OBJ_NAME[k], fill=col, font=F)
        info = OBJ_INFO[k]
        rows = ["回転する区間: " + info[0], "向き: " + info[1] + " / top: none (面が縦=真上から細い線)", "回転量: " + info[2], "速さ: " + info[3], "軸: 刃の中ほど (柄と刃の境) [I] / 先端が先頭 / 面が回転面と平行 = 刃で切る向き [I]", "確度: " + info[4]]
        for j, t in enumerate(rows):
            # wrap at 62 chars
            while len(t) > 38:
                d.text((ox + 10, 400 + 0), "", font=fnt)
                break
        y = 362
        for t in rows:
            line = ""
            for ch in t:
                line += ch
                if d.textlength(line, font=fnt) > 470:
                    d.text((ox + 8, y), line, fill=(40, 40, 40), font=fnt); y += 15; line = ""
            if line:
                d.text((ox + 8, y), line, fill=(40, 40, 40), font=fnt); y += 15
        d.text((ox + 10, 340), "灰=開始の向き / 色=終了の向き / 矢印=回る向き", fill=(120, 120, 120), font=fnt)
    # bottom row: side view of the alignment and flight + top view
    d.rectangle([10, 555, 985, 1030], outline=(190, 190, 185))
    d.text((18, 561), "横 (術者の右から。前=右)。f850-856 の整列 → f857 の発射 (回転なし、軸方向へ直進) → f859 に敵 [I: 距離と高さは概算]", fill=(0, 0, 0), font=F)
    sc = 82  # px per block (side)
    gx, gy = 120, 1000
    d.line([(gx - 60, gy), (gx + 5.4 * sc + 60, gy)], fill=(130, 130, 130), width=2)
    # caster
    d.line([(gx, gy), (gx, gy - 0.9 * sc), (gx, gy - 1.8 * sc)], fill=(40, 40, 40), width=4)
    d.ellipse([gx - 9, gy - 1.8 * sc - 9, gx + 9, gy - 1.8 * sc + 9], outline=(40, 40, 40), width=3)
    # enemy
    ex = gx + 5.4 * sc
    d.line([(ex, gy), (ex, gy - 1.8 * sc)], fill=(230, 120, 0), width=4)
    d.ellipse([ex - 9, gy - 1.8 * sc - 9, ex + 9, gy - 1.8 * sc + 9], outline=(230, 120, 0), width=3)
    d.text((ex - 20, gy + 4), "敵 (前 5.4)", fill=(230, 120, 0), font=fnt)
    # three halves, parallel at about -22 deg; hinge heights/forward offsets from the f850-856 frames (caster H = 1.8 blocks)
    spots = {"A": (0.15, 2.8), "C": (0.65, 2.15), "B": (-0.1, 1.95)}
    ang = {"A": -20, "C": -22, "B": -28}
    for k, (fx, fz) in spots.items():
        x, y = gx + fx * sc, gy - fz * sc
        _blade(d, x, y, ang[k], 2.2 * sc, OBJ_COL[k], 5)
        d.text((x - 30, y - 22), k, fill=OBJ_COL[k], font=F)
        a = math.radians(ang[k])
        tipx, tipy = x + 1.21 * sc * math.cos(a), y - 1.1 * sc * math.sin(a)
        c2 = tuple(min(255, c + 90) for c in OBJ_COL[k])
        d.line([(tipx, tipy), (ex - 12, gy - 1.3 * sc - (ord(k) - 65) * 8)], fill=c2, width=2)
    d.text((gx + 40, 600), "3 本とも長さ約 2.2 ブロック (1.2H)、刃先が右下 約 -20°〜-30°", fill=(60, 60, 60), font=fnt)
    d.text((gx + 40, 620), "薄い線 = 発射後の直線軌道 (f857-859, 約 1.5 tick)。ヒンジ高さ 約 2.1-2.8 ブロック [I]", fill=(60, 60, 60), font=fnt)
    # top view
    d.rectangle([995, 555, 1490, 1030], outline=(190, 190, 185))
    d.text((1003, 830), "どの回転面も縦 (forward-up) なので、真上から見ると回転は", fill=(40, 40, 40), font=fnt)
    d.text((1003, 847), "刃が前後に伸び縮みする細い線に潰れる: top = none [O]。", fill=(40, 40, 40), font=fnt)
    d.text((1003, 880), "左右 (術者の右) 方向のずれ・面の傾きは参照から読めない [U]", fill=(160, 60, 60), font=fnt)
    d.text((1003, 897), "(3 本が画面で重なる所の前後が決まらない)。", fill=(160, 60, 60), font=fnt)
    d.text((1003, 930), "図の線の位置は仮 (奥行きは [U])。", fill=(110, 110, 110), font=fnt)
    im.save(os.path.join(HERE, "b4b7-rotation.png"))


STRIP = [
    (827, "A 手に持つ、刃先は真上 85° / 手の鋏は1本"),
    (828, "A 手を離れ頭上へ (1 フレームで約 1H 上がる)"),
    (829, "A 頭上、柄が下 / 刃は上左 122°。右に弧の尾"),
    (830, "A 刃先 238° (後ろ下)。頭 = 下左の太い端"),
    (832, "A 刃先 295° (下)→前下へ。弧は 250°ぶん"),
    (834, "A 刃先 333° (前下)。弧が細くなる"),
    (836, "B と C が頭の高さに出現。V 字 (約 40°)"),
    (838, "B 縦下 272° / C 212°。A は静止 -22°"),
    (840, "B 265° / C 180° (水平)。C が回り出す"),
    (841, "B 235° / C 162°。両円盤が出る"),
    (842, "B 190° / C 112°"),
    (843, "B 140° / C 75°。どちらもカメラ CW"),
    (845, "B 95° / C 29°"),
    (847, "B 31° / C -7°。円盤が欠ける"),
    (850, "B -27° で停止 / C -15° / A -20°"),
    (857, "3 本が平行のまま発射。f858 に白い筋の集中"),
]


def frame_strip():
    import subprocess, tempfile
    frames = [f for f, _ in STRIP]
    sel = "+".join("eq(n\\,%d)" % f for f in frames)
    tmp = tempfile.mkdtemp(prefix="s2strip-")
    cmd = ["ffmpeg", "-v", "error", "-y", "-i", VIDEO, "-vf", "select='%s'" % sel, "-vsync", "0", os.path.join(tmp, "%03d.png")]
    subprocess.run(cmd, check=True)
    TW, TH = 480, 330
    cols, rows = 4, 4
    sheet = Image.new("RGB", (cols * TW, rows * (TH + 22) + 28), (250, 250, 246))
    sd = ImageDraw.Draw(sheet)
    sd.text((8, 6), "couture-rapier-s2 B4-B5 参照フレーム (f = 29.97fps、tick = round((f-797)*2/3))。A = 赤、B = 青、C = 緑。回転の向きは術者の右から見たカメラ基準", fill=(0, 0, 0), font=F)
    for i, (f, cap) in enumerate(STRIP):
        im = Image.open(os.path.join(tmp, "%03d.png" % (i + 1))).convert("RGB")
        if f >= 857:
            crop = im.crop((300, 0, 1500, 825))
        else:
            crop = im.crop((450, 0, 1450, 690))
        crop = crop.resize((TW, int(TW * crop.height / crop.width)))
        x, y = (i % cols) * TW, 28 + (i // cols) * (TH + 22)
        sheet.paste(crop.crop((0, 0, TW, TH)), (x, y))
        sd.rectangle([x, y, x + TW - 1, y + TH - 1], outline=(120, 120, 120))
        t = (f - 797) * 2 / 3
        sd.rectangle([x, y, x + 150, y + 18], fill=(0, 0, 0))
        sd.text((x + 4, y + 2), "f%d  t=%d" % (f, round(t)), fill=(255, 255, 0), font=FS)
        sd.text((x + 2, y + TH + 3), cap, fill=(30, 30, 30), font=FS)
    sheet.save(os.path.join(HERE, "b4b7-strip.png"))
    import shutil
    shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    swing1().save(os.path.join(HERE, "swing-1.png"))
    swing2().save(os.path.join(HERE, "swing-2.png"))
    swing3().save(os.path.join(HERE, "swing-3.png"))
    overview()
    rotation_figure()
    if os.path.exists(VIDEO):
        frame_strip()
