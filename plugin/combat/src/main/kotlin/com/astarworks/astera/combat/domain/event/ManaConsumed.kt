package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.combat.domain.skill.SkillId
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `ConsumeManaUseCase` when mana is successfully deducted from
 * a player's pool. Listeners (HUD / audit) read [remaining] and [source].
 */
public data class ManaConsumed(
    val playerId: PlayerId,
    val amount: Long,
    val remaining: Long,
    val source: ManaConsumptionSource,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent

/** Discriminator for what spent the mana. Reserved for future kinds (Buff, Toggle, etc.). */
public sealed class ManaConsumptionSource {
    public data class Skill(val skillId: SkillId) : ManaConsumptionSource()
}
