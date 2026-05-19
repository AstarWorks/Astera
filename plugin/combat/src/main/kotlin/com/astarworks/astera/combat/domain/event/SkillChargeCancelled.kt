package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `ChargedSkillCastUseCase.cancelCharge` when a charging skill
 * is cancelled before [SkillChargeStarted.chargeTicks] elapsed (player
 * interrupt, target lost, etc.). [elapsedTicks] is how long the cancellation
 * happened after [SkillChargeStarted].
 */
public data class SkillChargeCancelled(
    val caster: PlayerId,
    val skillId: SkillId,
    val elapsedTicks: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
