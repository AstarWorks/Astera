package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value

/** Particles and sound. Family owner: fx worker. */
public sealed interface FxOp : PresentationOp {

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
        /** Trail density: with a spacing above 0 the burst repeats every [spacing] blocks along the path `at` travelled since the last run (v1 `trail_spacing`). */
        val spacing: Value<Double>? = null,
    ) : FxOp

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
    ) : FxOp
}
