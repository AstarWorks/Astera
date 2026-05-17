package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.match.domain.match.MatchId
import com.astarworks.astera.match.domain.match.TeamId
import java.time.Instant

public data class MatchEnded(
    val matchId: MatchId,
    val winningTeam: TeamId?,
    val atTick: Long,
    val reason: EndReason,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
