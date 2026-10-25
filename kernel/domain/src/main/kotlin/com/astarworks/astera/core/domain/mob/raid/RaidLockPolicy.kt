package com.astarworks.astera.core.domain.mob.raid

/**
 * Policy that gates which players may damage a boss instance (Wave 5pve-boss,
 * ADR-0098).
 *
 * Per Annihilation PoC vision (`docs/competitive-analysis/01-hypixel-skyblock.md`
 * Slayer Tier design + `04-osrs.md` raid lock semantics): raid bosses must
 * be **owned** by a single party so loot and kill credit are unambiguous.
 * The policy lives in domain because the open-world spawn path needs to
 * decide ownership at spawn time, and the damage gateway needs to decide
 * rejection at hit time — both pure-domain decisions.
 *
 * Sealed interface (not enum) so we can ship variants that carry data —
 * `PartyLock` needs the max-size cap; future variants (e.g. `GuildLock`,
 * `LevelGated`) will carry their own params.
 */
public sealed interface RaidLockPolicy {

    /**
     * No lock — any player may damage the boss. Suitable for world bosses
     * + tutorial boss instances + admin spawns.
     */
    public data object Open : RaidLockPolicy

    /**
     * Party-locked encounter. The first party whose member damages the boss
     * "claims" it; other players cannot damage until claim is released
     * (boss despawn, party disband, server restart). [maxPartySize] is the
     * upper bound on the claiming party's size (HSB Slayer = 1, OSRS raid =
     * 4-5, Astera convention: 4).
     */
    public data class PartyLock(public val maxPartySize: Int) : RaidLockPolicy {
        init {
            require(maxPartySize >= 1) {
                "PartyLock.maxPartySize must be >= 1: $maxPartySize"
            }
            require(maxPartySize <= 8) {
                "PartyLock.maxPartySize must be <= 8 (Astera Phase 1 cap): $maxPartySize"
            }
        }
    }

    public companion object {
        /** Canonical "no lock" sentinel — equivalent to [Open]. */
        public val OPEN: RaidLockPolicy = Open
    }
}
