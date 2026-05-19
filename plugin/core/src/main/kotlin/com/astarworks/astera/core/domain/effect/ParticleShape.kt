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
    /** Single anchor-point emission (no spatial spread). */
    public data object Point : ParticleShape()

    /** Straight line of [lengthBlocks] blocks from the anchor. */
    public data class Line(public val lengthBlocks: Double) : ParticleShape() {
        init {
            require(lengthBlocks > 0.0) { "lengthBlocks must be positive: $lengthBlocks" }
        }
    }

    /** Horizontal ring of [radiusBlocks] around the anchor. */
    public data class Circle(public val radiusBlocks: Double) : ParticleShape() {
        init {
            require(radiusBlocks > 0.0) { "radiusBlocks must be positive: $radiusBlocks" }
        }
    }

    /** Full sphere of [radiusBlocks] around the anchor. */
    public data class Sphere(public val radiusBlocks: Double) : ParticleShape() {
        init {
            require(radiusBlocks > 0.0) { "radiusBlocks must be positive: $radiusBlocks" }
        }
    }

    /** Outward burst spreading up to [spreadBlocks] from the anchor. */
    public data class Burst(public val spreadBlocks: Double) : ParticleShape() {
        init {
            require(spreadBlocks > 0.0) { "spreadBlocks must be positive: $spreadBlocks" }
        }
    }
}
