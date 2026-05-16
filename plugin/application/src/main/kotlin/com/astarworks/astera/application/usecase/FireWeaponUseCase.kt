package com.astarworks.astera.application.usecase

import com.astarworks.astera.application.port.outbound.IBroadcaster
import com.astarworks.astera.application.port.outbound.IWeaponRegistry
import com.astarworks.astera.domain.event.WeaponFired
import com.astarworks.astera.domain.model.geometry.Vec3
import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.weapon.WeaponId

/**
 * Phase 1: publishes [WeaponFired]. Phase 2+ adds damage application,
 * cooldown enforcement, and skill activation.
 */
class FireWeaponUseCase(
    private val broadcaster: IBroadcaster,
    private val weapons: IWeaponRegistry,
) {
    /** Returns true if the weapon existed and an event was published. */
    fun execute(playerId: PlayerId, weaponId: WeaponId, at: Vec3): Boolean {
        weapons.find(weaponId) ?: return false
        broadcaster.publish(WeaponFired(playerId, weaponId, at))
        return true
    }
}
