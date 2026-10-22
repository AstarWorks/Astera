package com.astarworks.astera.core.domain.effect.animation

/**
 * A time-animated particle pattern (ADR-0196; port of RTM's `IConfiguredParticleSet`).
 *
 * Every shape is `amount` independent instances; each instance orients itself by
 * [angle] once. [delayTicks] is the shape's offset from the animation start, added to
 * each particle's own delay.
 *
 * RTM only ever had two set types (`particle` = single point, `circle`), so the sealed
 * hierarchy is exactly those two. Adding a shape means adding a variant here and a
 * branch in [ParticleAnimationSession].
 */
public sealed class AnimatedShape {
    public abstract val delayTicks: Int
    public abstract val amount: Int
    public abstract val angle: ParticleAngle
    public abstract val particles: List<AnimatedParticle>

    /** RTM `Type: PARTICLE` (the default set): the particles spawn at the anchor, [amount] times. */
    public data class Point(
        override val delayTicks: Int = 0,
        override val amount: Int = 1,
        override val angle: ParticleAngle = ParticleAngle.RAW,
        override val particles: List<AnimatedParticle>,
    ) : AnimatedShape() {
        init {
            validateCommon(delayTicks, amount, particles)
        }
    }

    /**
     * RTM `Type: CIRCLE`: each instance orbits the anchor in the plane spanned by its
     * (angle-resolved) direction and a perpendicular. For each of the [periodTicks]
     * ticks, and for each particle, it drops [acceleration] points: each advances the
     * angle by [speed] degrees (sign fixed randomly per instance, start angle random)
     * and the radius by [radiusAcceleration] inside `[minRadius, maxRadius]`
     * (see [CircleCursor]).
     */
    public data class Circle(
        override val delayTicks: Int = 0,
        override val amount: Int = 1,
        override val angle: ParticleAngle = ParticleAngle.RAW,
        override val particles: List<AnimatedParticle>,
        val periodTicks: Int = 0,
        val speed: Int = 0,
        val acceleration: Int = 0,
        val minRadius: Double = 0.0,
        val maxRadius: Double = 0.0,
        val repeatRadiusAcceleration: Boolean = true,
        val radiusAcceleration: Double = 0.0,
    ) : AnimatedShape() {
        init {
            validateCommon(delayTicks, amount, particles)
            require(periodTicks >= 0) { "periodTicks must be >= 0, got $periodTicks" }
            require(acceleration >= 0) { "acceleration must be >= 0, got $acceleration" }
            require(minRadius >= 0.0 && maxRadius >= minRadius) {
                "radius range must satisfy 0 <= min <= max, got [$minRadius, $maxRadius]"
            }
        }
    }

    /**
     * A sweeping arc in front of the caster (melee swings, ADR-0221). Unlike [Circle] it is
     * deterministic: [points] positions are laid on a horizontal arc of [radius] around the anchor,
     * from [startDegrees] to [endDegrees] measured from the (flattened) facing direction, positive =
     * to the caster's right, so `50 -> -50` is a right-to-left slash. Point `i` spawns at tick
     * `delayTicks + round(sweepTicks * i / (points - 1))` so the arc visibly travels. Height runs
     * linearly from [heightStart] to [heightEnd] (a diagonal slash). Each point emits every particle
     * of the shape once; [amount] repeats the whole arc. [angle] is ignored (the facing is fixed).
     * Cost per play: `amount * points * particles.size` spawn calls (budget in the skill YAML).
     */
    public data class Arc(
        override val delayTicks: Int = 0,
        override val amount: Int = 1,
        override val angle: ParticleAngle = ParticleAngle.RAW,
        override val particles: List<AnimatedParticle>,
        val radius: Double,
        val startDegrees: Double,
        val endDegrees: Double,
        val points: Int,
        val sweepTicks: Int = 0,
        val heightStart: Double = 0.0,
        val heightEnd: Double = 0.0,
    ) : AnimatedShape() {
        init {
            validateCommon(delayTicks, amount, particles)
            require(radius > 0.0) { "radius must be > 0, got $radius" }
            require(points >= 1) { "points must be >= 1, got $points" }
            require(sweepTicks >= 0) { "sweepTicks must be >= 0, got $sweepTicks" }
        }
    }

    internal companion object {
        fun validateCommon(delayTicks: Int, amount: Int, particles: List<AnimatedParticle>) {
            require(delayTicks >= 0) { "delayTicks must be >= 0, got $delayTicks" }
            require(amount >= 1) { "amount must be >= 1, got $amount" }
            require(particles.isNotEmpty()) { "a shape needs at least one particle" }
        }
    }
}

/** An ordered set of [AnimatedShape]s played together from one anchor (one RTM `effect.particles[<phase>]` list). */
public data class ParticleAnimation(val shapes: List<AnimatedShape>) {
    init {
        require(shapes.isNotEmpty()) { "an animation needs at least one shape" }
    }
}
