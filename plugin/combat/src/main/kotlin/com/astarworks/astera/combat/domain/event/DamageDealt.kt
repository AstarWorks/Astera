package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId

public data class DamageDealt(
    val attacker: PlayerId?,
    val target: PlayerId,
    val resolved: ResolvedDamage,
    val source: DamageSource,
) : DomainEvent
