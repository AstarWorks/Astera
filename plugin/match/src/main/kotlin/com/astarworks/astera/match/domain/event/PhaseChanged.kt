package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import java.time.Instant

public data class PhaseChanged(
    val matchId: MatchId,
    val from: MatchPhase,
    val to: MatchPhase,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
