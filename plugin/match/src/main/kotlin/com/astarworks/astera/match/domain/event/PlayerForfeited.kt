package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.EventRoute
import com.astarworks.astera.core.domain.game.InstanceId

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when a participant forfeits the match — voluntarily or as a
 * consequence of disconnect policy (Wave 5h).
 *
 * Distinct from [PlayerDisconnectedFromMatch]:
 * - Disconnect is "pending — may rejoin"; forfeit is "permanent, counts as loss".
 * - The disconnect listener escalates straight to forfeit during
 *   [com.astarworks.astera.core.domain.match.MatchPhase.SUDDEN_DEATH] (ADR-0079 §6).
 *
 * Stats / Economy subscribe to this so the player's per-match record reflects
 * a loss-by-forfeit rather than a regular DNF. Stage 5 may also penalise rating.
 */
public data class PlayerForfeited(
    val playerId: PlayerId,
    val matchId: MatchId,
    /** The team they belonged to when they left (null when the roster did not list them). */
    val teamId: TeamId?,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Instance(InstanceId(matchId.value))
}
