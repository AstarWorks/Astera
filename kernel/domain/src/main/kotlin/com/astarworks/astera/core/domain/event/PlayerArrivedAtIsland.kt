package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Player successfully teleported onto their party's survival island (Wave
 * 5wireup, ADR-0116 §G14).
 *
 * Emitted by `TravelToSurvivalIslandUseCase` after the island load + the
 * teleport both succeed. Distinct from `IslandUnloaded` / `PartyIslandCreated`
 * which fire on island lifecycle changes (not on visits).
 */
public data class PlayerArrivedAtIsland(
    val playerId: PlayerId,
    val islandId: IslandId,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
