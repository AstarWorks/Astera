package com.astarworks.astera.core.application.port.outbound.mob

import com.astarworks.astera.core.domain.mob.MobRegionId
import com.astarworks.astera.core.domain.mob.MobTypeId

/**
 * Outbound port: open-world mob spawn density / cap / tier-progression
 * (Wave 5pve, ADR-0085).
 *
 * Phase 1 commits the port shape only — the in-memory default
 * ([com.astarworks.astera.core.application.service.mob.StaticMobSpawnLadder])
 * carries a flat per-region cap and a single tier. Phase 4's
 * `TimeBucketSpawnLadder` adds tier progression over wall-clock time
 * (Wave 5c Star Generator's "open world content escalation" flavour).
 *
 * # Why a separate port
 *
 * The spawner ([IMobSpawner]) answers "spawn this mob now"; the ladder
 * answers "*which* mobs should I be spawning, where, and how many at once".
 * Open-world spawn loops query the ladder for the per-region candidate set
 * + cap, then call the spawner for each candidate slot.
 *
 * # Integration points
 *
 * - PvP match worlds: per-match config (Wave 5n) may set `maxConcurrent = 0`
 *   to suppress all open-world mob spawns inside a match world. The PvE
 *   spawn loop consults the ladder for the per-region cap; the per-match
 *   override comes from a separate config layer.
 * - Open-world hub: each region's ladder entry carries the candidate set
 *   and per-region cap. The spawn loop tries candidates against
 *   [com.astarworks.astera.core.domain.mob.SpawnConditions] until the cap
 *   is hit.
 */
public interface IMobSpawnLadder {

    /**
     * The mobs eligible to spawn in [regionId] right now. Returns an empty
     * list iff the region has no spawn entries OR the current tier yields
     * no candidates. The spawn loop picks candidates round-robin (or by
     * weighted roll — strategy is loop-side).
     */
    public fun candidatesFor(regionId: MobRegionId): List<MobTypeId>

    /**
     * Maximum concurrent live mob count for [regionId]. The spawn loop
     * gates on this cap before calling [IMobSpawner.spawn]. `0` means
     * "suppress spawning entirely in this region" (e.g. PvP match world).
     */
    public fun maxConcurrent(regionId: MobRegionId): Int

    /**
     * Snapshot of every region the ladder knows about. Used by admin
     * commands for tab-completion and by analytics for "where can mobs
     * spawn" introspection.
     */
    public fun knownRegions(): Set<MobRegionId>
}

