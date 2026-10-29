package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.skillengine.model.Value

/**
 * One key of a [VisualOp.Track]. [turn] is a rotation vector (axis direction, length = degrees, right-hand rule) applied on
 * top of the node's base orientation and interpolated by slerp, never an Euler triple; a channel that is null is not keyed
 * (it keeps its neutral value).
 */
public data class TrackKey(
    val tick: Int,
    val offset: Value<com.astarworks.astera.core.domain.geometry.Vec3>?,
    val turn: Value<com.astarworks.astera.core.domain.geometry.Vec3>?,
    /** Per-axis scale factor on the visual's authored size: a mesh scales x/y/z, a plate x = width and y = height (z ignored). A bare number in YAML is the uniform shorthand. */
    val scale: Value<com.astarworks.astera.core.domain.geometry.Vec3>?,
    /** Easing of the segment ENDING at this key: `linear` (default), `step`, or `in_` / `out_` / `in_out_` + sine, quad, cubic, quart, quint, expo, circ, back, elastic, bounce (the v1 `Easing` names). */
    val ease: String?,
    /** Absolute opacity 0..1 of a plate / quad (lerped between keyed neighbours); not for a mesh or block. */
    val alpha: Value<Double>? = null,
    /** Absolute colour `#rrggbb` of a quad (lerped per channel between keyed neighbours). */
    val color: String? = null,
    /** Vanilla block id a block display switches to from this key on (a step, never blended). */
    val block: String? = null,
    /** MiniMessage text a [VisualOp.Text] switches to from this key on (a step, never blended); nothing is applied before the first text key's tick. */
    val text: String? = null,
    /** Densified filler key, not a chunk boundary: v1 chunks only between the authored keyframe ticks (VfxTimeline.kt:544-559), so the runtime ignores it when cutting steps. */
    val sample: Boolean = false,
)
