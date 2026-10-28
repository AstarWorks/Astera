package com.astarworks.astera.core.domain.skillengine.motion

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * A presentation track channel written as a rate (ADR-0303 section 3): "for N ticks, this direction, this much", instead of one key per tick.
 * Pure data; [RateLawExpander] lowers it to the existing [com.astarworks.astera.core.domain.skillengine.op.TrackKey] table at load time, so
 * the runtime sampler is unchanged.
 *
 * Window: the law starts at track tick [from] and runs [forTicks] ticks. Before [from] the channel holds its start value, after the window it
 * holds the end value (the sampler's own hold rule).
 *
 * [ease] shapes the accumulated TOTAL (3.7): `value = start + rate * forTicks * E(tau / forTicks)`. A law with [ease] has no `accel`
 * (the two are mutually exclusive, they both define the time profile). `step` is not a law ease (it is a pose cut: keep keys).
 */
public sealed interface RateLaw {
    public val from: Int
    public val forTicks: Int
    public val ease: String?
}

private fun checkLaw(from: Int, forTicks: Int, ease: String?, hasAccel: Boolean) {
    require(from >= 0) { "rate law: from must be >= 0 (was $from)" }
    require(forTicks >= 1) { "rate law: for must be >= 1 tick (was $forTicks)" }
    if (ease != null) {
        require(!hasAccel) { "rate law: ease and accel are mutually exclusive on one law" }
        require(ease.trim().lowercase() != "step") { "rate law: ease 'step' is a pose cut, keep keys" }
        requireNotNull(Easing.fromKey(ease)) { "rate law: unknown ease '$ease'" }
    }
}

/** `move`: offset(tau) = [start] + [velocity] * tau (blocks, blocks/tick); [ease] shapes the accumulated total. */
public data class MoveRate(
    override val from: Int,
    override val forTicks: Int,
    val start: Vec3,
    val velocity: Vec3,
    override val ease: String? = null,
) : RateLaw {
    init { checkLaw(from, forTicks, ease, false) }
}

/**
 * `spin`: orientation(tau) = R([axis], [degPerTick] * tau + [accel] * tau^2 / 2) * q([start]); [start] is a rotation vector (axis * degrees, the
 * TrackKey `turn` convention), the orientation the spin begins from. The axis is frozen in the parent basis.
 */
public data class SpinRate(
    override val from: Int,
    override val forTicks: Int,
    val axis: Vec3,
    val degPerTick: Double,
    val start: Vec3 = Vec3(0.0, 0.0, 0.0),
    val accel: Double = 0.0,
    override val ease: String? = null,
) : RateLaw {
    init {
        checkLaw(from, forTicks, ease, accel != 0.0)
        require(axis.lengthSquared() > 1e-18) { "rate law: spin axis must be non-zero" }
    }
}

/** `grow` on the per-axis scale: scale(tau) = [start] + [perTick] * tau + [accel] * tau^2 / 2. A uniform scale uses the same value on all three components. */
public data class GrowRate(
    override val from: Int,
    override val forTicks: Int,
    val start: Vec3,
    val perTick: Vec3,
    val accel: Vec3 = Vec3(0.0, 0.0, 0.0),
    override val ease: String? = null,
) : RateLaw {
    init { checkLaw(from, forTicks, ease, accel.lengthSquared() != 0.0) }
}

/** `fade` on the absolute opacity: a(tau) = [to] + ([start] - [to]) * (1 - E(tau / for)) ^ [exponent]; ADR default start 1, to 0, exponent 1 (linear). */
public data class FadeRate(
    override val from: Int,
    override val forTicks: Int,
    val start: Double = 1.0,
    val to: Double = 0.0,
    val exponent: Double = 1.0,
    override val ease: String? = null,
) : RateLaw {
    init {
        checkLaw(from, forTicks, ease, false)
        require(exponent > 0.0) { "rate law: fade exponent must be > 0" }
    }
}
