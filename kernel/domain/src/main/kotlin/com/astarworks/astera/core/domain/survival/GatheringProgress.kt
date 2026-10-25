package com.astarworks.astera.core.domain.survival

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Per-(player, spot) harvest record (Wave 5pve-survival / ADR-0107).
 *
 * Used by `HarvestGatheringSpotUseCase` to enforce per-player cooldown +
 * by the analytics roll-up to count gathers per player per spot. Persisted
 * in `gathering_progress` (V42 migration) so progress survives server
 * restart + cross-server hop (ADR-0068 same-spirit).
 *
 * The repository upserts on every harvest; pure tick-precision retention is
 * cheap (one row per `(player, spot)` regardless of harvest count) and
 * lets the cooldown check resolve to a single primary-key lookup.
 */
public data class GatheringProgress(
    val playerId: PlayerId,
    val spotId: GatheringSpotId,
    val lastHarvestedAt: Instant,
    val totalHarvests: Long,
) {
    init {
        require(totalHarvests >= 0L) {
            "GatheringProgress.totalHarvests must be >= 0, was $totalHarvests"
        }
    }

    /**
     * Returns `true` if [now] has advanced at least [cooldownTicks] (1 tick
     * = 50ms) past [lastHarvestedAt]. Cooldown is per-player; per-spot
     * cooldown is layered on top of this in the use case.
     */
    public fun isOffCooldown(now: Instant, cooldownTicks: Long): Boolean {
        val elapsedMillis = now.toEpochMilli() - lastHarvestedAt.toEpochMilli()
        val cooldownMillis = cooldownTicks * MILLIS_PER_TICK
        return elapsedMillis >= cooldownMillis
    }

    /** Advance the progress by one harvest event. */
    public fun recordHarvest(now: Instant): GatheringProgress =
        copy(lastHarvestedAt = now, totalHarvests = totalHarvests + 1L)

    public companion object {
        public const val MILLIS_PER_TICK: Long = 50L

        /** Initial progress when a player harvests a spot for the first time. */
        public fun first(playerId: PlayerId, spotId: GatheringSpotId, now: Instant): GatheringProgress =
            GatheringProgress(
                playerId = playerId,
                spotId = spotId,
                lastHarvestedAt = now,
                totalHarvests = 1L,
            )
    }
}
