package com.astarworks.astera.match.domain.death

import com.astarworks.astera.core.domain.item.ItemId

/**
 * Match-scoped policy for what happens to a victim's inventory when they
 * die to PvP combat in a match (Wave 5game-complete, ADR-0121 §E).
 *
 * # Why a new sealed (separate from PvE [com.astarworks.astera.core.domain.mob.encounter.DeathPenaltyRule])
 *
 * PvE death penalties are zone-scoped (encounter-zone YAML), pick between
 * "safe respawn" or "dump everything on the floor", and only care about a
 * pickup-grace window. PvP death is **per-match**, **per-mode**, and has
 * to express richer "drop equipped weapon only" / "drop a specific item
 * with a probability" rules to support competitive variants:
 *
 *  - **Norma** (objective-collect): rounds are short, kits respawn; full
 *    inventory drops would punish skill-tier players. Default
 *    [KeepInventory].
 *  - **Annihilation** (3-team nexus): mid-game economy hangs on contested
 *    armor / weapon exchanges. Default [DropEquipped] so kills are worth
 *    something but skill-tier loadouts aren't wiped.
 *  - **Bet 1v1**: high-stakes single match; the loser conceding their kit
 *    is part of the wager. Default [DropAll].
 *
 * Sealed interface so the resolver / listener `when` is exhaustive and
 * Phase 2 variants (`DropOnlyTagged(tagSet)`, `DropPercentOfStack(p)`)
 * land as new variants with compile-time fan-out.
 *
 * # Where it's applied
 *
 * `ApplyPvpDeathPolicyListener` subscribes to
 * `combat.domain.event.PlayerKilled`, resolves the rule via
 * `IPvpDeathPolicyResolver`, and translates the decision into
 * `IInventoryMutator.clear(...)` calls (Wave 5world-events port).
 * **Actual item-on-the-floor drops** are a Wave 6 Paper-adapter concern
 * (the resolver only declares what to clear); domain stays I/O-free.
 *
 * # Phase 1 PoC scope
 *
 * The four variants below cover the three Phase 1 PoC modes. The
 * percentage-roll path ([DropOnly]) is shipped now (not Phase 4) because
 * the stage-YAML override hook expects it — stages already in
 * `content/stages/` may declare per-stage `pvp_death:` blocks that need
 * the richer shape.
 */
public sealed interface PvpDeathRule {

    /**
     * Death drops nothing. Match-end rewards still fire. Phase 1 PoC
     * default for Norma — short rounds, kits respawn, kill credit alone
     * is the reward.
     */
    public data object KeepInventory : PvpDeathRule

    /**
     * Death drops every item in the victim's inventory (hotbar + main +
     * armor + off-hand). Bet 1v1 default — losing the wager forfeits
     * the kit.
     */
    public data object DropAll : PvpDeathRule

    /**
     * Death drops only the victim's equipped slots: main-hand weapon,
     * off-hand item, the 4 armor slots. Hotbar 1..8 + main inventory
     * stay with the player. Annihilation default — kills break enemy
     * loadout without erasing it.
     */
    public data object DropEquipped : PvpDeathRule

    /**
     * Death drops a configured subset of items with a per-stack
     * probability. Used by stage YAML overrides that want bespoke
     * "drop the objective item 100% but nothing else" semantics.
     *
     * @property percent rolled per matching item. `1.0f` always drops,
     *   `0.0f` never drops, intermediate values use a deterministic RNG
     *   seeded by `matchId + victim + tick` (Wave 6 adapter).
     * @property includes restrict the rule to these item ids. Empty set
     *   means "every item is a candidate", same as [DropAll] but with a
     *   probability gate.
     */
    public data class DropOnly(
        public val percent: Float,
        public val includes: Set<ItemId> = emptySet(),
    ) : PvpDeathRule {
        init {
            require(percent in 0.0f..1.0f) {
                "PvpDeathRule.DropOnly.percent must be 0.0..1.0, was $percent"
            }
        }
    }
}
