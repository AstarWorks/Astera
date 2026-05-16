package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId

public data class PlayerKilled(
    val victim: PlayerId,
    val killer: PlayerId?,
    val source: DamageSource,
    val atTick: Long,
) : DomainEvent
