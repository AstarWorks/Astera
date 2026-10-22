package com.astarworks.astera.core.domain.effect.animation

import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * One particle emitted by an [AnimatedShape] (RTM `IConfiguredParticle`, ADR-0194).
 *
 * [key] is the Bukkit particle name in lower case (`flame`, `dust`, `end_rod`).
 * [count] / [extra] / [offset] have vanilla `spawnParticle` meaning (see
 * [ParticleSpec]). [delayTicks] shifts this particle against the shape's own delay.
 * [color] + [scale] tint `dust` (RTM `Type: COLOR`, `Id: REDSTONE`).
 */
public data class AnimatedParticle(
    val key: String,
    val count: Int = 1,
    val extra: Double = 0.0,
    val offset: Vec3 = Vec3.ZERO,
    val delayTicks: Int = 0,
    val color: ParticleColor? = null,
    val scale: Float = 1.0f,
) {
    init {
        require(key.isNotBlank()) { "particle key must not be blank" }
        require(count >= 0) { "count must be >= 0, got $count" }
        require(delayTicks >= 0) { "delayTicks must be >= 0, got $delayTicks" }
        require(scale > 0f) { "scale must be > 0, got $scale" }
    }

    /** The spawn descriptor; [direction] replaces [offset] for `count == 0` (directional) spawns. */
    public fun toSpec(direction: Vec3? = null): ParticleSpec =
        ParticleSpec(
            key = key,
            count = count,
            offset = if (count == 0 && direction != null) direction else offset,
            extra = extra,
            color = color,
            scale = scale,
        )
}
