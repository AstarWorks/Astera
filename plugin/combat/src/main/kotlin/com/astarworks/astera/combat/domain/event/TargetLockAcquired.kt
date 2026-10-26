package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `ITargetLockController` the tick a ranged target lock
 * completes acquisition (Wave 5combat-depth, ADR-0105).
 *
 * Subscribers:
 *  - HUD (Wave 5j): paint the lock-on reticle over [target].
 *  - Sound: play "lock acquired" SFX (Wave 5sfx path).
 *  - Combat damage pipeline: arm the next-ranged-hit bonus multiplier.
 *
 * @property playerId Player who acquired the lock.
 * @property weaponId Ranged weapon used.
 * @property target Locked entity.
 * @property atTick Server tick at acquisition.
 */
public data class TargetLockAcquired(
    public val playerId: PlayerId,
    public val weaponId: ItemId,
    public val target: EntityHandle,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)

    init {
        require(atTick >= 0L) {
            "TargetLockAcquired.atTick must be >= 0: $atTick"
        }
    }
}
