package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.core.domain.stats.PlayerStats

public class RewardOnDamageListener(
    private val repository: IPlayerStatsRepository,
) {
    public fun handle(event: DamageDealt) {
        val dmg = event.resolved.finalAmount.toLong()

        // Update target's damageTaken
        val targetStats = repository.find(event.target) ?: PlayerStats.empty(event.target)
        repository.upsert(targetStats.copy(damageTaken = targetStats.damageTaken + dmg))

        // Update attacker's damageDealt (if attacker is a player)
        val attackerId = event.attacker ?: return
        val attackerStats = repository.find(attackerId) ?: PlayerStats.empty(attackerId)
        repository.upsert(attackerStats.copy(damageDealt = attackerStats.damageDealt + dmg))
    }
}
