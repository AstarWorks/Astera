package com.astarworks.astera.items.application.config.refinement

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level wrapper for one `content/refinement/<id>.yaml` file (Wave 5refine,
 * ADR-0100 §"YAML schema").
 *
 * Standalone per-item refinement profile. The `id` field is the kebab-case
 * [com.astarworks.astera.core.domain.item.ItemId.value] this profile applies
 * to; the body is one [RefinementYamlConfig].
 *
 * # Why a wrapper
 *
 * `content/refinement/<id>.yaml` files are dropped flat in one directory; the
 * filename does not have to match the id (designers may want grouping). The
 * wrapper carries the id so the loader can register against the right
 * [com.astarworks.astera.core.domain.item.ItemId] without relying on the
 * filename. This mirrors the `content/skills/` and `content/shops/` patterns.
 *
 * # Wire format
 *
 * ```yaml
 * id: example-sword
 * profile:
 *   enabled: true
 *   gemstone_slots: 3
 *   reforge_pool: [sharp, fierce, demonic]
 *   upgradeable_to: 10
 *   sub_stat_count: 4
 *   max_rolls_per_stat: 5
 *   candidate_stats: [attack, crit_chance, crit_dmg, defense]
 * ```
 */
@Serializable
public data class RefinementProfileYaml(
    public val id: String,
    @SerialName("profile") public val profile: RefinementYamlConfig = RefinementYamlConfig.DISABLED,
)
