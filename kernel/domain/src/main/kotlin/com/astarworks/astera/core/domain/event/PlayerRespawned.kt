package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.SpawnPoint
import java.time.Instant

/**
 * Fired after a player has been successfully respawned by the Wave 5o
 * `RespawnUseCase` (ADR-0081).
 *
 * Carries the resolved [spawnPoint] (post-strategy) and the optional
 * [matchId] (`null` for free-roam respawns outside a match — admin /kill,
 * void death in lobby world).
 */
public data class PlayerRespawned(
    public val playerId: PlayerId,
    public val matchId: MatchId?,
    public val spawnPoint: SpawnPoint,
    public val atTick: Long,
    public override val version: Int = 1,
    public override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
