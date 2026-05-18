package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `ApplyDamageUseCase` when a damage resolution flagged a
 * critical hit. Emitted *before* the main [DamageDealt] so listeners can
 * pre-decorate the kill-feed / HUD without re-parsing the resolution.
 */
public data class CriticalHit(
    val attacker: PlayerId?,
    val target: PlayerId,
    val multiplier: Double,
    val source: DamageSource,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
