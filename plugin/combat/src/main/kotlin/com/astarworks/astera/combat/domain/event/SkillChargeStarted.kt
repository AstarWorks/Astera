package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `ChargedSkillCastUseCase.startCharge` to signal the start of
 * a hold-to-cast skill window. HUD listeners use this to render a charging
 * bar; SoundDispatcher to play a charge-up SFX.
 */
public data class SkillChargeStarted(
    val caster: PlayerId,
    val skillId: SkillId,
    val chargeTicks: Long,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(caster.uuid)
}
