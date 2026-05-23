package com.astarworks.astera.items.application.config.refinement

import com.astarworks.astera.items.domain.refinement.RefinementSpec
import com.astarworks.astera.items.domain.refinement.SubStatKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML DTO for an item's `refinement:` block (Wave 5refine, ADR-0100).
 *
 * Per ADR-0100 §"ItemYamlConfig 拡張 (ADR-0073 upward compatible)" this is a
 * new optional block on each `content/items/<id>.yaml` file. The block lives
 * in the Items plugin (not Core) per the ADR-0022 sibling-isolation matrix:
 * refinement is an Items-side concern, so the DTO + parsing live there.
 *
 * The block is *additive* — items whose YAML omits it stay non-refinable
 * (default [RefinementSpec.DISABLED]).
 *
 * # Wire format
 *
 * ```yaml
 * refinement:
 *   enabled: true
 *   gemstone_slots: 3
 *   reforge_pool: [sharp, fierce, demonic]
 *   upgradeable_to: 10
 *   sub_stat_count: 4
 *   max_rolls_per_stat: 5
 *   candidate_stats: [attack, crit_chance, crit_dmg, defense]
 * ```
 *
 * Konsist forbids `kotlinx.serialization` annotations from `*.domain.*` —
 * that's why this DTO sits in `application/config/`.
 */
@Serializable
public data class RefinementYamlConfig(
    public val enabled: Boolean = false,
    @SerialName("gemstone_slots") public val gemstoneSlots: Int = 0,
    @SerialName("reforge_pool") public val reforgePool: List<String> = emptyList(),
    @SerialName("upgradeable_to") public val upgradeableTo: Int = 0,
    @SerialName("sub_stat_count") public val subStatCount: Int = 0,
    @SerialName("max_rolls_per_stat") public val maxRollsPerStat: Int = 0,
    @SerialName("candidate_stats") public val candidateStats: List<String> = emptyList(),
) {

    /**
     * Convert to the domain [RefinementSpec]. Resolves each `candidate_stats`
     * entry via [SubStatKey.fromYaml]; an unknown key throws
     * `IllegalArgumentException` (caught by the ContentLoader's fail-soft path).
     */
    public fun toDomain(): RefinementSpec {
        val candidateKeys: List<SubStatKey> = candidateStats.map { raw ->
            SubStatKey.fromYaml(raw)
                ?: throw IllegalArgumentException(
                    "RefinementYamlConfig: unknown sub-stat candidate '$raw' " +
                        "(expected one of ${SubStatKey.entries.joinToString { it.yamlKey }})",
                )
        }
        return RefinementSpec(
            enabled = enabled,
            gemstoneSlots = gemstoneSlots,
            reforgePool = reforgePool,
            upgradeableTo = upgradeableTo,
            subStatCount = subStatCount,
            maxRollsPerStat = maxRollsPerStat,
            candidateSubStats = candidateKeys,
        )
    }

    public companion object {
        public val DISABLED: RefinementYamlConfig = RefinementYamlConfig(enabled = false)
    }
}
