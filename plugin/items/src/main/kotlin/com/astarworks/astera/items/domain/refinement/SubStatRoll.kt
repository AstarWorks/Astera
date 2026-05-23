package com.astarworks.astera.items.domain.refinement

/**
 * One sub-stat roll line on an [ItemInstance] (Wave 5refine, ADR-0100).
 *
 * 原神聖遺物 sub-stat model: each refined instance carries up to N
 * [SubStatRoll] entries, each accumulated through up to
 * [RefinementSpec.maxRollsPerStat] independent reroll grains. The final
 * applied magnitude is [magnitude] — the use case sums the grains client-side
 * (in `RerollSubStatsUseCase`) before constructing the value here.
 *
 * @property rollCount how many grains have been folded into [magnitude] so
 *   far. Useful for UI display ("ATK +124 (3/5)") and to enforce the
 *   `max_rolls_per_stat` ceiling.
 *
 * @property magnitude the *delta* the sub-stat contributes — additive on the
 *   raw stat. The instance's [com.astarworks.astera.core.domain.attribute.IAttributeContainer]
 *   stores this as a `NumberValue` under [SubStatKey.containerKey].
 *
 * # Why no `weight` here
 *
 * RNG weighting (how often a given [SubStatKey] is picked) lives in the
 * randomizer / loot service, not the per-instance state. The roll record only
 * captures the realised outcome.
 */
public data class SubStatRoll(
    public val key: SubStatKey,
    public val magnitude: Double,
    public val rollCount: Int,
) {
    init {
        require(magnitude >= 0.0) {
            "SubStatRoll.magnitude must be >= 0, got $magnitude (key=$key)"
        }
        require(rollCount >= 1) {
            "SubStatRoll.rollCount must be >= 1, got $rollCount (key=$key)"
        }
    }
}
