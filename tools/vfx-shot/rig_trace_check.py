#!/usr/bin/env python3
"""Grip-in-fist check on what the Paper adapters actually sent (ADR-0315, RigDisplayContract).

Reads the `[vfx-trace] rig hand ...` rows (PaperSkinRigPuppet: the entity position, yaw, teleport/interpolation
durations and the held-item grip in world space it sent for the puppet's right hand) and the `[vfx-trace] rig mesh ...`
rows (PaperSkillVisualGateway: the same fields for the weapon display, plus its world origin) out of a capture's
trace.log and asserts, per game tick, that the two displays stand on ONE entity position and yaw, use the same
teleport / interpolation durations, and that the weapon origin lies on the hand grip. The game-sim measures the
engine's model; this measures the metadata the client interpolates, which is where the 0.6-block split lived
(dev 2026-11-01: weapon moved by entity position, hand by transformation, one tick apart).

The hand row's `reach=` (how far the pinned target lies beyond / inside the straight arm, 0 when the arm reaches it) is
checked too: a pin the arm cannot reach means the body clip and the frame table are not on one clock (dev 2026-11-01,
second cause: a cast started on a tick the engine had already run put the frames a tick ahead of the rows, reach +0.79).

The weapon display is not placed AT the hand grip: the engine puts the table's `grip` point of the held mesh on the
pin (ADR-0315), so the display origin sits `PLAYER_SCALE * |grip|` from the pin (the aim rotation only turns that
offset, it does not change its length). The check therefore expects `distance == PLAYER_SCALE * |grip|` of the item's
`held_hand` part (content/frames/<item>.yaml, the point table RigHeldParityScenarioTest holds to the model JSON's
display chain), within TOL_GRIP; a hold that moves the pin off the hand, or a mesh not placed by its grip, still breaks it.

Usage: rig_trace_check.py <trace.log> <rig-check.txt> [<content/frames/ITEM.yaml>]   (exit 1 = contract broken; a log
without rig rows passes; without the frames file the grip offset is taken as 0)
"""
import re
import sys

TOL = 0.05
TOL_GRIP = 0.01
PLAYER_SCALE = 0.9375
GRIP = re.compile(r"grip:\s*\[([^\]]+)\]")
ROW = re.compile(r"\[vfx-trace\] rig (hand|mesh) (.*)$")
KV = re.compile(r"(\w+)=(\S+)")


def vec(s):
    return tuple(float(x) for x in s.split(","))


def dist(a, b):
    return sum((x - y) ** 2 for x, y in zip(a, b)) ** 0.5


def held_hand_grip_offset(frames_path):
    """PLAYER_SCALE * |grip| of the `held_hand` part (blocks): how far the engine puts the display origin from the pin."""
    if not frames_path:
        return 0.0
    part = re.split(r"^\s*held_hand:", open(frames_path, encoding="utf-8").read(), maxsplit=1, flags=re.M)  # the part key, never a comment mentioning it
    m = GRIP.search(part[1]) if len(part) == 2 else None
    if not m:
        return 0.0
    return PLAYER_SCALE * sum(float(x) ** 2 for x in m.group(1).split(",")) ** 0.5


