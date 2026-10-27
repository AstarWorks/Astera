package com.astarworks.astera.core.domain.skillengine.op

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
    ) : VisualOp

    public data class Plate(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val parent: String,
        val face: PlateFace,
        val sprite: String,
        val sizeFrac: Value<Double>,
        val alpha: Value<Double>,
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
    ) : VisualOp

    /** Remove presentation objects created earlier by the listed op ids. */
    public data class Remove(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val ids: List<String>,
    ) : VisualOp
}
