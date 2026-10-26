package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.game.InstanceId

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * [playerId] was registered as a participant of [matchId] (queue formation,
 * `/astera match entry`, cross-server arrival). Published by the entry use case
 * **before** the player is teleported or kitted, so subscribers (the per-match
 * inventory snapshot, F3-12) see the pre-match inventory.
 */
public data class PlayerEnteredMatch(
    val matchId: MatchId,
    val playerId: PlayerId,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Instance(InstanceId(matchId.value))
}
