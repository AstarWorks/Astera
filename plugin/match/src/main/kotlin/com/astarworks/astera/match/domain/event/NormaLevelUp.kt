package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/** [playerId] cleared their Norma goal and now stands at [level] (match-scoped; nothing persists). */
public data class NormaLevelUp(
    val matchId: MatchId,
    val playerId: PlayerId,
    val level: Int,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
