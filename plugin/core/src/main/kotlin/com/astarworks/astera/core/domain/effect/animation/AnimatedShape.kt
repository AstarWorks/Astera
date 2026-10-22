package com.astarworks.astera.core.domain.effect.animation

/**
 * A time-animated particle pattern (ADR-0196; port of RTM's `IConfiguredParticleSet`).
 *
 * Every shape is `amount` independent instances; each instance orients itself by
 * [angle] once. [delayTicks] is the shape's offset from the animation start, added to
 * each particle's own delay.
 *
 * RTM only ever had two set types (`particle` = single point, `circle`); [Ring] and [Line]
 * are Astera additions. Adding a shape means adding a variant here and a
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
     * An expanding ring (not in RTM): over [periodTicks] ticks, each tick drops [points] points evenly spaced on a
     * circle whose radius grows linearly from [startRadius] to [endRadius]. The ring lies in the plane
     * perpendicular to the (angle-resolved) axis, so the default [ParticleAngle.VERTICAL] gives a flat ground ring
     * (a shockwave). [rise] lifts the ring along the axis by that many blocks over the period (a rising halo). Each instance starts at a random phase. Directional particles (`count: 0`) fly outward.
     * Budget: `amount * periodTicks * points * particles.size` spawns.
     */
    public data class Ring(
        override val delayTicks: Int = 0,
        override val amount: Int = 1,
        override val angle: ParticleAngle = ParticleAngle.VERTICAL,
        override val particles: List<AnimatedParticle>,
        val periodTicks: Int = 1,
        val points: Int = 16,
        val startRadius: Double = 0.0,
        val endRadius: Double = 1.0,
        val rise: Double = 0.0,
    ) : AnimatedShape() {
        init {
            validateCommon(delayTicks, amount, particles)
            require(periodTicks >= 1) { "periodTicks must be >= 1, got $periodTicks" }
            require(points >= 1) { "points must be >= 1, got $points" }
            require(startRadius >= 0.0 && endRadius >= 0.0) { "radii must be >= 0, got $startRadius..$endRadius" }
        }
    }

    /**
     * A jagged polyline (lightning, not in RTM) from the anchor along the (angle-resolved) axis for [length]
     * blocks, split into [segments] pieces. Every interior vertex is pushed sideways by up to [jitter] blocks
     * (random, perpendicular to the axis); the endpoints stay put. [pointsPerSegment] points are dropped along each
     * piece, all on tick `delay + particle.delay`. Each instance rolls its own shape.
     * Budget: `amount * (segments * pointsPerSegment + 1) * particles.size` spawns.
     */
    public data class Line(
        override val delayTicks: Int = 0,
        override val amount: Int = 1,
        override val angle: ParticleAngle = ParticleAngle.RAW,
        override val particles: List<AnimatedParticle>,
        val length: Double = 1.0,
        val segments: Int = 6,
        val jitter: Double = 0.3,
        val pointsPerSegment: Int = 3,
    ) : AnimatedShape() {
        init {
            validateCommon(delayTicks, amount, particles)
            require(length > 0.0) { "length must be > 0, got $length" }
            require(segments >= 1) { "segments must be >= 1, got $segments" }
            require(jitter >= 0.0) { "jitter must be >= 0, got $jitter" }
            require(pointsPerSegment >= 1) { "pointsPerSegment must be >= 1, got $pointsPerSegment" }
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
