package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.combat.CombatContext
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

public data class PlayerKilled(
    val victim: PlayerId,
    val killer: PlayerId?,
    val source: DamageSource,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
    /** Where it happened (ADR-0237); default `Match` is for tests - every production source sets it. */
    val context: CombatContext = CombatContext.Match(),
) : DomainEvent
