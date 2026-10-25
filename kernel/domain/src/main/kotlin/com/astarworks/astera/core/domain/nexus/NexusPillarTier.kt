package com.astarworks.astera.core.domain.nexus

/**
 * Which of the three pillars in a 3-pillar Nexus this entity represents
 * (ADR-0108, Wave 5annihilation).
 *
 * Astera Annihilation runs **3 pillars per team** (vs Hypixel Annihilation's
 * single Nexus) — each pillar offers a "graceful degradation" defensive
 * checkpoint:
 *
 *  - **Tier 1 (outer)**: first pillar to fall. Loss debuffs *respawn timing*
 *    of the owning team (Wave 5 PoC: +2s on top of base 10s).
 *  - **Tier 2 (middle)**: loss debuffs *resource regen* (Ammo / Stamina /
 *    Mana — Wave 5k integration). Owning team's effective per-tick regen
 *    drops by a configured multiplier.
 *  - **Tier 3 (core)**: the Nexus proper. Loss = team eliminated. Last team
 *    standing wins.
 *
 * Pillar ordering is total: Tier 1 → 2 → 3 must fall in order. The domain
 * pins this via [AnnihilationMatchState.canDamage] (returns false for tiers
 * above an alive lower tier). Out-of-order damage attempts are a no-op at
 * the framework layer — the listener (Wave 6 paper adapter) is expected to
 * cancel the underlying block break, but the domain stays defensive in
 * case the listener wires up later.
 *
 * The numeric value matches the on-disk YAML `tier:` field (1/2/3) and the
 * SQL `pillar_tier` column in `annihilation_match_stats`.
 */
public enum class NexusPillarTier(public val ordinalValue: Int) {

    /** Outer-most pillar; first to fall. Loss → respawn-timing debuff. */
    TIER_1(1),

    /** Middle pillar; falls after Tier 1. Loss → resource-regen debuff. */
    TIER_2(2),

    /** Core pillar (= the Nexus proper); falls last. Loss → team elimination. */
    TIER_3(3),
    ;

    public companion object {
        /** Resolve a tier from its numeric ordinal (1/2/3); null on invalid input. */
        public fun fromOrdinal(value: Int): NexusPillarTier? =
            entries.firstOrNull { it.ordinalValue == value }

        /** Total order: [TIER_1] < [TIER_2] < [TIER_3]. */
        public fun ordered(): List<NexusPillarTier> = listOf(TIER_1, TIER_2, TIER_3)
    }
}
