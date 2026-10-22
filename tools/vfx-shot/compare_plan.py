"""Plan for `VIEW=compare`: maps a skill-compare case's intent.json onto world space.

usage: python3 -I compare_plan.py <intent.json|-> <mode case|side> <cx> <cy> <cz> <plan_out>

Intent units: x toward the enemy, y up, z toward the side camera, caster at the origin. The world mapping is the
identity (world = (cx+x, cy+y, cz+z)); the caster faces +x (yaw -90), so the side camera at +z sees the enemy on its
right. The client FOV option is fixed per run (the first key's fov); keys with another fov are emulated by moving the
camera along its view ray (distance * tan(fov/2) / tan(fov0/2)) so the framing matches.

Writes <plan_out> as `t_ms<TAB>command` lines (t relative to the cast) and prints shell assignments on stdout.
"""
import json
import math
import sys


def look(pos, target):
    dx, dy, dz = target[0] - pos[0], target[1] - pos[1], target[2] - pos[2]
    yaw = math.degrees(math.atan2(-dx, dz))
    pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
    return yaw, pitch


def main() -> int:
    src, mode = sys.argv[1], sys.argv[2]
    cx, cy, cz = (float(v) for v in sys.argv[3:6])
    out = sys.argv[6]
    intent = json.load(open(src)) if src != "-" else {}
    actors = {a["id"]: a for a in intent.get("actors", [])}
    enemy_x = 4.2
    if "enemy" in actors and actors["enemy"].get("keys"):
        caster_x = actors["caster"]["keys"][0]["pos"][0] if "caster" in actors else 0.0
        enemy_x = actors["enemy"]["keys"][0]["pos"][0] - caster_x
    duration = int(intent.get("duration_ticks", 100))
    keys = intent.get("camera", [])
    if mode == "side" or not keys:
        mid = enemy_x / 2
        keys = [{"t": 0, "pos": [mid, 1.6, 6.0], "target": [mid, 1.0, 0.0], "fov": 55}]
    fov0 = float(keys[0].get("fov", 70))
    fov_opt = max(-1.0, min(1.0, (fov0 - 70.0) / 40.0))
    k0 = math.tan(math.radians(fov0) / 2)
    # A destination key opts into a continuous dolly; unspecified transitions remain hard cuts.
    sampled = [keys[0]]
    for a, b in zip(keys, keys[1:]):
        if b.get("transition") == "linear":
            for tick in range(math.floor(a["t"]) + 1, math.ceil(b["t"])):
                u = (tick - a["t"]) / (b["t"] - a["t"])
                sampled.append({"t": tick,
                    "pos": [x + (y - x) * u for x, y in zip(a["pos"], b["pos"])],
                    "target": [x + (y - x) * u for x, y in zip(a["target"], b["target"])],
                    "fov": a.get("fov", fov0) + (b.get("fov", fov0) - a.get("fov", fov0)) * u})
        sampled.append(b)
    keys = sampled
    first = ""
    lines = []
    for key in keys:
        pos, tgt = key["pos"], key["target"]
        s = math.tan(math.radians(key.get("fov", fov0)) / 2) / k0
        p = [tgt[i] + (pos[i] - tgt[i]) * s for i in range(3)]
        yaw, pitch = look(p, tgt)
        # spectator tp sets the feet; the eye is 1.62 above
        cmd = "execute in minecraft:overworld run tp Watcher %.3f %.3f %.3f %.2f %.2f" % (
            cx + p[0], cy + p[1] - 1.62, cz + p[2], yaw, pitch)
        if not first:
            first = cmd
        lines.append("%d\t%s" % (round(key["t"] * 50), cmd))
    with open(out, "w") as f:
        f.write("\n".join(lines) + "\n")
    print("ENEMY_X=%.2f" % enemy_x)
    print("DURATION_TICKS=%d" % duration)
    print("CMP_FOV_OPT=%.4f" % fov_opt)
    print("CAM0_CMD='%s'" % first)
    return 0


sys.exit(main())
