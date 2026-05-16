package com.astarworks.astera.application.usecase

import com.astarworks.astera.application.port.outbound.IBroadcaster
import com.astarworks.astera.application.port.outbound.IWeaponRegistry
import com.astarworks.astera.domain.Result
import com.astarworks.astera.domain.event.WeaponFired
import com.astarworks.astera.domain.model.geometry.Vec3
import com.astarworks.astera.domain.model.i18n.MessageKey
import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.weapon.WeaponId

/**
 * Phase 1: publishes [WeaponFired]. Phase 2+ adds damage application,
 * skill activation, and projectile spawn.
 *
 * Cooldown gating happens *before* this use case is invoked (the platform
 * layer's click handler consults `ICooldownTracker`); a use case is "do the
 * thing", not "decide whether to do the thing".
 */
public class FireWeaponUseCase(
    private val broadcaster: IBroadcaster,
    private val weapons: IWeaponRegistry,
) {
    public fun execute(playerId: PlayerId, weaponId: WeaponId, at: Vec3): Result<Unit, FireWeaponError> {
        weapons.find(weaponId) ?: return Result.failure(FireWeaponError.WeaponNotFound(weaponId))
        broadcaster.publish(WeaponFired(playerId, weaponId, at))
        return Result.success(Unit)
    }
}

/** Expected failure modes of [FireWeaponUseCase.execute]. */
public sealed class FireWeaponError {
    public abstract val messageKey: MessageKey

    public data class WeaponNotFound(val weaponId: WeaponId) : FireWeaponError() {
        override val messageKey: MessageKey = MessageKey("astera.weapon.unknown")
    }
}
