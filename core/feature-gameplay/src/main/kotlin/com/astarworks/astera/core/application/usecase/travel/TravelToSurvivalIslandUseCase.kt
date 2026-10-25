package com.astarworks.astera.core.application.usecase.travel

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandManager
import com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerArrivedAtIsland
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.IslandTemplateId
import com.astarworks.astera.core.domain.survival.IslandVisibility

/**
 * Teleport a player to their party's survival island, creating one on the
 * fly if the party does not yet have one (Wave 5wireup, ADR-0116 §G14).
 *
 * # Lookup chain
 *
 *  1. Resolve the player's party via [IPartyRepository.forPlayer]. Missing →
 *     [TravelError.NoParty].
 *  2. Resolve the existing island via [IPartyIslandRepository.forParty]. If
 *     present, call [IPartyIslandManager.load] (no-op when already Active).
 *  3. If absent, call [IPartyIslandManager.create] with the configured
 *     [defaultTemplateId]. The returned [PartyIsland] is then loaded.
 *  4. Teleport into the island world at the template's spawn coordinate (the
 *     manager publishes [com.astarworks.astera.core.domain.event.PartyIslandCreated]
 *     on create; this use case publishes [PlayerArrivedAtIsland] on the
 *     successful teleport).
 *
 * # Spawn coordinate
 *
 * Phase 1 PoC uses a hard-coded spawn near `(0.5, 70.0, 0.5)` inside the
 * loaded island world. Templates carry a `spawnOffset` but resolving the
 * island's origin requires the schematic loader — deferred to Wave 6 where
 * the Paper-side schematic placement actually runs. Until then, the
 * teleport target is the world default-spawn area; the player can use
 * `/astera travel lobby` to come back if the spawn is unreachable.
 */
public class TravelToSurvivalIslandUseCase(
    private val parties: IPartyRepository,
    private val islands: IPartyIslandRepository,
    private val islandManager: IPartyIslandManager,
    private val worldGateway: IWorldGateway,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
    private val defaultTemplateId: IslandTemplateId = IslandTemplateId(DEFAULT_TEMPLATE_ID),
    private val defaultMaxMembers: Int = DEFAULT_MAX_MEMBERS,
    private val defaultVisibility: IslandVisibility = IslandVisibility.PartyOnly,
) {

    public fun travel(playerId: PlayerId): Result<Unit, TravelError> {
        val party = parties.forPlayer(playerId)
            ?: return Result.failure(TravelError.NoParty)

        val existing = islands.forParty(party.id)
        val island = if (existing != null) {
            existing
        } else {
            when (val created = islandManager.create(
                partyId = party.id,
                createdBy = playerId,
                templateId = defaultTemplateId,
                maxMembers = defaultMaxMembers,
                visibility = defaultVisibility,
            )) {
                is Result.Success -> created.value
                is Result.Failure -> return Result.failure(
                    TravelError.IslandLoadFailed(created.error::class.simpleName ?: "Unknown"),
                )
            }
        }

        // Bring the island world online (no-op when already Active).
        when (val loaded = islandManager.load(island.id)) {
            is Result.Success -> {
                // worldId returned by load() is the live one — prefer it over
                // the cached island.world in case the manager re-mapped on
                // load.
                val worldId = loaded.value
                val teleported = worldGateway.teleport(
                    playerId = playerId,
                    at = DEFAULT_ISLAND_SPAWN,
                    world = worldId,
                )
                if (!teleported) return Result.failure(TravelError.TeleportFailed)

                eventBus.publish(
                    PlayerArrivedAtIsland(
                        playerId = playerId,
                        islandId = island.id,
                        atTick = scheduler.currentTick(),
                    ),
                )
                return Result.success(Unit)
            }
            is Result.Failure -> return Result.failure(
                TravelError.IslandLoadFailed(loaded.error::class.simpleName ?: "Unknown"),
            )
        }
    }

    public companion object {
        public const val DEFAULT_TEMPLATE_ID: String = "vanilla-skyblock"
        public const val DEFAULT_MAX_MEMBERS: Int = 4
        public val DEFAULT_ISLAND_SPAWN: Vec3 = Vec3(0.5, 70.0, 0.5)
    }
}
