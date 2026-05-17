package com.astarworks.astera.stats.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * XP granted to a player. `newLevel` is a raw [Int] (not a value class) until
 * the δ worker introduces a `Level` value type in Stage 2.
 */
public data class XpGranted(
    val playerId: PlayerId,
    val amount: Long,
    val newTotal: Long,
    val newLevel: Int?,
    val source: XpSource,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
