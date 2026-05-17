package com.astarworks.astera.stats.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.domain.norma.NormaId
import com.astarworks.astera.stats.domain.norma.NormaReward

public data class MissionCompleted(
    val playerId: PlayerId,
    val normaId: NormaId,
    val reward: NormaReward,
) : DomainEvent
