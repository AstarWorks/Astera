package com.astarworks.astera.core.domain.survival

import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Skyblock-style party-shared instance (Wave 5pve-survival / ADR-0107).
 *
 * Owned by exactly one [PartyId] (the `Party` aggregate from Wave 5e);
 * up to [maxMembers] party members may load + build on it. When every
 * leader-class member is offline the leader-offline detector flips
 * [status] to [IslandStatus.Unloaded] and the world handle is released —
 * the schematic + plot rows are preserved for instant re-load.
 *
 * # Identity
 *
 * [IslandId] is the persistent SSOT (defined in `domain/backup/`, Wave 5w
 * stub elevated to live here). [WorldId] is the runtime world handle —
 * stable per load cycle but can change across `unload` → `load` round-
 * trips when the platform picks a new world name.
 *
 * # Lifetime
 *
 * `createdAt` is set once and never mutated. `lastActiveAt` advances on
 * every load / member visit; the 7-day archival sweep reads this
 * column to decide cold-storage eligibility (Phase 4 archival job
 * deferred — see [IslandStatus.Archived]).
 *
 * # Immutability
 *
 * Like every other Astera aggregate, mutations return a new [PartyIsland]
 * via `copy(...)`. Repositories upsert the whole record per change.
 */
public data class PartyIsland(
    val id: IslandId,
    val partyId: PartyId,
    val world: WorldId,
    val templateId: IslandTemplateId,
    val maxMembers: Int,
    val visibility: IslandVisibility,
    val status: IslandStatus,
    val createdAt: Instant,
    val lastActiveAt: Instant,
) {
    init {
        require(maxMembers in IslandTemplate.MIN_MEMBERS..IslandTemplate.MAX_MEMBERS) {
            "PartyIsland.maxMembers must be in [${IslandTemplate.MIN_MEMBERS}..${IslandTemplate.MAX_MEMBERS}], was $maxMembers"
        }
        require(!createdAt.isAfter(lastActiveAt)) {
            "PartyIsland.lastActiveAt ($lastActiveAt) must not precede createdAt ($createdAt)"
        }
    }

    /**
     * Returns a copy with [status] set to [next] and [lastActiveAt] bumped
     * to [now]. Convenience for the unload sweeper / load path so callers
     * don't forget to update the timestamp.
     */
    public fun transitionTo(next: IslandStatus, now: Instant): PartyIsland =
        copy(status = next, lastActiveAt = now)
}
