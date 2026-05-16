package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.match.domain.match.MatchId
import com.astarworks.astera.match.domain.match.MatchPhase

public data class PhaseChanged(
    val matchId: MatchId,
    val from: MatchPhase,
    val to: MatchPhase,
    val atTick: Long,
) : DomainEvent
