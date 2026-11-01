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
 * Vendor-neutral particle effect definition (Wave 5f; one dialect with the skill `particle` op, ADR-0312).
 *
 * A [ParticleDefinition] is one named preset: the vanilla `spawnParticle` arguments the `particle` op takes
 * ([particle], [count], [offset], [extra], [data]) plus the def-only emission geometry ([shape], [velocity]).
 * The domain stays free of Bukkit / Paper types.
 *
 * Lives in `plugin/core` because every feature plugin (combat skill effects, match phase stings, economy
 * reward sprinkles) consumes a single `IParticleRegistry` lookup via Core's `ServiceRegistry`.
 *
 * # Compared to [ParticleSpec]
 *
 * [ParticleSpec] is the **call-site** emission descriptor (one-off spawn at a `Vec3`). [ParticleDefinition]
 * is the **catalog entry** (named preset loaded from YAML). [ParticleEmitter.plan] turns a definition
 * lookup into [ParticleSpec] emissions.
 */
public data class ParticleDefinition(
    public val id: ParticleId,
    /** Vanilla particle type this definition emits (`flame`, `snowflake`, `dust`, ...; namespace optional). */
    public val particle: String,
    public val count: Int,
    /** Vanilla `offset`, in world axes: the gaussian spread box, or the direction when `count` is 0. Zero when a [shape] samples the positions. */
    public val offset: Vec3 = Vec3.ZERO,
    /** Vanilla `extra` (drift speed). */
    public val extra: Double = 0.0,
    /** Typed particle data (dust colour / size, block, item, trail, ...). Dust-class particles without it render in the client default. */
    public val data: ParticleData? = null,
    /** Sampled emission geometry (circle / line / sphere / ring / arc / helix); `null` = emit at the anchor. Exclusive with a non-zero [offset]. */
    public val shape: ParticleShape? = null,
    /** Directed-particle motion (`count = 0` trick); `null` = scatter by [offset]. */
    public val velocity: ParticleVelocity? = null,
    public val displayNameKey: MessageKey? = null,
    /** Long-distance render so opponents see skill VFX. */
    public val force: Boolean = true,
    public val viewers: Audience = Audience.ALL,
) {
    init {
        require(particle.isNotBlank()) { "particle must not be blank" }
        require(count > 0) { "count must be positive: $count" }
        require(extra >= 0.0) { "extra must be >= 0: $extra" }
        require(shape == null || offset == Vec3.ZERO) { "a sampled shape and a non-zero offset are exclusive: the shape already places the particles" }
    }

    public val bakedShape: ParticleEmitter.BakedShape = ParticleEmitter.bake(shape)

    /**
     * The emission descriptor for this definition. [countOverride] (a mechanic's explicit `count`)
     * replaces [count] when given. Sampled shapes and [velocity] need a context: use [ParticleEmitter.plan] for those.
     */
    public fun toParticleSpec(countOverride: Int? = null): ParticleSpec {
        val spread = when (val s = shape) {
            null -> offset
            is ParticleShape.Sphere -> Vec3(s.radiusBlocks, s.radiusBlocks, s.radiusBlocks)
            is ParticleShape.Circle -> Vec3(s.radiusBlocks, FLAT_SPREAD, s.radiusBlocks)
            is ParticleShape.Line -> Vec3(s.lengthBlocks, FLAT_SPREAD, FLAT_SPREAD)
            is ParticleShape.Ring, is ParticleShape.Arc, is ParticleShape.Helix -> Vec3.ZERO
        }
        return ParticleSpec(
            key = particle,
            count = countOverride ?: count,
            offset = spread,
            extra = extra,
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
