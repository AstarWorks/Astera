package com.astarworks.astera.core.application.service.mob

import com.astarworks.astera.core.application.port.outbound.mob.IMobSpawnLadder
import com.astarworks.astera.core.domain.mob.MobRegionId
import com.astarworks.astera.core.domain.mob.MobTypeId

/**
 * Phase 1 default impl of [IMobSpawnLadder] (Wave 5pve, ADR-0085).
 *
 * Flat per-region config carried in-memory. Operators register entries at
 * boot (typically from a YAML config in `content/mobs/spawn-ladder.yaml`,
 * parsed in Wave 6b); tests register directly via [register]. Phase 4
 * supersedes with a time-bucketed impl (`TimeBucketSpawnLadder`).
 *
 * Empty-by-default — the absence of a region entry means "no spawning
 * here". The PvE spawn loop must therefore explicitly register every
 * region it wants populated; this prevents accidental open-world spawning
 * in match worlds.
 */
public class StaticMobSpawnLadder : IMobSpawnLadder {

    private data class Entry(val candidates: List<MobTypeId>, val cap: Int)

    private val entries: MutableMap<MobRegionId, Entry> = mutableMapOf()

    override fun candidatesFor(regionId: MobRegionId): List<MobTypeId> =
        entries[regionId]?.candidates ?: emptyList()

    override fun maxConcurrent(regionId: MobRegionId): Int =
        entries[regionId]?.cap ?: 0

    override fun knownRegions(): Set<MobRegionId> = entries.keys.toSet()

    /**
     * Register an entry for [regionId]. Re-registering replaces the prior
     * entry (last-write-wins, matching the [com.astarworks.astera.core.application.port.outbound.mob.IMobRegistry]
     * reload semantics).
     *
     * @param candidates Eligible mob ids — pass empty to disable the region
     *   without removing it from [knownRegions].
     * @param cap Non-negative concurrent cap. `0` = suppress spawning.
     */
    public fun register(regionId: MobRegionId, candidates: List<MobTypeId>, cap: Int) {
        require(cap >= 0) { "StaticMobSpawnLadder.cap must be non-negative: $cap" }
        entries[regionId] = Entry(candidates, cap)
    }

    /** Test-only: drop all state. */
    public fun clear() {
        entries.clear()
    }
}
