package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.AxisRef
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
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
 * Ops allowed in the `presentation` section: they attach to frames and points and cannot move
 * frames, hit, damage or raise events (so a skin can never change contacts).
 */
public sealed interface PresentationOp : SkillOp {

    /** Show an item model (or one named part of it) on [frame]. */
    public data class Mesh(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val frame: String,
        val item: String,
        val part: String?,
        val scale: Value<Double>?,
    ) : PresentationOp

    public data class Plate(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val parent: String,
        val face: PlateFace,
        val sprite: String,
        val sizeFrac: Value<Double>,
        val alpha: Value<Double>,
    ) : PresentationOp

    /** Ribbon between two points; length follows them every tick. */
    public data class Line(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val from: PointRef,
        val to: PointRef,
        val thickness: Value<Double>,
        val color: Value<String>,
        val face: PlateFace,
    ) : PresentationOp

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
    ) : PresentationOp

    public data class Emit(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val at: PointRef,
        val particle: String,
        val count: Value<Double>,
        val dir: Value<Vec3>?,
        val spreadDeg: Value<Double>?,
        val speed: Value<Double>?,
        val inheritVelocity: Value<Double>?,
        val color: Value<String>?,
        val seed: String?,
    ) : PresentationOp

    /** Positional sound: `at` is required, there are no unlocated sounds. */
    public data class Sound(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val sound: String,
        val at: PointRef,
        val volume: Value<Double>?,
        val pitch: Value<Double>?,
        val everyTicks: Int?,
        val untilEvent: String?,
    ) : PresentationOp

    public data class VictimAnim(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val anim: String,
        val strength: Value<Double>?,
    ) : PresentationOp

    /** Remove presentation objects created earlier by the listed op ids. */
    public data class Remove(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val ids: List<String>,
    ) : PresentationOp
}
