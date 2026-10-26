"""Per-fx-tick composite of the tiled ring (what the flipbooks show on each tick), with the rig tip marked."""
import sys
import numpy as np
from PIL import Image, ImageDraw
W = "/IdeaProjects/Astera/.claude/worktrees/agent-ac6a4537bd59e6ffd"
sys.path.insert(0, W + "/tools/item-art/scripts")
import bastion_s1_sprites as b
from rig_path import RigPath

name = sys.argv[1]
out = sys.argv[2]
sw = b.SWEEPS[name]
sch = b.ring_schedule(name)
rp = RigPath(b.SKILL_ID, b.RIG_VFX, fx_vfx=sw["fx"])
strips = {q: np.asarray(Image.open(f"{W}/content/textures/sources/images/bastion-s1-{name}-q{q}.png")) for q in range(4)}
S = 128
pos = {0: (0, 0), 1: (1, 0), 2: (0, 1), 3: (1, 1)}  # TILES order: (-1,1) TL, (1,1) TR, (-1,-1) BL, (1,-1) BR
ticks = list(range(0, sch["last"] + 2))
sheet = Image.new("RGBA", (len(ticks) * (2 * S + 6), 2 * S + 20), (70, 90, 120, 255))
d = ImageDraw.Draw(sheet)
half = sw.get("tile_half", b.SWEEP_HALF)
for i, t in enumerate(ticks):
    x0 = i * (2 * S + 6)
    for q, (cx, cy) in pos.items():
        s0 = sch["spawn"].get(q)
        if s0 is None or t < s0 or t > sch["last"]:
            continue
        k = 0 if t <= s0 + 1 else t - s0 - 1
        fr = strips[q][:, k * 256:(k + 1) * 256]
        im = Image.fromarray(fr, "RGBA").resize((S, S), Image.NEAREST)
        sheet.alpha_composite(im, (x0 + cx * S, 20 + cy * S))
    # tip of the blade (front view coords a = rel.e1, b = rel.e2), image x = a, image y = -b
    p = np.asarray(rp.blade_point(t)) - sw["centre"]
    a, bb = p @ sw["e1"], p @ sw["e2"]
    px = x0 + S + a / half * S
    py = 20 + S - bb / half * S
    d.ellipse((px - 4, py - 4, px + 4, py + 4), outline=(255, 0, 255, 255), width=2)
    d.text((x0 + 4, 4), f"fx {t} sk {rp.skill_tick(t)}", fill=(255, 255, 255, 255))
sheet.save(out)
print(out)
