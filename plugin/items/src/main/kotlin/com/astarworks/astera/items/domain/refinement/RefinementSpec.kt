package com.astarworks.astera.items.domain.refinement

/**
 * Per-item refinement capability declaration (Wave 5refine, ADR-0100).
 *
 * Lives on the item template (sibling of
 * [com.astarworks.astera.core.domain.item.ItemSpec]). Read by:
 *
 *   - [com.astarworks.astera.items.application.usecase.refinement.ApplyRefinementUseCase]
 *     to validate "is this layer even legal on this item?"
 *   - [com.astarworks.astera.items.application.usecase.refinement.RerollSubStatsUseCase]
 *     to know how many rolls / which candidate stats are allowed.
 *   - YAML round-trip in
 *     [com.astarworks.astera.items.application.config.refinement.RefinementYamlConfig].
 *
 * # The 4-category 厳選 matrix (HSB 8-layer / 原神 / MH / PoE)
 *
 *   - [gemstoneSlots] — HSB Hot Potato Book / Gemstone slot count. Players
 *     slot independent gemstones; each slot multiplies its host stat.
 *   - [reforgePool] — HSB Reforge Stone-style modifier pool. Re-roll picks
 *     one from the pool with weights left to the loot service.
 *   - [upgradeableTo] — HSB Star (1..N) progression. Linear ramp, no RNG
 *     (resource sink only).
 *   - [subStatCount] / [maxRollsPerStat] / [candidateSubStats] — 原神
 *     聖遺物 sub-stat layer. RNG-driven, swap-on-reroll (Warframe Riven
 *     反例 教訓 per ADR-0100).
 *
 * # Invariants
 *
 *   - All numeric fields are >= 0; zero means "this axis disabled".
 *   - [subStatCount] <= [candidateSubStats].size (cannot roll more sub-stats
 *     than candidates).
 *
 * # Why one record, multiple axes
 *
 * The four 厳選 categories are independent and compose multiplicatively
 * (ADR-0100 §Decision). Folding them into one declaration keeps the YAML
 * shape flat and the use cases trivial to combine.
 */
public data class RefinementSpec(
    public val enabled: Boolean = false,
    public val gemstoneSlots: Int = 0,
    public val reforgePool: List<String> = emptyList(),
    public val upgradeableTo: Int = 0,
    public val subStatCount: Int = 0,
    public val maxRollsPerStat: Int = 0,
    public val candidateSubStats: List<SubStatKey> = emptyList(),
) {
    init {
        require(gemstoneSlots >= 0) {
            "RefinementSpec.gemstoneSlots must be >= 0, got $gemstoneSlots"
        }
        require(upgradeableTo >= 0) {
            "RefinementSpec.upgradeableTo must be >= 0, got $upgradeableTo"
        }
        require(subStatCount >= 0) {
            "RefinementSpec.subStatCount must be >= 0, got $subStatCount"
        }
        require(maxRollsPerStat >= 0) {
            "RefinementSpec.maxRollsPerStat must be >= 0, got $maxRollsPerStat"
        }
        require(subStatCount <= candidateSubStats.size || candidateSubStats.isEmpty()) {
            "RefinementSpec.subStatCount ($subStatCount) cannot exceed candidateSubStats.size " +
                "(${candidateSubStats.size}); narrow the YAML candidate_stats list."
        }
    }

    /** Convenience: any non-zero axis declares this item refinable. */
    public val anyAxisEnabled: Boolean
        get() = gemstoneSlots > 0 ||
            reforgePool.isNotEmpty() ||
            upgradeableTo > 0 ||
            subStatCount > 0

    public companion object {
        /** Disabled spec — the default for items whose YAML omits the `refinement:` block. */
        public val DISABLED: RefinementSpec = RefinementSpec(enabled = false)
    }
}
