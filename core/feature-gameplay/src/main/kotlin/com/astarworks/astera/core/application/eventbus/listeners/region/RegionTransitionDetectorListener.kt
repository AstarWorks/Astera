package com.astarworks.astera.core.application.eventbus.listeners.region

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IStageRegionIndex
import com.astarworks.astera.core.domain.event.PlayerEnteredRegion
import com.astarworks.astera.core.domain.event.PlayerExitedRegion
import com.astarworks.astera.core.domain.event.PlayerMoved
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.region.RegionId

/**
 * Subscribe to [PlayerMoved] and derive region transition events
 * (Wave 5block-embed, ADR-0120).
 *
 * # Why this exists
 *
 * Wave 5world-events ships `PlayerMoved` but never derives the
 * higher-level "the player entered region X" / "the player exited region Y"
 * signals. Use cases that need region-scoped behaviour (HUD badge, buff
 * applicator, audit log) had to subscribe to `PlayerMoved` and re-derive
 * the membership delta themselves — repeated logic, easy to get wrong.
 * This listener centralises the derivation.
 *
 * # Behaviour
 *
 *  1. On each [PlayerMoved], compute `regionsBefore = regionsAt(from)`
 *     and `regionsAfter = regionsAt(to)`.
 *  2. The first time we see a player, treat `regionsBefore` as the
 *     listener's cached set instead (so the synthetic baseline doesn't
 *     fire spurious "entered X" events at session start). The cache is
 *     seeded on first [handle] call.
 *  3. Publish [PlayerEnteredRegion] for each id in `regionsAfter \ cached`.
 *     Publish [PlayerExitedRegion] for each id in `cached \ regionsAfter`.
 *  4. Update the cache to `regionsAfter`.
 *
 * # Per-player cache
 *
 * Cached as a plain `MutableMap<PlayerId, Set<RegionId>>`. Sessions stop
 * being tracked once a `PlayerLeftServer` event drops the entry (Wave 6
 * cleanup; until then the map grows with each unique player but Phase 1
 * PoC concurrency caps at a few hundred players per process so the leak
 * is negligible).
 *
 * # Threading
 *
 * Single-threaded; relies on the in-memory event bus dispatching on the
 * Paper main thread.
 */
public class RegionTransitionDetectorListener(
    private val stageRegionIndex: IStageRegionIndex,
    private val eventBus: IDomainEventBus,
) {

    /** Visible-for-tests: per-player last-known region membership. */
    public val lastKnownRegions: MutableMap<PlayerId, Set<RegionId>> = mutableMapOf()

    public fun handle(event: PlayerMoved) {
        val nextSet = stageRegionIndex.regionsAt(event.world, event.to).toSet()
        val cached = lastKnownRegions[event.playerId]
        val priorSet = cached ?: stageRegionIndex.regionsAt(event.world, event.from).toSet()

        val entered = nextSet - priorSet
        val exited = priorSet - nextSet

        for (regionId in entered) {
            eventBus.publish(
                PlayerEnteredRegion(
                    playerId = event.playerId,
                    regionId = regionId,
                    atTick = event.atTick,
                ),
            )
        }
        for (regionId in exited) {
            eventBus.publish(
                PlayerExitedRegion(
                    playerId = event.playerId,
                    regionId = regionId,
                    atTick = event.atTick,
                ),
            )
        }

        lastKnownRegions[event.playerId] = nextSet
    }

    /**
     * Drop the cache entry for [playerId]. Call from a `PlayerLeftServer`
     * listener (Wave 6 wiring) to release per-session state.
     */
    public fun forget(playerId: PlayerId) {
        lastKnownRegions.remove(playerId)
    }
}
