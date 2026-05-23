package com.astarworks.astera.items.application.config.glow

import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.items.domain.glow.GlowProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level wrapper for `content/flex/glow-profile.yaml` (Wave 5flex /
 * ADR-0102 surface #1).
 *
 * # Why this DTO is application-layer
 *
 * Per ADR-0022 / ADR-0073 the domain
 * ([com.astarworks.astera.items.domain.glow.GlowProfile]) must stay free
 * of `kotlinx.serialization`. This wrapper translates the YAML wire
 * shape into the pure domain via [toDomain].
 *
 * # Wire format
 *
 * ```yaml
 * # content/flex/glow-profile.yaml
 * profiles:
 *   - rarity: COMMON
 *     particle_tick_period: 0
 *     particle_count: 0
 *     glint_override: false
 *     visible_from_blocks: 0
 *   - rarity: LEGENDARY
 *     particle_tick_period: 10
 *     particle_count: 4
 *     glint_override: true
 *     visible_from_blocks: 16
 * ```
 *
 * Missing entries fall back to [GlowProfile.DEFAULTS] inside the
 * registry — ops only need to declare tiers they wish to override.
 */
@Serializable
public data class GlowProfileFileYaml(
    public val profiles: List<GlowProfileYaml> = emptyList(),
) {
    public fun toDomain(): List<GlowProfile> = profiles.map { it.toDomain() }
}

@Serializable
public data class GlowProfileYaml(
    public val rarity: String,
    @SerialName("particle_tick_period") public val particleTickPeriod: Int,
    @SerialName("particle_count") public val particleCount: Int,
    @SerialName("glint_override") public val glintOverride: Boolean = false,
    @SerialName("visible_from_blocks") public val visibleFromBlocks: Int = 0,
) {
    public fun toDomain(): GlowProfile {
        val tier = runCatching { Rarity.valueOf(rarity) }.getOrElse {
            error("GlowProfileYaml.rarity must be one of ${Rarity.entries}: got '$rarity'")
        }
        return GlowProfile(
            rarity = tier,
            particleTickPeriod = particleTickPeriod,
            particleCount = particleCount,
            glintOverride = glintOverride,
            visibleFromBlocks = visibleFromBlocks,
        )
    }
}
