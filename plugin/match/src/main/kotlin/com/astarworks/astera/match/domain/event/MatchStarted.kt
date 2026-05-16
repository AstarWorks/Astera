package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.match.domain.match.MatchId
import com.astarworks.astera.match.domain.stage.StageId

public data class MatchStarted(
    val matchId: MatchId,
    val stageId: StageId,
    val atTick: Long,
) : DomainEvent
