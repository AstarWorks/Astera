package com.astarworks.astera.core.domain.effect

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Where a [ParticleData.Trail] / [ParticleData.Vibration] particle travels to. The planner
 * ([ParticleEmitter]) resolves [Offset] and [Toward] into [Absolute] before the spec reaches a gateway.
 */
public sealed interface ParticleTarget {
    /** [offset] blocks from the emission position. */
    public data class Offset(public val offset: Vec3) : ParticleTarget

    /** The emission context's "toward" point (a projectile's target, the VFX trail target, ...). */
    public data object Toward : ParticleTarget

    /** A world position. */
    public data class Absolute(public val position: Vec3) : ParticleTarget
}

/**
 * Typed extra data for particles whose Bukkit `Particle.dataType` is not `Void` (verified against the
 * Paper API in `ParticleDataTypeTest`). The Paper gateway maps each variant to the matching data
 * object and drops the particle (debug log) when the variant does not fit the particle's `dataType`.
 */
public sealed interface ParticleData {
    /** `dust` (`Particle.DustOptions`). */
    public data class Dust(public val color: ParticleColor, public val scale: Float = 1.0f) : ParticleData

    /** `dust_color_transition` (`Particle.DustTransition`). */
    public data class DustTransition(
        public val from: ParticleColor,
        public val to: ParticleColor,
        public val scale: Float = 1.0f,
    ) : ParticleData

    /** Plain `Color` data: `entity_effect`, `flash`, `tinted_leaves`. */
    public data class Tint(public val color: ParticleColor) : ParticleData

    /** `effect` / `instant_effect` (`Particle.Spell`). */
    public data class Spell(public val color: ParticleColor, public val power: Float = 1.0f) : ParticleData

    /** `block`, `block_crumble`, `block_marker`, `falling_dust`, `dust_pillar` (`BlockData`). */
    public data class Block(public val blockKey: String) : ParticleData {
        init { require(blockKey.isNotBlank()) { "block must not be blank" } }
    }

    /** `item` (`ItemStack` of a material). */
    public data class Item(public val materialKey: String) : ParticleData {
        init { require(materialKey.isNotBlank()) { "item must not be blank" } }
    }

    /** `trail` (`Particle.Trail`): travels to [target] over [durationTicks]. */
    public data class Trail(
        public val target: ParticleTarget,
        public val color: ParticleColor,
        public val durationTicks: Int = 20,
    ) : ParticleData {
        init { require(durationTicks > 0) { "trail duration_ticks must be > 0: $durationTicks" } }
    }

    /** `sculk_charge` (`Float` roll, radians). */
    public data class SculkCharge(public val roll: Float) : ParticleData

    /** `shriek` (`Integer` delay in ticks). */
    public data class Shriek(public val delayTicks: Int) : ParticleData {
        init { require(delayTicks >= 0) { "shriek delay must be >= 0: $delayTicks" } }
    }

    /** `vibration`: travels from the emission position to [destination] in [arrivalTicks]. */
    public data class Vibration(
        public val destination: ParticleTarget,
        public val arrivalTicks: Int = 20,
    ) : ParticleData {
        init { require(arrivalTicks > 0) { "vibration arrival_ticks must be > 0: $arrivalTicks" } }
    }

    /** `dragon_breath` (`Float` power). */
    public data class Power(public val value: Float) : ParticleData
}

/** Who sees a skill particle. */
public enum class ParticleViewers { ALL, CASTER, OTHERS }

/** Direction of a directed (`count = 0`) particle; resolved per emission by [ParticleEmitter]. */
public sealed interface VelocityDirection {
    public data object FromCasterLook : VelocityDirection
    public data object AlongTangent : VelocityDirection
    public data object Outward : VelocityDirection
    public data object Up : VelocityDirection
    public data class Custom(public val direction: Vec3) : VelocityDirection
}

/**
 * Vanilla directed-particle motion: the particle is spawned with `count = 0`, `offset` = unit
 * direction, `extra` = [speed]. A non-zero [spreadDegrees] jitters each particle inside a cone; a non-zero
 * [speedJitter] scales each particle's speed by a random factor in `1 +- speedJitter`, so a burst of dozens
 * of particles fans out into a depth of speeds instead of one shell (ADR-0274).
 */
public data class ParticleVelocity(
    public val direction: VelocityDirection,
    public val speed: Double,
    public val spreadDegrees: Double = 0.0,
    public val speedJitter: Double = 0.0,
) {
    init {
        require(speed >= 0.0) { "velocity speed must be >= 0: $speed" }
        require(spreadDegrees in 0.0..180.0) { "spread_degrees must be in 0..180: $spreadDegrees" }
        require(speedJitter in 0.0..1.0) { "speed_jitter must be in 0..1: $speedJitter" }
    }
}
