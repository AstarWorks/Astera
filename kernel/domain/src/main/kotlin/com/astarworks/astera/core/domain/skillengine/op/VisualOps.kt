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

    /**
     * Show an item model (or one named part of it) as a NODE of the frame tree named [id], child of [frame] (ADR-0303): its
     * local pose is `pivot` + `tilt`, tracks move that node, and any frame, emit, hit or child may name [id] as its parent.
     * [scale] is the model's size in blocks per model unit (an item display renders 1 block at scale 1).
     */
    public data class Mesh(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val frame: String,
        val item: String,
        val part: String?,
        val scale: Value<Double>?,
        /** Where the model's origin sits relative to [frame], in the frame's axes `[left, up, forward]` (a held weapon: the grip). */
        val pivot: Value<Vec3>? = null,
        /** Rotation vector (axis direction, length = degrees, right-hand) of the model at track 0, applied INSIDE the track turn (blade +Y to forward: `[90,0,0]` about left). */
        val rest: Value<Vec3>? = null,
        /** Rotation vector (degrees) that tilts the whole swing plane, applied OUTSIDE the track turn: the node's base rotation. */
        val tilt: Value<Vec3>? = null,
        /** Isolated item-surface effect id from content/shader-patches/effects. */
        val shaderEffect: String? = null,
    ) : VisualOp

    /** A sprite quad as a NODE of the frame tree named [id], child of [parent]; [size] is its edge length in BLOCKS. */
    public data class Plate(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val parent: String,
        val face: PlateFace,
        val sprite: String,
        /** Edge length in blocks (the converter resolves it; the runtime never scales by a tip distance). */
        val size: Value<Double>,
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
        /**
         * Radial colour bands, `from..to` as fractions of the root-to-tip segment (0 root, 1 tip), each its own quad.
         * Empty = one band over the whole segment in [color] / [alpha].
         */
        val bands: List<TrailBand> = emptyList(),
        /** Crisp opaque line of [TrailEdge.width] blocks centred on the tip path. */
        val edge: TrailEdge? = null,
        /** Soft dark band just outside the tip so the fan reads against bright skies. */
        val darkRim: TrailRim? = null,
        /** The ribbon is sampled finely enough that the root-to-tip segment turns at most this many degrees per quad (5..90). */
        val stepDeg: Double = 30.0,
        /** The ribbon is drawn this many ticks behind the point. */
        val lagTicks: Int = 0,
        /** Also draw the reversed face of every soft quad (a text-display quad is single sided). */
        val doubleSided: Boolean = false,
    ) : VisualOp

    /**
     * Keyframes the local offset / turn / scale of ONE tree node (a mesh or plate created by the op with id [target], or a plain frame of that id: its emits and children ride it)
     * relative to its parent frame. Children of that node ride it. [space] is the frame whose axes the offset is expressed
     * in (default: the node's parent). Ticks count from the moment this op runs.
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
    /** `linear` (default), `ease_in`, `ease_out`, `ease_in_out`: easing of the segment ENDING at this key. */
    val ease: String?,
)

/** One radial band of a [VisualOp.Trail]: colour `#rrggbb`, peak [alpha] 0..1, optional [colorTo] it has faded to at the end of the life. */
public data class TrailBand(val from: Double, val to: Double, val color: String, val alpha: Double, val colorTo: String? = null)

/** Leading edge of a [VisualOp.Trail]: `#rrggbb` line [width] blocks wide, opaque for the whole life. */
public data class TrailEdge(val color: String, val width: Double)

/** Dark rim outside the tip of a [VisualOp.Trail]: `#rrggbb` at [alpha], [width] blocks wide. */
public data class TrailRim(val color: String, val alpha: Double, val width: Double)
