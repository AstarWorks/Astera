package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.effect.ParticleSkin
import com.astarworks.astera.core.domain.effect.ParticleSkinTint
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/particle-skins/<vanilla-type>.yaml` (ADR-0252). The frame PNGs and the vanilla
 * sprite list are generated from it by `:tools:item-art` and shipped by the resource-pack builder.
 *
 * ```yaml
 * type: entity_effect   # vanilla particle type whose sprite list is replaced
 * channel: ember        # texture names astera_ember_<i>
 * source: ember         # :tools:item-art painter
 * frames: 8
 * tint: grey            # grey | baked
 * size: 64              # optional
 * ```
 */
@Serializable
public data class ParticleSkinYamlConfig(
    public val type: String,
    public val channel: String,
    public val source: String,
    public val frames: Int,
    public val tint: String,
    public val size: Int = ParticleSkin.DEFAULT_SIZE,
) {
    /** Throws [IllegalArgumentException] on an invalid field; the contents loader counts that as a failed load. */
    public fun toDefinition(): ParticleSkin = ParticleSkin(
        vanillaType = type,
        channel = channel,
        source = source,
        frames = frames,
        tint = when (tint.lowercase()) {
            "grey" -> ParticleSkinTint.GREY
            "baked" -> ParticleSkinTint.BAKED
            else -> throw IllegalArgumentException("Unknown tint '$tint' (expected: grey/baked)")
        },
        size = size,
    )
}
