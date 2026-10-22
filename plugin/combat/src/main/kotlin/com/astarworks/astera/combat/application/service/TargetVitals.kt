package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IEntityDamageGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Host-neutral read of a target's health for the HealthBelow condition and the
 * "skip dead targets" rule (WP4). Mobs report through [IEntityDamageGateway];
 * players through Astera's canonical HP ([IPlayerHealthGateway], ADR-0149).
 * Both are resolved lazily because the owning plugins register them after
 * Combat wires.
 *
 * Unknown health (`null`) is treated as alive and never "below" anything.
 */
internal class TargetVitals(
    private val playerHealth: () -> IPlayerHealthGateway?,
    private val entityHealth: () -> IEntityDamageGateway?,
) {
    fun health(id: PlayerId): Double? {
        val entity = entityHealth()
        if (entity != null && entity.isEntityTarget(id)) return entity.hitPoints(id)
        return playerHealth()?.getCurrentHealth(id)
    }

    fun maxHealth(id: PlayerId): Double? {
        val entity = entityHealth()
        if (entity != null && entity.isEntityTarget(id)) return entity.maxHitPoints(id)
        return playerHealth()?.getMaxHealth(id)
    }

    fun isAlive(id: PlayerId): Boolean = (health(id) ?: 1.0) > 0.0

    /** `health / maxHealth`, or `null` when either is unknown. */
    fun fraction(id: PlayerId): Double? {
        val hp = health(id) ?: return null
        val max = maxHealth(id)?.takeIf { it > 0.0 } ?: return null
        return hp / max
    }
}
