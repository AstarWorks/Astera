package com.astarworks.astera.core.domain.mob.encounter

/**
 * Zone-scoped policy controlling what happens when a player dies inside an
 * [EncounterZone] (Wave 5pve-content, ADR-0099).
 *
 * Sealed interface (not enum) so future variants can carry data — e.g. a
 * `CurrencyLoss(amount)` variant or `ItemDropOnDeath(itemSelector)`. Phase 1
 * ships the two HSB / OSRS-equivalent variants the PoC zones need:
 *
 *  - [SafeDeath] — respawn at zone hub, no item loss (HSB Slayer tier-1,
 *    Annihilation tutorial encounters)
 *  - [ItemDropOnDeath] — respawn at hub with inventory dropped at death
 *    site (OSRS PvM, HSB Slayer tier-4+ hard mode); a respawn-grace tick
 *    window protects the dropped pile so only the dying player can pick
 *    up within the grace window
 *
 * The rule is **declarative** — actual respawn placement / inventory drop
 * mechanics live in the Wave 6b death-handler service (Paper layer). This
 * domain record is the policy decision input.
 *
 * # Why a sealed interface, not a Boolean + count
 *
 * The PoC scope is two variants; a Boolean would conflate "should drop
 * items" with "is a safe death" and force every caller to special-case
 * the count parameter only valid for one variant. The sealed interface
 * makes the variant set explicit and exhaustive `when` blocks call out
 * unhandled cases at compile time.
 *
 * # Why grace ticks live on the variant, not a separate service field
 *
 * Different zones may want different grace windows (a high-stakes raid
 * could ship `graceTicks = 600` (30 s) while a tutorial drops to 100
 * (5 s)). Keeping the value on the variant lets the YAML zone author
 * tune per-encounter without touching any code.
 *
 * # Why this lives in [EncounterZone] sibling, not its own subpackage
 *
 * The rule is conceptually a zone property — it never makes sense outside
 * the context of an encounter, and pulling it into a separate package
 * would force [EncounterZone] to cross a subpackage boundary just to
 * reference it. Living in `encounter` keeps the data + the rule that
 * operates on it co-located, matching the [DifficultyCurve] precedent.
 */
public sealed interface DeathPenaltyRule {

    /**
     * Safe death: player respawns at the zone hub, inventory preserved.
     * Suitable for tutorial zones, low-tier slayer content, training rooms.
     */
    public data object SafeDeath : DeathPenaltyRule

    /**
     * Item drop on death: player's inventory is dropped at the death site
     * when they die. Drops are protected (only the dying player can pick
     * up) for [graceTicks] server ticks, after which any nearby player may
     * claim them.
     *
     * @property graceTicks Tick window (≥0) during which dropped items are
     *   reserved for the original owner. Default 600 ticks (30 s at 20 tps).
     */
    public data class ItemDropOnDeath(
        public val graceTicks: Int = DEFAULT_GRACE_TICKS,
    ) : DeathPenaltyRule {
        init {
            require(graceTicks >= 0) {
                "ItemDropOnDeath.graceTicks must be >= 0: $graceTicks"
            }
            require(graceTicks <= MAX_GRACE_TICKS) {
                "ItemDropOnDeath.graceTicks must be <= $MAX_GRACE_TICKS (5 min cap): $graceTicks"
            }
        }

        public companion object {
            /** 30 s @ 20 tps — matches HSB / OSRS pickup grace. */
            public const val DEFAULT_GRACE_TICKS: Int = 600

            /** 5 min hard cap to prevent ops-side typo abandoning items. */
            public const val MAX_GRACE_TICKS: Int = 6000
        }
    }

    public companion object {
        /** Canonical "safe death" sentinel. */
        public val SAFE: DeathPenaltyRule = SafeDeath
    }
}
