package com.astarworks.astera.core.domain.skill.targeter

import kotlin.math.cos
import kotlin.math.max

/** Closed-form caster-space hit parameters; literal arithmetic is performed at content load. */
public sealed interface BakedHitShape {
    public data class Cone(public val range: Double, public val angleDegrees: Double) : BakedHitShape {
        public val minCos: Double = cos(Math.toRadians(angleDegrees / 2.0))
    }
    public data class Sphere(public val radius: Double) : BakedHitShape
    public data class Box(public val width: Double, public val height: Double, public val depth: Double) : BakedHitShape {
        /** Preserve the existing enclosing-sphere collision policy for projectile boxes. */
        public val enclosingRadius: Double = max(width, max(height, depth)) / 2.0
    }
}
