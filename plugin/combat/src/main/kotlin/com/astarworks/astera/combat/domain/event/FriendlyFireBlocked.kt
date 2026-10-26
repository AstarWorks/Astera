package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `ApplyDamageUseCase` when the [FriendlyFirePolicy] blocked a
 * damage attempt. Provides an audit trail for grief / TK detection — without
 * this event, a silently-dropped DamageDealt would have no explanation.
 *
 * Only emitted when an attacker can be identified (so environmental damage
 * never fires this).
 */
public data class FriendlyFireBlocked(
    val attacker: PlayerId,
    val intendedTarget: PlayerId,
    val source: DamageSource,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(attacker.uuid)
}
