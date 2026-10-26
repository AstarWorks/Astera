package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import java.time.Instant

/**
 * Published whenever a player's current HP changes via
 * [com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway].
 *
 * NOT published by the combat pipeline — combat publishes `DamageDealt` (Wave 5a).
 *
 * Wave 5k (ADR-0080).
 */
public data class PlayerHealthChanged(
    val playerId: PlayerId,
    val oldHp: Double,
    val newHp: Double,
    val source: HealthChangeSource,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
