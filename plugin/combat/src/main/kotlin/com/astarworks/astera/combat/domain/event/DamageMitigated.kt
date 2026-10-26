package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.MitigationReason
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `ApplyDamageUseCase` once per
 * [com.astarworks.astera.combat.domain.damage.MitigationApplied] recorded
 * during the [com.astarworks.astera.combat.domain.rule.DamageRule] pipeline.
 *
 * NOTE: emitted **once per mitigation source**, not once per damage event —
 * armor with 4 slots equipped fires 4 of these, a set bonus fires a 5th, etc.
 * Aggregate at the listener side if you need a single per-hit signal.
 */
public data class DamageMitigated(
    val target: PlayerId,
    val originalAmount: Double,
    val finalAmount: Double,
    val reason: MitigationReason,
    val source: DamageSource,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(target.uuid)
}
