package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.EventRoute
import com.astarworks.astera.core.domain.game.InstanceId

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when a participant's connection drops mid-match (Wave 5h).
 *
 * Carries the [phase] in which the disconnect happened so downstream subscribers
 * (HUD, audit log, anti-cheat) can decide how to react without re-fetching the
 * match aggregate. The [willTimeoutAtTick] field encodes the reconnect policy:
 *
 * - `null` — the player is being removed immediately. Used during
 *   [MatchPhase.COUNTDOWN] (kick before the match goes live) and as the
 *   terminal event after the 3-min ACTIVE reconnect window elapses.
 * - non-null — the player is held in [com.astarworks.astera.core.domain.player.SessionState.Disconnected]
 *   until this absolute server tick. If the player rejoins before then, a
 *   [PlayerReconnectedToMatch] event fires instead.
 *
 * [MatchPhase.SUDDEN_DEATH] does not surface this event directly — the
 * disconnect listener short-circuits straight to [PlayerForfeited] to avoid
 * a reconnect window during the final stretch (ADR-0079 §6).
 */
public data class PlayerDisconnectedFromMatch(
    val playerId: PlayerId,
    val matchId: MatchId,
    val phase: MatchPhase,
    val willTimeoutAtTick: Long?,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Instance(InstanceId(matchId.value))
}
