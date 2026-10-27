package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.KnockbackPush
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `ApplyDamageUseCase` when the damage resolution carries an
 * push request and `IKnockbackGateway.applyPush` was invoked.
 *
 * [push] is away-from-attacker with a strength in blocks-per-second world units. Listeners (e.g. particle adapter) read it for visual
 * fidelity.
 */
public data class KnockbackApplied(
    val target: PlayerId,
    val push: KnockbackPush,
    val source: DamageSource,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
