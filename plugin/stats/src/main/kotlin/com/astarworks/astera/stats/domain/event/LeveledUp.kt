package com.astarworks.astera.stats.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.domain.level.Level
import com.astarworks.astera.stats.domain.level.Xp
import java.time.Instant

public data class LeveledUp(
    val playerId: PlayerId,
    val oldLevel: Level,
    val newLevel: Level,
    val totalXp: Xp,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
