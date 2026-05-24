package com.astarworks.astera.match.application.config.gamemode.annihilation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML wire shape for `content/nexus-templates/<id>.yaml` (ADR-0108,
 * Wave 5annihilation).
 *
 * A Nexus template describes the **structure** of a 3-pillar Nexus
 * deployment — where each tier sits relative to the team's base spawn,
 * what block / model materialises each tier, and any per-tier HP override.
 *
 * Wave 5 ships the schema + an example template; the Phase 6 Paper adapter
 * consumes the template at match-world generation time to materialise the
 * pillars in-world. The Phase 1 PoC tracker does **not** read this file —
 * it seeds pillars at the placeholder position `(0, 64, 0)` and relies on
 * `INexusEntity` to carry the real world position. Wiring the YAML through
 * to actual block placement is a Wave 6 concern.
 *
 * ## Schema
 *
 * ```yaml
 * id: default-3pillar                 # required; lower-kebab-case
 * name_key: astera.gamemode.annihilation.template.default
 *
 * tiers:
 *   - tier: 1                         # 1 / 2 / 3
 *     block_material: minecraft:obsidian
 *     offset:                         # relative to base spawn (block coords)
 *       x: 4
 *       y: 1
 *       z: 0
 *     hp_override: 500                # optional; falls back to AnnihilationConfig.pillarHp
 *   - tier: 2
 *     block_material: minecraft:crying_obsidian
 *     offset:
 *       x: 6
 *       y: 1
 *       z: 0
 *   - tier: 3
 *     block_material: minecraft:beacon
 *     offset:
 *       x: 8
 *       y: 2
 *       z: 0
 *     hp_override: 800                 # the Nexus proper is beefier in this template
 * ```
 *
 * @property id Stable template id (lower-kebab-case); referenced from
 *   stage YAML or Match config.
 * @property nameKey i18n key for the player-facing template name.
 * @property tiers Per-tier offset + block material. Exactly 3 entries
 *   expected (one per [com.astarworks.astera.core.domain.nexus.NexusPillarTier]);
 *   loader validates this at `toSpec()` time.
 */
@Serializable
public data class NexusTemplateYamlConfig(
    val id: String,
    @SerialName("name_key")
    val nameKey: String,
    val tiers: List<NexusTierYamlConfig>,
) {

    public fun toSpec(): NexusTemplateSpec {
        require(id.isNotBlank()) { "NexusTemplate.id must not be blank" }
        require(tiers.size == 3) {
            "NexusTemplate '$id' must declare exactly 3 tiers (got ${tiers.size})"
        }
        val byTier = tiers.associateBy { it.tier }
        require(byTier.keys == setOf(1, 2, 3)) {
            "NexusTemplate '$id' must declare tiers 1, 2, 3 exactly once each (got ${byTier.keys})"
        }
        return NexusTemplateSpec(
            id = id,
            nameKey = nameKey,
            tiers = tiers.sortedBy { it.tier },
        )
    }
}

@Serializable
public data class NexusTierYamlConfig(
    val tier: Int,
    @SerialName("block_material")
    val blockMaterial: String,
    val offset: OffsetYamlConfig,
    @SerialName("hp_override")
    val hpOverride: Int? = null,
) {
    init {
        require(tier in 1..3) { "NexusTier.tier must be 1, 2, or 3 (got $tier)" }
        require(blockMaterial.isNotBlank()) { "NexusTier.block_material must not be blank" }
        if (hpOverride != null) {
            require(hpOverride > 0) { "NexusTier.hp_override must be > 0 if set (got $hpOverride)" }
        }
    }
}

@Serializable
public data class OffsetYamlConfig(
    val x: Int,
    val y: Int,
    val z: Int,
)

/** Validated, in-memory view of a [NexusTemplateYamlConfig]. */
public data class NexusTemplateSpec(
    public val id: String,
    public val nameKey: String,
    public val tiers: List<NexusTierYamlConfig>,
)
