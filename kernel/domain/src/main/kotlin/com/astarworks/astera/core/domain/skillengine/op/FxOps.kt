package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value

/** Particles and sound. Family owner: fx worker. */
public sealed interface FxOp : PresentationOp {

    /**
     * A particle spawn. For a vanilla particle this is exactly `spawnParticle(at, count, offset, speed(=extra), data)`:
     * an unset field takes the vanilla default (count 1, offset 0, speed 0, no data), and vanilla `count 0` keeps its
     * meaning (the [offset] is the direction and [speed] the speed). For a custom particle definition [count], when set,
     * is the number of particles (it replaces the definition's own count); unset keeps the definition's.
     *
     * [dir] is explicit sugar that expands to vanilla arguments: [count] spawns, each `count 0`, `offset =
     * jitter(dir, spreadDeg) * speed + inheritVelocity`, `extra 1`. It never changes a plain emit.
     */
    public data class Emit(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val at: PointRef,
        val particle: String,
        val count: Value<Double>?,
        val offset: Value<Vec3>?,
        /** Vanilla `extra`. */
        val speed: Value<Double>?,
        /** Vanilla `data`: dust colour `#rrggbb`. */
        val data: Value<String>?,
        /** Vanilla dust size (with [data]). */
        val dataSize: Value<Double>?,
        val dir: Value<Vec3>?,
        val spreadDeg: Value<Double>?,
        val inheritVelocity: Value<Double>?,
        val seed: String?,
        /** Blocks between plays along the segment the frame of [at] covered since the last tick (a fast body keeps a continuous trail); null = one play. */
        val spacing: Value<Double>? = null,
        /** Where the burst plays relative to [at], in the axes of [at]'s frame `[left, up, forward]` (a ring slot, a helix point). */
        val shift: Value<Vec3>? = null,
        /** `times` plays, play k `every * k` ticks after the op runs; `i` / `n` are bound in the op's values. `life` is not used by an emit. */
        val repeat: Repeat? = null,
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
