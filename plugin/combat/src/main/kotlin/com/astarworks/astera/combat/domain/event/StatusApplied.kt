package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

public data class StatusApplied(
    val target: PlayerId,
    val effect: StatusEffectInstance,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(target.uuid)
}
