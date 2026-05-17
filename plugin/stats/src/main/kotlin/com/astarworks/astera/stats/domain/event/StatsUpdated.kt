package com.astarworks.astera.stats.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

public data class StatsUpdated(
    val playerId: PlayerId,
    val kind: Kind,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    public enum class Kind {
        Kill,
        Death,
        Assist,
        DamageDealt,
        DamageTaken,
        MatchPlayed,
        Win,
        Loss,
    }
}
