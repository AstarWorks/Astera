package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.EventRoute
import com.astarworks.astera.core.domain.game.InstanceId

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Fired by [com.astarworks.astera.match.application.usecase.spectator.EnterSpectatorUseCase]
 * after a player successfully transitions into spectator mode for a given
 * match (Wave 5match-hud, ADR-0118).
 *
 * # Why a new event
 *
 * Round 10 in-match-experience audit revealed there was no domain event for
 * "player became a spectator" — the use case mutated `IPlayerSessionRepository`
 * and called `IWorldGateway.spectatorSpawn` directly, leaving downstream
 * subscribers (HUD, freelook, audit) with nowhere to hook in. The Wave 5h
 * design did not lose this event on purpose; it was simply never authored.
 *
 * # Carried payload
 *
 * - [playerId]  — the spectator.
 * - [matchId]   — the match they are spectating.
 * - [atTick]    — server tick of the transition.
 */
public data class PlayerEnteredSpectator(
    public val playerId: PlayerId,
    public val matchId: MatchId,
    public val atTick: Long,
    public override val version: Int = 1,
    public override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Instance(InstanceId(matchId.value))
}
