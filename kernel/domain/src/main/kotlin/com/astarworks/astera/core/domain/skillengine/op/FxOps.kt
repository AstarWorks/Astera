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
        /** With [overTicks]: the pitch glides from [pitch] to [pitchTo] over that many ticks (windup rise), then holds. */
        val pitchTo: Value<Double>? = null,
        val overTicks: Int? = null,
        /** Easing key of the glide (v1 `pitch_arc.ease`, e.g. `in_quad`); null = linear. */
        val pitchEase: String? = null,
    ) : FxOp
}
