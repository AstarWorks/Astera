package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.survival.IGatheringProgressRepository
import com.astarworks.astera.core.application.port.outbound.survival.IGatheringSpotRegistry
import com.astarworks.astera.core.application.port.outbound.survival.IGatheringYieldRoller
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.GatheringSpotHarvested
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.GatheringProgress
import com.astarworks.astera.core.domain.survival.GatheringSkillTrigger
import com.astarworks.astera.core.domain.survival.GatheringSpotId
import com.astarworks.astera.core.domain.survival.matches
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Harvest a [GatheringSpot] for a player (Wave 5pve-survival / ADR-0107).
 *
 * Rules / sequence:
 *  1. Resolve [spotId] in [IGatheringSpotRegistry].
 *  2. Reject the trigger if it does not match the spot's kind
 *     (`matches(spotKind, trigger)`).
 *  3. Enforce per-spot cooldown via an in-instance `ConcurrentHashMap`
 *     so cross-player rate-limit holds without a repository hit.
 *  4. Enforce per-player cooldown via [IGatheringProgressRepository].
 *  5. Roll yields via [IGatheringYieldRoller].
 *  6. Persist `GatheringProgress`.
 *  7. Publish [GatheringSpotHarvested].
 *
 * The per-spot cooldown map is intentionally in-instance (per-use-case
 * lifetime) because spot cooldowns are short (target: seconds) and a
 * server restart resetting them is fine. Per-player progress is durable
 * so analytics + leaderboards survive restart.
 */
public class HarvestGatheringSpotUseCase(
    private val spotRegistry: IGatheringSpotRegistry,
    private val progressRepository: IGatheringProgressRepository,
    private val yieldRoller: IGatheringYieldRoller,
    private val eventBus: IDomainEventBus,
    private val clock: Clock = Clock.systemUTC(),
    private val perPlayerCooldownTicks: Long = DEFAULT_PER_PLAYER_COOLDOWN_TICKS,
) {

    private val spotLastHarvest: ConcurrentHashMap<GatheringSpotId, Instant> = ConcurrentHashMap()

    public suspend fun harvest(
        spotId: GatheringSpotId,
        playerId: PlayerId,
        trigger: GatheringSkillTrigger,
    ): Result<GatheringSpotHarvested, GatheringError> {
        val spot = spotRegistry.find(spotId)
            ?: return Result.failure(GatheringError.UnknownSpot(spotId))

        if (!spot.kind.matches(trigger)) {
            return Result.failure(
                GatheringError.WrongTrigger(
                    spotId = spotId,
                    expected = spot.kind::class.simpleName ?: "unknown",
                    actual = trigger,
                ),
            )
        }

        val now: Instant = clock.instant()
        val spotLast = spotLastHarvest[spotId]
        if (spotLast != null) {
            val elapsedMillis = now.toEpochMilli() - spotLast.toEpochMilli()
            val cooldownMillis = spot.cooldownTicks * GatheringProgress.MILLIS_PER_TICK
            if (elapsedMillis < cooldownMillis) {
                val remainingTicks = (cooldownMillis - elapsedMillis + GatheringProgress.MILLIS_PER_TICK - 1) /
                    GatheringProgress.MILLIS_PER_TICK
                return Result.failure(GatheringError.SpotOnCooldown(spotId, remainingTicks))
            }
        }

        val existing = progressRepository.find(playerId, spotId)
        if (existing != null && !existing.isOffCooldown(now, perPlayerCooldownTicks)) {
            val elapsedMillis = now.toEpochMilli() - existing.lastHarvestedAt.toEpochMilli()
            val cooldownMillis = perPlayerCooldownTicks * GatheringProgress.MILLIS_PER_TICK
            val remainingTicks = (cooldownMillis - elapsedMillis + GatheringProgress.MILLIS_PER_TICK - 1) /
                GatheringProgress.MILLIS_PER_TICK
            return Result.failure(GatheringError.PlayerOnCooldown(spotId, remainingTicks))
        }

        val yields = yieldRoller.roll(spot)
        if (yields.isEmpty()) {
            // The roller produced nothing — treat as cooldown to avoid
            // publishing an empty event (the event invariant forbids it
            // anyway). Use the per-spot cooldown for the back-off.
            return Result.failure(GatheringError.SpotOnCooldown(spotId, spot.cooldownTicks))
        }

        val updatedProgress = existing?.recordHarvest(now)
            ?: GatheringProgress.first(playerId, spotId, now)
        progressRepository.upsert(updatedProgress)
        spotLastHarvest[spotId] = now

        val event = GatheringSpotHarvested(
            spotId = spotId,
            playerId = playerId,
            trigger = trigger,
            world = spot.world,
            rolledYields = yields,
            occurredAt = now,
        )
        eventBus.publish(event)
        return Result.success(event)
    }

    public companion object {
        /** Default per-player cooldown — 1 second (20 ticks). */
        public const val DEFAULT_PER_PLAYER_COOLDOWN_TICKS: Long = 20L
    }
}
