package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.AxisRef
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value

/** How a plate / line is oriented. There is no third way (design 3.6): no free euler keys. */
public sealed interface PlateFace {
    /** Billboard CENTER. */
    public data object ViewerCenter : PlateFace

    /** Billboard VERTICAL. */
    public data object ViewerVertical : PlateFace

    /** Rotate only about [axis] to face the viewer (threads, beams). */
    public data class ViewerAxis(val axis: AxisRef) : PlateFace

    /** Fixed on the plane spanned by two axes of [frame]. */
    public data class Plane(val frame: String, val axisA: AxisRef, val axisB: AxisRef) : PlateFace
}

/** Display objects attached to frames. Family owner: visual (mesh/plate/line/trail) worker. */
public sealed interface VisualOp : PresentationOp {

    /** Show an item model (or one named part of it) on [frame]. */
    public data class Mesh(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val frame: String,
        val item: String,
        val part: String?,
        val scale: Value<Double>?,
        /** Where the model's origin sits relative to [frame], in the frame's axes `[left, up, forward]` (a held weapon: the grip). */
        val pivot: Value<Vec3>? = null,
        /** Orientation `[yaw, pitch, roll]` degrees of the model at track 0, applied INSIDE the track rotation (blade +Y to forward: pitch 90). */
        val rest: Value<Vec3>? = null,
        /** `[yaw, pitch, roll]` degrees that tilt the whole swing plane, applied OUTSIDE the track rotation. */
        val tilt: Value<Vec3>? = null,
    ) : VisualOp

    public data class Plate(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val parent: String,
        val face: PlateFace,
        val sprite: String,
        val sizeFrac: Value<Double>,
        val alpha: Value<Double>,
        /** Ticks each flipbook frame stays; null shows frame 0 only. */
        val frameTicks: Int? = null,
        /** After the last frame: true wraps to frame 0, false holds the last frame. */
        val loop: Boolean = false,
        /** `#rrggbb` multiplied into the sprite; null keeps the sprite's own colours. */
        val color: Value<String>? = null,
    ) : VisualOp

    /** Ribbon between two points; length follows them every tick. */
    public data class Line(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val from: PointRef,
        val to: PointRef,
        val thickness: Value<Double>,
        val color: Value<String>,
        val face: PlateFace,
    ) : VisualOp

    /** Ribbon from the history of [of] (and [root], if given: fan from root to tip). */
    public data class Trail(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val of: PointRef,
        val root: PointRef?,
        val ticks: Int,
        val widthFrac: Value<Double>,
        val color: Value<String>,
        val alpha: Value<Double>,
        val untilEvent: String?,
        /** Alpha at age a = `alpha * (1 - a / ticks) ^ fade`: 1 linear, below 1 holds bright then drops, above 1 drops early. */
        val fade: Value<Double>? = null,
        /** `soft` translucent ribbon (default), `crisp` hard ribbon that keeps its alpha until it ends, `both` soft body with a crisp edge. */
        val style: String = "soft",
    ) : VisualOp

    /**
     * Keyframes the local offset / rotation / scale of ONE visual object (mesh or plate created by the
     * op with id [target]) relative to its frame. Moves only that visual, never a gameplay frame, so
     * hit volumes are unaffected. [space] is the frame whose axes the offset is expressed in
     * (default: the visual's own frame). Ticks count from the moment this op runs.
     */
    public data class Track(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: String,
        val keys: List<TrackKey>,
        val space: String?,
    ) : VisualOp

    /** Remove presentation objects created earlier by the listed op ids. */
    public data class Remove(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val ids: List<String>,
    ) : VisualOp
}

/**
 * One key of a [VisualOp.Track]. [rotation] is `[yawDeg, pitchDeg, rollDeg]` applied on top of the
 * visual's base orientation; a channel that is null is not keyed (it keeps its neutral value).
 */
public data class TrackKey(
    val tick: Int,
    val offset: Value<com.astarworks.astera.core.domain.geometry.Vec3>?,
    val rotation: Value<com.astarworks.astera.core.domain.geometry.Vec3>?,
    /** Per-axis scale factor on the visual's authored size: a mesh scales x/y/z, a plate x = width and y = height (z ignored). A bare number in YAML is the uniform shorthand. */
    val scale: Value<com.astarworks.astera.core.domain.geometry.Vec3>?,
    /** `linear` (default), `ease_in`, `ease_out`, `ease_in_out`: easing of the segment ENDING at this key. */
    val ease: String?,
)
