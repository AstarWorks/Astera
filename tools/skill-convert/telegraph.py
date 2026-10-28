"""v1 `telegraph` -> a v1-form vfx definition (flat ground quads), baked from the v1 TelegraphPlanner.

Port of core/domain/.../effect/vfx/telegraph/TelegraphPlanner.kt (ADR-0290): the OUTER RIM exists at full size on tick 0, the
INNER FILL grows from nothing to the full footprint by `impact_tick` (group scale 0.01 -> 1 for circle / cone, a length-growing
bar for line). Quads lie flat on the ground in the effect frame (x = left, y = up, z = forward), anchor = footprint origin.
The result goes through the ordinary vfx element converter, so there is no telegraph op in v2: it is quads + tracks.
"""
import math

ENEMY_WARNING_RGB = 0xFF3B2F
RIM_THICKNESS = 0.12
RIM_ALPHA = 0.85
FILL_ALPHA = 0.35
GROUND_LIFT = 0.05
FILL_START_SCALE = 0.01
CIRCLE_RIM_SEGMENTS = 28
FILL_STRIPS = 11
CONE_STEP_DEGREES = 6.0
MAX_FILL_FAN = 28
FLAT_PITCH = -90.0


def _r(x):
    return round(float(x), 5)


def _el(eid, rgb, rim, keyframes, group=None):
    el = {
        "id": eid,
        "kind": {"type": "quad", "color": "#%06x" % rgb, "alpha": RIM_ALPHA if rim else FILL_ALPHA},
        "brightness": 15,
        "keyframes": keyframes,
    }
    if group:
        el["group_keyframes"] = group
    return el


def _flat(eid, rgb, rim, centre, yaw, length, thickness):
    return _el(eid, rgb, rim, [{"t": 0, "translate": [_r(c) for c in centre], "rotate": [_r(yaw), FLAT_PITCH, 0.0],
                                "scale": [_r(length), _r(thickness), 1.0]}])


def _radial(eid, rgb, rim, angle, length, thickness, lift):
    centre = [math.sin(angle) * length / 2.0, lift, math.cos(angle) * length / 2.0]
    yaw = math.degrees(math.atan2(math.cos(angle), math.sin(angle)))
    return _el(eid, rgb, rim, [{"t": 0, "translate": [_r(c) for c in centre], "rotate": [_r(yaw), FLAT_PITCH, 0.0],
                                "scale": [_r(length), _r(thickness), 1.0]}])


def plan(shape, radius, angle_degrees, width, ticks, impact_tick, rgb, name):
    if radius <= 0 or ticks < 1 or not 1 <= impact_tick <= ticks or not 0 < angle_degrees <= 360 or width <= 0:
        raise ValueError("telegraph: radius %s ticks %s impact_tick %s angle %s width %s out of range" % (radius, ticks, impact_tick, angle_degrees, width))
    full_at = max(1, impact_tick - 1)
    rim, fill = [], []
    grows_as_group = True
    if shape == "circle":
        seg = 2.0 * math.pi * radius / CIRCLE_RIM_SEGMENTS
        for i in range(CIRCLE_RIM_SEGMENTS):
            a = 2.0 * math.pi * i / CIRCLE_RIM_SEGMENTS
            rim.append(_flat("rim-%d" % i, rgb, True, (radius * math.cos(a), GROUND_LIFT, radius * math.sin(a)),
                             math.degrees(math.atan2(math.cos(a), -math.sin(a))), seg * 1.08, RIM_THICKNESS))
        h = 2.0 * radius / FILL_STRIPS
        for i in range(FILL_STRIPS):
            z = -radius + h * (i + 0.5)
            half = math.sqrt(max(0.0, radius * radius - z * z))
            if half < 0.05:
                continue
            fill.append(_flat("fill-%d" % i, rgb, False, (0.0, GROUND_LIFT + 0.01, z), 0.0, 2.0 * half, h * 1.02))
    elif shape == "cone":
        half = math.radians(angle_degrees / 2.0)
        arc_steps = max(3, math.ceil(angle_degrees / CONE_STEP_DEGREES))
        seg = 2.0 * half * radius / arc_steps
        for i in range(arc_steps):
            a = -half + 2.0 * half * (i + 0.5) / arc_steps
            rim.append(_flat("rim-arc-%d" % i, rgb, True, (radius * math.sin(a), GROUND_LIFT, radius * math.cos(a)),
                             -math.degrees(a), seg * 1.08, RIM_THICKNESS))
        for idx, sign in enumerate((1.0, -1.0)):
            rim.append(_radial("rim-edge-%d" % idx, rgb, True, sign * half, radius, RIM_THICKNESS, GROUND_LIFT))
        n = min(MAX_FILL_FAN, max(3, math.ceil(angle_degrees / CONE_STEP_DEGREES)))
        step = 2.0 * half / n
        for i in range(n):
            a = -half + step * (i + 0.5)
            fill.append(_radial("fill-%d" % i, rgb, False, a, radius, radius * step * 0.95, GROUND_LIFT + 0.01))
    elif shape == "line":
        hw = width / 2.0
        rim.append(_flat("rim-left", rgb, True, (hw, GROUND_LIFT, radius / 2.0), 90.0, radius, RIM_THICKNESS))
        rim.append(_flat("rim-right", rgb, True, (-hw, GROUND_LIFT, radius / 2.0), 90.0, radius, RIM_THICKNESS))
        rim.append(_flat("rim-near", rgb, True, (0.0, GROUND_LIFT, 0.0), 0.0, width + RIM_THICKNESS, RIM_THICKNESS))
        rim.append(_flat("rim-far", rgb, True, (0.0, GROUND_LIFT, radius), 0.0, width + RIM_THICKNESS, RIM_THICKNESS))
        fill.append(_el("fill", rgb, False, [
            {"t": 0, "translate": [0.0, GROUND_LIFT + 0.01, 0.0], "rotate": [0.0, FLAT_PITCH, 0.0], "scale": [_r(width), 0.01, 1.0]},
            {"t": full_at, "translate": [0.0, GROUND_LIFT + 0.01, _r(radius / 2.0)], "rotate": [0.0, FLAT_PITCH, 0.0],
             "scale": [_r(width), _r(radius), 1.0], "ease": "linear"},
        ]))
        grows_as_group = False
    else:
        raise ValueError("telegraph shape '%s' (circle / cone / line)" % shape)
    if grows_as_group:
        for el in fill:
            el["group_keyframes"] = [{"t": 0, "scale": FILL_START_SCALE}, {"t": full_at, "scale": 1.0, "ease": "linear"}]
    return {"id": "telegraph-%s-%s" % (shape, name), "duration_ticks": ticks, "elements": rim + fill}
