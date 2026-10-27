#!/usr/bin/env python3
"""Generate the raw geometry of astera:couture-shears-linked (dark, ~2.9 block long shears for Couturier S2 t52-60).

Long axis = model Y (tip up), centred on y=8 so display fixed pivots at the middle. Two halves rotate +-THETA about the
pivot (8,PY); every piece is placed by rotating its centre and giving it a z rotation about its own centre
(client accepts arbitrary single-axis angles; forge validate warns, intentionally).
Writes raw.model.json (faces use #0) and groups.json (material group -> element ids) for material.apply.
usage: gen.py <outdir>
"""
import json, math, sys
from pathlib import Path
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
PX, PY = 8.0, 1.3          # pivot
THETA = 6.5                # half opening angle (deg) -> tips ~6 units apart
els, groups = [], {}


def add(name, grp, cx, cy, cz, sx, sy, sz, ang=0.0, half=0):
    """(cx,cy) local offset from the pivot, in the half's frame; half=+1/-1 rotates by +-THETA."""
    th = THETA * half
    a = math.radians(th)
    gx = PX + cx*math.cos(a) - cy*math.sin(a)
    gy = PY + cx*math.sin(a) + cy*math.cos(a)
    total = ang + th
    e = {"name": name, "from": [round(gx-sx/2, 4), round(gy-sy/2, 4), round(cz-sz/2, 4)],
         "to": [round(gx+sx/2, 4), round(gy+sy/2, 4), round(cz+sz/2, 4)],
         "faces": {f: {"texture": "#0", "uv": [0, 0, 1, 1]} for f in ("north", "south", "east", "west", "up", "down")}}
    if abs(total) > 1e-6:
        e["rotation"] = {"origin": [round(gx, 4), round(gy, 4), round(cz, 4)], "axis": "z", "angle": round(total, 4)}
    els.append(e); groups.setdefault(grp, []).append(name)


def half(h, tag):
    s = 1 if h > 0 else -1          # half A (h=+1) tip leans to -x; its cutting edge faces +x (inner)
    z0 = 8.55 if h > 0 else 7.45    # blade centre plane (A in front, B behind)
    zs = 1 if h > 0 else -1
    n, y, seg = 8, 1.0, 3.0
    for i in range(n):
        w = 4.4 - (4.4-1.2)*i/(n-1)
        yc = y + seg*i + seg/2
        add(f"{tag}_blade{i}", "blade", 0, yc, z0, w, seg, 0.9, half=h)
        add(f"{tag}_ridge{i}", "ridge", -s*(w*0.18), yc, z0, w*0.28, seg-0.3, 1.25, half=h)
        add(f"{tag}_edge{i}", "edge", s*(w/2+0.18), yc, z0, 0.36, seg, 0.7, half=h)
        if i < n-1:
            add(f"{tag}_tooth{i}", "accent", -s*(w/2+0.28), yc+0.4, z0, 0.55, 1.1, 0.7, ang=-s*35, half=h)
        add(f"{tag}_stitch{i}", "thread", s*0.15, yc, z0+zs*0.5, w*0.55, 0.28, 0.12, ang=s*40, half=h)
    add(f"{tag}_tip0", "blade", 0, y+seg*n+0.9, z0, 0.9, 1.8, 0.8, half=h)
    add(f"{tag}_tip1", "edge", 0, y+seg*n+2.2, z0, 0.5, 1.2, 0.6, half=h)
    add(f"{tag}_shoulder", "blade", -s*0.2, -0.6, z0, 3.0, 2.4, 0.9, half=h)
    add(f"{tag}_neck", "ridge", s*1.8, -2.6, z0, 1.9, 5.0, 1.0, ang=s*43.4, half=h)
    # handle ring (10-gon of segments) hanging below
    cxr, cyr, rx, ry = s*3.6, -8.4, 3.1, 3.6
    m = 10
    for k in range(m):
        t0 = 2*math.pi*k/m
        t1 = 2*math.pi*(k+1)/m
        x0, y0 = cxr+rx*math.cos(t0), cyr+ry*math.sin(t0)
        x1, y1 = cxr+rx*math.cos(t1), cyr+ry*math.sin(t1)
        mx, my = (x0+x1)/2, (y0+y1)/2
        L = math.hypot(x1-x0, y1-y0)
        ang = -math.degrees(math.atan2(x1-x0, y1-y0))
        add(f"{tag}_ring{k}", "ring", mx, my, z0, 1.1, L+0.25, 1.0, ang=ang, half=h)
        if k % 2 == 0:
            add(f"{tag}_ringedge{k}", "edge", mx, my, z0, 0.3, L-0.4, 1.12, ang=ang, half=h)
    add(f"{tag}_gem", "gem", cxr, cyr-ry-0.5, z0, 1.6, 1.6, 1.3, ang=45, half=h)
    fx, fy = cxr+s*1.4, cyr+ry+0.2
    for k in range(4):
        add(f"{tag}_petal{k}", "petal", fx+1.1*math.cos(math.radians(k*90+20)), fy+1.1*math.sin(math.radians(k*90+20)),
            z0+zs*0.7, 1.3, 1.3, 0.5, ang=k*20, half=h)
    add(f"{tag}_heart", "heart", fx, fy, z0+zs*0.85, 0.9, 0.9, 0.5, ang=45, half=h)
    for k in range(2):
        ycl = cyr-ry-2.0-k*1.35
        add(f"{tag}_link{k}", "chain" if k % 2 == 0 else "chain2", cxr+s*0.5*math.sin(k*0.9), ycl, z0,
            0.9 if k % 2 == 0 else 0.5, 1.5, 0.5 if k % 2 == 0 else 1.0, half=h)
    add(f"{tag}_tassel", "thread", cxr+s*0.3, cyr-ry-2.0-2*1.35-0.2, z0, 0.7, 1.4, 0.4, half=h)
    for k in range(3):
        add(f"{tag}_tipthread{k}", "thread", s*(0.3+k*0.35), y+seg*n+3.0+k*0.9, z0, 0.25, 0.9, 0.25, ang=s*(8+k*6), half=h)


half(+1, "a")
half(-1, "b")
for nm, g, sx, sy, sz, ang in [("hub_collar", "accent", 3.6, 3.6, 3.4, 45), ("hub_ring", "ring", 3.0, 3.0, 3.8, 0),
                               ("hub_capf", "gold", 1.7, 1.7, 4.3, 45), ("hub_capb", "gold", 1.2, 1.2, 4.6, 0)]:
    add(nm, g, 0, 0, 8.0, sx, sy, sz, ang=ang, half=0)
for k, ang in enumerate([35, -35]):
    add(f"bind{k}", "thread", 0, 0.0, 8.0, 5.2, 0.4, 3.9, ang=ang, half=0)

doc = {"textures": {"0": "minecraft:block/iron_block", "particle": "minecraft:block/iron_block"}, "elements": els}
(out/"raw.model.json").write_text(json.dumps(doc, indent=1))
(out/"groups.json").write_text(json.dumps(groups, indent=1))
ys = [e["from"][1] for e in els]+[e["to"][1] for e in els]
print(len(els), "elements; y range", min(ys), max(ys), {k: len(v) for k, v in groups.items()})
