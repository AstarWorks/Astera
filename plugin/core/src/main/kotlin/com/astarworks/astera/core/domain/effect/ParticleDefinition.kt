package com.astarworks.astera.core.domain.effect

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
) {
    init {
        require(count > 0) { "count must be positive: $count" }
        require(lifetimeTicks > 0L) { "lifetimeTicks must be positive: $lifetimeTicks" }
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
