package com.astarworks.astera.combat.domain.aoe

/**
 * Geometric shape for a persistent area-effect zone (Wave 5m / ADR-0088).
 *
 * Three primitives covering Phase 1's needs:
 *  - [Circle] — vertical cylinder of radius `r` (XZ-circle, full Y).
 *  - [Box] — axis-aligned box centred at [AreaEffectSpec.center].
 *  - [Ring] — annular vertical cylinder (`innerRadius < outerRadius`).
 *    Used by boss-circle skills that want to leave the centre safe.
 *
 * Phase 6 may introduce Cone / Cross variants; the sealed interface
 * stays in `combat.domain.aoe` so the shape catalogue grows in a single
 * place.
 */
public sealed interface AreaEffectShape {

    public data class Circle(public val radius: Double) : AreaEffectShape {
        init {
            require(radius >= 0.0) { "Circle.radius must be >= 0: $radius" }
        }
    }

    public data class Box(
        public val sizeX: Double,
        public val sizeY: Double,
        public val sizeZ: Double,
    ) : AreaEffectShape {
        init {
            require(sizeX >= 0.0) { "Box.sizeX must be >= 0: $sizeX" }
            require(sizeY >= 0.0) { "Box.sizeY must be >= 0: $sizeY" }
            require(sizeZ >= 0.0) { "Box.sizeZ must be >= 0: $sizeZ" }
        }
    }

    public data class Ring(
        public val innerRadius: Double,
        public val outerRadius: Double,
    ) : AreaEffectShape {
        init {
            require(innerRadius >= 0.0) {
                "Ring.innerRadius must be >= 0: $innerRadius"
            }
            require(outerRadius > innerRadius) {
                "Ring.outerRadius ($outerRadius) must be > innerRadius ($innerRadius)"
            }
        }
    }
}
