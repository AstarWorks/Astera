package com.astarworks.astera.core.domain.effect

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Identifier of a [ParticleDefinition] loaded from `content/particles/<id>.yaml`.
 *
 * Naming: lower-kebab-case (`a-z`, `0-9`, `-`). Matches the convention used by
 * weapon / skill / item ids so the asset namespace is consistent and greppable.
 */
@JvmInline
public value class ParticleId(public val value: String) {
    init {
        require(value.matches(KEBAB)) { "ParticleId must be lower-kebab: $value" }
    }

    override fun toString(): String = value

    public companion object {
        private val KEBAB = Regex("^[a-z][a-z0-9-]*$")
    }
}

/**
 * Vendor-neutral particle effect definition (Wave 5f).
 *
 * A [ParticleDefinition] describes one named particle preset that the adapter
 * layer materializes into actual Minecraft particle calls. The domain stays
 * free of Bukkit / Paper types — `ParticleShape` is a sealed hierarchy of pure
 * geometry, and color is just an RGB triple.
 *
 * Lives in `plugin/core` because every feature plugin (combat skill effects,
 * match phase stings, economy reward sprinkles) consumes a single
 * `IParticleRegistry` lookup via Core's `ServiceRegistry`.
 *
 * `lifetimeTicks` is plain [Long] ticks (20 TPS Minecraft cadence) — Astera
 * does not yet have a `Tick` value class.
 *
 * # Compared to [ParticleSpec]
 *
 * [ParticleSpec] is the **call-site** emission descriptor (one-off spawn at a
 * `Vec3` with a key + count). [ParticleDefinition] is the **catalog entry**
 * (named preset loaded from YAML, shape geometry + lifetime + i18n display
 * name). The Phase 6 emitter glue will translate a `ParticleDefinition`
 * lookup into `ParticleSpec` emissions over the requested lifetime.
 */
public data class ParticleDefinition(
    public val id: ParticleId,
    public val shape: ParticleShape,
    public val color: ParticleColor,
    public val count: Int,
    public val lifetimeTicks: Long,
    public val displayNameKey: MessageKey? = null,
    /**
     * Vanilla particle type this definition emits (`flame`, `snowflake`, `dust`, ...; namespace
     * optional). `null` = legacy definition with no vanilla mapping (nothing is rendered).
     */
    public val vanillaParticle: String? = null,
    /** Vanilla "extra" value (drift speed). */
    public val speed: Double = 0.0,
    /** Dust size; only meaningful for `dust` / `dust_color_transition`. */
    public val size: Double = 1.0,
    /** Typed particle data (block / item / trail / dust transition ...); `null` = legacy `color` + `size` dust. */
    public val data: ParticleData? = null,
    /** Directed-particle motion (`count = 0` trick); `null` = scatter by the shape's spread. */
    public val velocity: ParticleVelocity? = null,
    /** Long-distance render so opponents see skill VFX. */
    public val force: Boolean = true,
    public val viewers: Audience = Audience.ALL,
) {
    init {
        require(count > 0) { "count must be positive: $count" }
        require(lifetimeTicks > 0L) { "lifetimeTicks must be positive: $lifetimeTicks" }
        require(speed >= 0.0) { "speed must be >= 0: $speed" }
        require(size > 0.0) { "size must be > 0: $size" }
    }

    public val bakedShape: ParticleEmitter.BakedShape = ParticleEmitter.bake(shape)

    /**
     * The emission descriptor for this definition. [countOverride] (a mechanic's explicit `count`)
     * replaces [count] when given. Definitions without a [vanillaParticle] keep the legacy mapping
     * (key = id), which the Paper gateway cannot resolve. Sampled shapes and [velocity] need a context:
     * use [ParticleEmitter.plan] for those.
     */
    public fun toParticleSpec(countOverride: Int? = null): ParticleSpec {
        val spread = when (val s = shape) {
            is ParticleShape.Burst -> Vec3(s.spreadBlocks, s.spreadBlocks, s.spreadBlocks)
            is ParticleShape.Sphere -> Vec3(s.radiusBlocks, s.radiusBlocks, s.radiusBlocks)
            is ParticleShape.Circle -> Vec3(s.radiusBlocks, FLAT_SPREAD, s.radiusBlocks)
            is ParticleShape.Line -> Vec3(s.lengthBlocks, FLAT_SPREAD, FLAT_SPREAD)
            is ParticleShape.Ring, is ParticleShape.Arc, is ParticleShape.Helix, ParticleShape.Point -> Vec3.ZERO
        }
        return ParticleSpec(
            key = vanillaParticle ?: id.value,
            count = countOverride ?: count,
            offset = spread,
            extra = speed,
            color = color,
            scale = size.toFloat(),
            data = data,
            force = force,
            viewers = viewers,
        )
    }

    private companion object {
        const val FLAT_SPREAD: Double = 0.1
    }
}

/**
 * RGB color triple in `[0, 255]`. Adapter-side conversion to
 * `org.bukkit.Color` happens in `plugin/core/.../adapter/paper/`.
 */
public data class ParticleColor(public val r: Int, public val g: Int, public val b: Int) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255) {
            "RGB components must be 0..255: ($r,$g,$b)"
        }
    }
}