def main(trace_path, out_path, frames_path=None):
    grip_offset = held_hand_grip_offset(frames_path)
    hands = {}  # (tick, player, side) -> fields; side is "r" (the main hand) or "l" (a `hand: off` rig)
    meshes = {}  # (tick, player, side) -> fields
    for line in open(trace_path, encoding="utf-8", errors="replace"):
        m = ROW.search(line)
        if not m:
            continue
        kind, rest = m.groups()
        f = dict(KV.findall(rest))
        key = (int(f["tick"]), f["player"], f.get("hand", "r"))
        (hands if kind == "hand" else meshes)[key] = f
    lines = []
    if not meshes and not hands:
        lines.append("no rig rows: no rig mesh or puppet hand was traced in this capture")
        open(out_path, "w").write("\n".join(lines) + "\n")
        print(lines[-1])
        return 0
    if not hands:
        lines.append(f"puppet not driven: {len(meshes)} rig mesh rows, no puppet hand rows (a rig without a body clip keeps the vanilla arm)")
        open(out_path, "w").write("\n".join(lines) + "\n")
        print(lines[-1])
        return 0
    failures = []
    worst = 0.0
    worst_tick = None
    worst_reach = 0.0
    worst_reach_tick = None
    checked = 0
    hand_span = {}
    for tick, player, _side in hands:
        lo, hi = hand_span.get(player, (tick, tick))
        hand_span[player] = (min(lo, tick), max(hi, tick))
    left_checked = 0
    worst_left = 0.0
    worst_left_tick = None
    for key in sorted(meshes):
        tick, player, side = key
        mesh = meshes[key]
        hand = hands.get(key)
        if hand is None:
            lo, hi = hand_span.get(player, (None, None))
            if lo is not None and lo < tick < hi:
                failures.append(f"t{tick}: a {'left' if side == 'l' else 'right'}-hand rig mesh was written but no puppet hand was posed for {player}")
            else:
                lines.append(f"t{tick} mesh only (outside the puppet's driven span {lo}..{hi})")
            continue
        problems = []
        for field in ("at", "yaw", "tp", "ip"):
            if mesh.get(field) != hand.get(field):
                problems.append(f"{field} mesh={mesh.get(field)} hand={hand.get(field)}")
        raw = dist(vec(mesh["origin"]), vec(hand["grip"]))
        gap = abs(raw - grip_offset) if side == "r" else raw
        if side == "l":
            left_checked += 1
            if gap > worst_left:
                worst_left, worst_left_tick = gap, tick
        elif gap > worst:
            worst, worst_tick = gap, tick
        reach = float(hand["reach"]) if hand.get("reach") not in (None, "null") else 0.0
        if gap > (TOL_GRIP if side == "r" else TOL):
            why = f"; the frame origin is {abs(reach):.4f} {'inside' if reach < 0 else 'outside'} the arm's sphere (reach {reach:+.4f}): move the frame key onto it" if abs(reach) > TOL_GRIP / 2 else ""
            problems.append(f"origin {mesh['origin']} vs pin {hand['grip']} is {raw:.4f} apart, the held grip offset is {grip_offset:.4f} (gap={gap:.4f}){why}")
        if abs(reach) > abs(worst_reach):
            worst_reach, worst_reach_tick = reach, tick
        if abs(reach) > TOL:
            problems.append(f"pin {hand.get('target')} out of the arm's reach by {reach:+.4f} (clip and frame not on one clock)")
        checked += 1
        lines.append(f"t{tick} hand={side} at={mesh['at']} yaw={mesh['yaw']} tp={mesh['tp']}/{hand['tp']} ip={mesh['ip']}/{hand['ip']} gap={gap:.4f} reach={reach:+.4f}" + (" FAIL " + "; ".join(problems) if problems else ""))
        if problems:
            failures.append(f"t{tick}: " + "; ".join(problems))
    for key in sorted(hands):
        if key not in meshes:
            lines.append(f"t{key[0]} hand={key[2]} hand only (no mesh write this tick)")
    verdict = f"checked={checked} left_checked={left_checked} worst_left_gap={worst_left:.4f}" + (f" at t{worst_left_tick}" if worst_left_tick is not None else "") + f" worst_gap={worst:.4f}" + (f" at t{worst_tick}" if worst_tick is not None else "") \
        + f" worst_reach={worst_reach:+.4f}" + (f" at t{worst_reach_tick}" if worst_reach_tick is not None else "")
    if failures:
        verdict = "FAIL " + verdict + ": " + failures[0] + (f" (+{len(failures) - 1} more)" if len(failures) > 1 else "")
    else:
        verdict = "OK " + verdict
    lines.append(verdict)
    open(out_path, "w").write("\n".join(lines) + "\n")
    print(verdict)
    return 1 if failures else 0


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4):
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) == 4 else None))
