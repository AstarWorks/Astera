package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.stats.PlayerStats

/**
 * Wave 5e (ADR-0058 §5) container-facade rewrite. Mutates STAT_DAMAGE_TAKEN
 * / STAT_DAMAGE_DEALT through the container on the [PlayerStats] facade.
 */
public class RewardOnDamageListener(
    private val repository: IPlayerStatsRepository,
) {
    public fun handle(event: DamageDealt) {
        if (!event.context.countsForProgression) return // ADR-0235: dummies / lobby hits leave no stats
        val dmg = event.resolved.finalAmount.toLong()

        // Update target's damageTaken
        val targetStats = repository.find(event.target) ?: PlayerStats.empty(event.target)
        targetStats.container.set(
            Attributes.STAT_DAMAGE_TAKEN,
            AttributeValue.NumberValue((targetStats.damageTaken + dmg).toDouble()),
        )
        repository.upsert(targetStats)

        // Update attacker's damageDealt (if attacker is a player)
        val attackerId = event.attacker ?: return
        val attackerStats = repository.find(attackerId) ?: PlayerStats.empty(attackerId)
        attackerStats.container.set(
            Attributes.STAT_DAMAGE_DEALT,
            AttributeValue.NumberValue((attackerStats.damageDealt + dmg).toDouble()),
        )
        repository.upsert(attackerStats)
    }
}
