package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.stage.StageId
import java.time.Instant

public data class MatchStarted(
    val matchId: MatchId,
    val stageId: StageId,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
