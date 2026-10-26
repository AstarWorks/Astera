package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.game.InstanceId

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * [playerId] is no longer a participant of the running [matchId] (they forfeited after the reconnect
 * window, were kicked or banned). Counterpart of [PlayerEnteredMatch]: the per-match inventory snapshot
 * hands the pre-match inventory back at once instead of waiting for the match end (an offline player
 * gets it on their next join).
 */
public data class PlayerLeftMatch(
    val matchId: MatchId,
    val playerId: PlayerId,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Instance(InstanceId(matchId.value))
}
