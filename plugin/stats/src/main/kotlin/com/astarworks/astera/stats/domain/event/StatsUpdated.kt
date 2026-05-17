package com.astarworks.astera.stats.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId

public data class StatsUpdated(
    val playerId: PlayerId,
    val kind: Kind,
) : DomainEvent {
    public enum class Kind {
        Kill,
        Death,
        Assist,
        DamageDealt,
        DamageTaken,
    }
}
