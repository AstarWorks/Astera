package com.astarworks.astera.weapons.testfixtures

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.weapons.application.port.outbound.DeliveryResult
import com.astarworks.astera.weapons.application.port.outbound.IWeaponDelivery
import com.astarworks.astera.weapons.domain.weapon.WeaponSpec

/**
 * In-memory [IWeaponDelivery] for use case tests. Records every call and
 * returns a configurable [DeliveryResult].
 *
 * - `gave` accumulates `(target, weapon)` pairs in the order they arrived.
 * - `nextResult` controls what the next [give] returns; tests flip it to
 *   exercise the InventoryFull / UnknownItem branches.
 */
public class FakeWeaponDelivery(
    public var nextResult: DeliveryResult = DeliveryResult.Delivered,
) : IWeaponDelivery {

    public val gave: MutableList<Pair<IMcPlayer, WeaponSpec>> = mutableListOf()

    override fun give(target: IMcPlayer, weapon: WeaponSpec): DeliveryResult {
        gave += target to weapon
        return nextResult
    }
}
