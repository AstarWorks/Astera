package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `ApplyDamageUseCase` when the damage resolution carries an
 * impulse vector and `IKnockbackGateway.applyImpulse` was invoked.
 *
 * [impulse] is in blocks-per-second world units (same convention as the
 * Knockback formula). Listeners (e.g. particle adapter) read it for visual
 * fidelity.
 */
public data class KnockbackApplied(
    val target: PlayerId,
    val impulse: Vec3,
    val source: DamageSource,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
