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

/**
 * Copies of one display: `times` copies of the op, copy k spawned `every * k` ticks after the op runs and removed `life` ticks after
 * it spawned (null = stays until removed). Inside the op's values `i` is the copy index and `n` the copy count. Copies are the
 * children `<id>#<k>` of a plain group node named `<id>`, so a `track` or `remove` on `<id>` moves / removes the group.
 */
public data class Repeat(val times: Value<Double>, val every: Int = 0, val life: Int? = null)

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
        /** Vanilla Display.brightness 0..15 (block and sky light together); null = lit by the world. */
        val brightness: Int? = null,
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
        /** Vanilla text-display `see_through`: the glyph draws through blocks. */
        val seeThrough: Boolean = false,
        /** Local position in [parent]'s axes `[left, up, forward]` at track 0. */
        val at: Value<Vec3>? = null,
        /** Rotation vector (degrees) of the plate at track 0. */
        val turn: Value<Vec3>? = null,
        /** Vanilla Display.brightness 0..15; 15 (full bright) when null. */
        val brightness: Int? = null,
        val repeat: Repeat? = null,
    ) : VisualOp

    /**
     * A vanilla block display (a block model of [block], e.g. `gold_block`) as a NODE of the frame tree: [size] is its edge per
     * axis in BLOCKS, centred on the node. [at] / [turn] are its pose in [parent] at track 0.
     */
    public data class Block(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val parent: String,
        val block: String,
        val size: Value<Vec3>,
        val at: Value<Vec3>? = null,
        val turn: Value<Vec3>? = null,
        val brightness: Int? = null,
        val repeat: Repeat? = null,
    ) : VisualOp

    /**
     * A solid-colour text-display quad (`color` + `alpha` background; optionally [text], a MiniMessage string, drawn on it) as a
     * NODE of the frame tree. [size] is width x height in BLOCKS; [face] works as for a plate.
     */
    public data class Quad(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val parent: String,
        val face: PlateFace,
        val size: Value<Vec3>,
        val color: Value<String>,
        val alpha: Value<Double>,
        /** Drawn text (a glyph); when set [size].x is the glyph scale (blocks per text unit) instead of the quad width. */
        val text: String? = null,
        /** Vanilla text-display `shadow` / `see_through` (only with [text]). */
        val shadow: Boolean = false,
        val seeThrough: Boolean = false,
        val at: Value<Vec3>? = null,
        val turn: Value<Vec3>? = null,
        val brightness: Int? = null,
        val repeat: Repeat? = null,
    ) : VisualOp

    /**
     * A vanilla text display of a MiniMessage [text] (a glyph, a coloured symbol) as a NODE of the frame tree; [size] is blocks per
     * text unit along x / y (0.2 = vanilla scale 1). The text keeps its own colour and opacity; only the box behind it is
     * styled: [background] `#rrggbb` at [backgroundAlpha] (0 = no box). Unlike a [Quad] glyph it has no track-driven colour.
     */
    public data class Text(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val parent: String,
        val face: PlateFace,
        val size: Value<Vec3>,
        val text: String,
        val background: Value<String>,
        val backgroundAlpha: Value<Double>,
        val shadow: Boolean = false,
        val seeThrough: Boolean = false,
        val at: Value<Vec3>? = null,
        val turn: Value<Vec3>? = null,
        val brightness: Int? = null,
        val repeat: Repeat? = null,
        /** Markup per locale code (`en`, `ja`); non-empty = the gateway picks the cast owner's locale and [text] is the fallback. */
        val textByLocale: Map<String, String> = emptyMap(),
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

    /**
     * The skin rig as frame-tree nodes (ADR-0303, skin-rig section). The op drives the node [frame] (a declared `body` frame,
     * child of [parent]) with the rig motion of the former VFX rig: `frame` = grip origin and `arm * wrist` rotation, so a
     * mesh / block / emit under [frame] rides the animated hand. With [anim] it also spawns the six skin-bone displays
     * (nodes `<id>_<bone>` under [parent]) playing that clip on the same clock; without it the arm stays at rest.
     * [hand] false anchors the rig about [pivot] instead of the right arm. Poses are sent pose to pose: a state only when it
     * differs, a `step` key (clip or [swing]) is a cut with interpolation 0.
     */
    public data class Rig(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val frame: String,
        val parent: String,
        val anim: String?,
        val hand: Boolean,
        /** Grip offset (hand) or rotation centre (anchored) in the parent's axes `[left, up, forward]`. */
        val pivot: Vec3,
        /** `(yaw, pitch, roll)` degrees applied OUTSIDE the swing. */
        val tilt: Vec3,
        /** `(yaw, pitch, roll)` degrees of the rig at swing zero, applied INSIDE the swing. */
        val rest: Vec3,
        /** Absolute rotation-vector wrist keys, slerped with the segment ease; empty = static. */
        val swing: List<RigSwingKey>,
        /** Longest client interpolation chunk of the swing, in ticks. */
        val sampleTicks: Int,
        /** Ticks the performance lasts; the bones and driven displays are not updated after it. */
        val durationTicks: Int,
        /** The freeze windows stop the swing clock (weapon / frame). */
        val respectHitStop: Boolean,
        /** The freeze windows also stop the skin body. */
        val bodyRespectHitStop: Boolean,
        /** Hit-stop windows (rig-relative ticks). */
        val freezes: List<RigFreeze>,
        /** Blade landmarks in the rig frame (blocks): defined as points `tip`, `root` and the direction `edge` of [frame]. */
        val tip: Vec3? = null,
        val root: Vec3? = null,
        val edge: Vec3? = null,
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
)

/** One radial band of a [VisualOp.Trail]: colour `#rrggbb`, peak [alpha] 0..1, optional [colorTo] it has faded to at the end of the life. */
public data class TrailBand(val from: Double, val to: Double, val color: String, val alpha: Double, val colorTo: String? = null)

/** Leading edge of a [VisualOp.Trail]: `#rrggbb` line [width] blocks wide, opaque for the whole life. */
public data class TrailEdge(val color: String, val width: Double)

/** Dark rim outside the tip of a [VisualOp.Trail]: `#rrggbb` at [alpha], [width] blocks wide. */
public data class TrailRim(val color: String, val alpha: Double, val width: Double)

/** One wrist key of a [VisualOp.Rig]: absolute rotation-vector [rotate] (null keeps the previous), [ease] of the segment ENDING here (`step` = cut). */
public data class RigSwingKey(val tick: Int, val rotate: Vec3?, val ease: String?, val sample: Boolean = false)
// [sample]: a pre-eased sample between two real keys; it is not a chunk boundary of the rig clock

/** A hit-stop window of a [VisualOp.Rig]: [ticks] frozen ticks starting at rig-relative tick [at]. */
public data class RigFreeze(val at: Int, val ticks: Int)
