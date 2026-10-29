package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/** [playerId] met their Norma goal: the level rises only when they run the Norma check at their own base. */
public data class NormaCheckReady(
    val matchId: MatchId,
    val playerId: PlayerId,
    /** The level the check would clear. */
    val level: Int,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
