package com.astarworks.astera.stats.domain.event

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.norma.NormaReward
import java.time.Instant

public data class MissionCompleted(
    val playerId: PlayerId,
    val normaId: NormaId,
    val reward: NormaReward,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
