package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.match.domain.match.MatchId
import com.astarworks.astera.match.domain.match.TeamId

public data class MatchEnded(
    val matchId: MatchId,
    val winningTeam: TeamId?,
    val atTick: Long,
    val reason: EndReason,
) : DomainEvent
