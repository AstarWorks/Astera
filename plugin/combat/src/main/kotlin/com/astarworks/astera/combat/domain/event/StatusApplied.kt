package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId

public data class StatusApplied(
    val target: PlayerId,
    val effect: StatusEffectInstance,
    val atTick: Long,
) : DomainEvent
