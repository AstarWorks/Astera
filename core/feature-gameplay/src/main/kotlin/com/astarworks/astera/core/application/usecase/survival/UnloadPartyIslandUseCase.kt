package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.survival.IIslandPersistence
import com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository
import com.astarworks.astera.core.application.port.outbound.survival.IslandPersistenceError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.event.IslandUnloaded
import com.astarworks.astera.core.domain.survival.IslandStatus
import java.time.Clock
import java.time.Instant

/**
 * Release a [PartyIsland]'s world after snapshotting it (Wave 5pve-survival /
 * ADR-0107).
 *
 * Rules:
 *  - Island must exist.
 *  - Status must be [IslandStatus.Active] (idempotent on already-Unloaded
 *    returns success with no event — re-publish would spam audit / Discord).
 *
 * Side effects on success:
 *  - Schematic snapshot persisted via [IIslandPersistence.save].
 *  - Island row flipped to [IslandStatus.Unloaded].
 *  - [IslandUnloaded] published.
 *
 * Snapshot failure surfaces as [IslandError.PlatformFailure] — we do not
 * silently drop islands on save errors, the leader-offline sweeper retries.
 */
public class UnloadPartyIslandUseCase(
    private val islandRepository: IPartyIslandRepository,
    private val persistence: IIslandPersistence,
    private val eventBus: IDomainEventBus,
    private val clock: Clock = Clock.systemUTC(),
) {

    public suspend fun unload(islandId: IslandId, reasonKind: String): Result<Unit, IslandError> {
        require(reasonKind.isNotBlank()) {
            "UnloadPartyIslandUseCase.reasonKind must not be blank"
        }
        val island = islandRepository.find(islandId)
            ?: return Result.failure(IslandError.NotFound(islandId))

        if (island.status == IslandStatus.Unloaded) {
            // Idempotent — already unloaded, no event, no error.
            return Result.success(Unit)
        }
        if (island.status !in IslandStatus.HOT) {
            return Result.failure(
                IslandError.WrongStatus(islandId, IslandStatus.HOT, island.status),
            )
        }

        val saveResult = persistence.save(islandId, island.world)
        if (saveResult is Result.Failure) {
            val detail = describe(saveResult.error)
            return Result.failure(IslandError.PlatformFailure(islandId, detail))
        }

        val now: Instant = clock.instant()
        islandRepository.upsert(island.transitionTo(IslandStatus.Unloaded, now))
        eventBus.publish(
            IslandUnloaded(
                islandId = islandId,
                partyId = island.partyId,
                world = island.world,
                reasonKind = reasonKind,
                occurredAt = now,
            ),
        )
        return Result.success(Unit)
    }

    private fun describe(error: IslandPersistenceError): String = when (error) {
        is IslandPersistenceError.NotFound -> "schematic not found: ${error.islandId}"
        is IslandPersistenceError.SaveFailed -> "save failed: ${error.detail}"
        is IslandPersistenceError.LoadFailed -> "load failed: ${error.detail}"
        is IslandPersistenceError.PlaceFailed -> "place failed: ${error.detail}"
    }
}
