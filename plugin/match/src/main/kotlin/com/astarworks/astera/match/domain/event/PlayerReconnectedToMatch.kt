package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when a previously [com.astarworks.astera.core.domain.player.SessionState.Disconnected]
 * participant rejoins their match within the reconnect window (Wave 5h).
 *
 * Driven by `ReconnectDuringMatchListener` reacting to `PlayerJoinedServer`.
 * Downstream subscribers can update HUDs ("X has reconnected"), audit logs,
 * and any cross-server bookkeeping that wants to undo the pause the
 * [PlayerDisconnectedFromMatch] event triggered.
 */
public data class PlayerReconnectedToMatch(
    val playerId: PlayerId,
    val matchId: MatchId,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
