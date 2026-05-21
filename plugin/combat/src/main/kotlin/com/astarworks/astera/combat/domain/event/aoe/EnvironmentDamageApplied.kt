package com.astarworks.astera.combat.domain.event.aoe

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Domain event published when an [DamageSource.Environment]-flavoured
 * damage has been resolved against a player (Wave 5m / ADR-0088).
 *
 * Fired by Wave 6a's BukkitEventAdapter on every `EntityDamageEvent`
 * whose source is environmental. Analytics + the future achievement
 * system subscribe here for "first lava death" / "100m fall survivor"
 * style metrics without polling.
 *
 * @property target The damaged player.
 * @property source The specific environmental cause (one of the
 *   [DamageSource.Environment] sub-variants).
 * @property finalDamage Damage actually applied (after armor / element
 *   matrix / etc.).
 * @property atTick Server tick at which the damage hit.
 */
public data class EnvironmentDamageApplied(
    public val target: PlayerId,
    public val source: DamageSource.Environment,
    public val finalDamage: Double,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    init {
        require(finalDamage >= 0.0) {
            "EnvironmentDamageApplied.finalDamage must be >= 0: $finalDamage"
        }
    }
}
