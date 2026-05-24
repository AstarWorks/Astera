package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.application.port.outbound.survival.IIslandPersistence
import com.astarworks.astera.core.application.port.outbound.survival.IIslandTemplateRegistry
import com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository
import com.astarworks.astera.core.application.port.outbound.survival.IslandPersistenceError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.survival.IslandStatus
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Clock
import java.time.Instant

/**
 * Bring a [PartyIsland] back online (Wave 5pve-survival / ADR-0107).
 *
 * Rules:
 *  - Island must exist.
 *  - Already-Active is a no-op success (returns the live [WorldId]).
 *  - Archived is currently rejected — Phase 4 will graft a thaw step.
 *
 * Side effects on success:
 *  - Schematic placed via [IIslandPersistence.place] (skipped on already-Active).
 *  - Island row flipped to [IslandStatus.Active] + `lastActiveAt` bumped.
 *
 * No event published — the [com.astarworks.astera.core.domain.event.PartyIslandCreated]
 * fires only on initial create. Subsequent loads are operational and audit-
 * recorded by the platform's join listener.
 */
public class LoadPartyIslandUseCase(
    private val islandRepository: IPartyIslandRepository,
    private val templateRegistry: IIslandTemplateRegistry,
    private val persistence: IIslandPersistence,
    private val clock: Clock = Clock.systemUTC(),
) {

    public fun load(islandId: IslandId): Result<WorldId, IslandError> {
        val island = islandRepository.find(islandId)
            ?: return Result.failure(IslandError.NotFound(islandId))

        if (island.status == IslandStatus.Active) {
            // Already live — just bump lastActiveAt as a heartbeat for the
            // archival sweeper.
            islandRepository.upsert(island.copy(lastActiveAt = clock.instant()))
            return Result.success(island.world)
        }
        if (island.status == IslandStatus.Archived) {
            return Result.failure(
                IslandError.WrongStatus(islandId, IslandStatus.HOT, island.status),
            )
        }

        // Status == Unloaded: resolve schematic + place.
        val template = templateRegistry.find(island.templateId)
        // Only place if a saved snapshot exists. A freshly-created island that
        // was unloaded before any persist would have nothing to place; allow
        // the path to proceed (template re-stamp is Phase 4).
        if (persistence.exists(islandId)) {
            val offset = template?.spawnOffset ?: Vec3.ZERO
            val placeResult = persistence.place(islandId, island.world, offset)
            if (placeResult is Result.Failure) {
                return Result.failure(
                    IslandError.PlatformFailure(islandId, describe(placeResult.error)),
                )
            }
        }

        val now: Instant = clock.instant()
        islandRepository.upsert(island.transitionTo(IslandStatus.Active, now))
        return Result.success(island.world)
    }

    private fun describe(error: IslandPersistenceError): String = when (error) {
        is IslandPersistenceError.NotFound -> "schematic not found"
        is IslandPersistenceError.SaveFailed -> "save failed: ${error.detail}"
        is IslandPersistenceError.LoadFailed -> "load failed: ${error.detail}"
        is IslandPersistenceError.PlaceFailed -> "place failed: ${error.detail}"
    }
}
