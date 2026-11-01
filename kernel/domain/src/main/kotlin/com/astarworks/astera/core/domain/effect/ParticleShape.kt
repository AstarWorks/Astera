package com.astarworks.astera.core.domain.effect

/**
 * Geometric template for spawning a [ParticleDefinition]'s particles relative
 * to an emission anchor.
 *
 * Sealed so the adapter's emission `when` is exhaustive — adding a new shape
 * is a deliberate cross-cutting change rather than something a contributor can
 * miss. The adapter (Phase 6 work) interprets each variant by sampling the
 * shape into Minecraft particle calls.
 *
 * Distance fields are in blocks (Minecraft world units). All shapes must
 * supply non-negative dimensions (`init` requires).
 */
public sealed class ParticleShape {
    /**
     * Straight line of [lengthBlocks] blocks from the anchor along the frame forward. With [points]
     * the line is sampled into evenly spaced emission points; without it the legacy random scatter applies.
     */
    public data class Line(public val lengthBlocks: Double, public val points: Int? = null) : ParticleShape() {
        init {
            require(lengthBlocks > 0.0) { "lengthBlocks must be positive: $lengthBlocks" }
            require(points == null || points >= 1) { "points must be >= 1: $points" }
        }
    }

    /** Horizontal ring of [radiusBlocks] around the anchor. */
    public data class Circle(public val radiusBlocks: Double, public val points: Int? = null) : ParticleShape() {
        init {
            require(radiusBlocks > 0.0) { "radiusBlocks must be positive: $radiusBlocks" }
            require(points == null || points >= 1) { "points must be >= 1: $points" }
        }
    }

    /** Full sphere of [radiusBlocks] around the anchor. */
    public data class Sphere(public val radiusBlocks: Double, public val points: Int? = null) : ParticleShape() {
        init {
            require(radiusBlocks > 0.0) { "radiusBlocks must be positive: $radiusBlocks" }
            require(points == null || points >= 1) { "points must be >= 1: $points" }
        }
    }

    /** [points] evenly spaced points on a circle of [radiusBlocks] in the frame's horizontal (XZ) plane. */
    public data class Ring(public val radiusBlocks: Double, public val points: Int) : ParticleShape() {
        init {
            require(radiusBlocks > 0.0) { "radiusBlocks must be positive: $radiusBlocks" }
            require(points >= 1) { "points must be >= 1: $points" }
        }
    }

    /**
     * [points] points on an arc of [radiusBlocks] from [fromDeg] to [toDeg] (0 = frame forward, positive
     * toward the frame's left), in the horizontal plane.
     */
    public data class Arc(
        public val radiusBlocks: Double,
        public val fromDeg: Double,
        public val toDeg: Double,
        public val points: Int,
    ) : ParticleShape() {
        init {
            require(radiusBlocks > 0.0) { "radiusBlocks must be positive: $radiusBlocks" }
            require(points >= 1) { "points must be >= 1: $points" }
        }
    }

    /** [points] points on a helix of [radiusBlocks] rising [heightBlocks] over [turns] turns. */
    public data class Helix(
        public val radiusBlocks: Double,
        public val heightBlocks: Double,
        public val turns: Double,
        public val points: Int,
    ) : ParticleShape() {
        init {
            require(radiusBlocks > 0.0) { "radiusBlocks must be positive: $radiusBlocks" }
            require(turns > 0.0) { "turns must be positive: $turns" }
            require(points >= 1) { "points must be >= 1: $points" }
        }
    }
}
