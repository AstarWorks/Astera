package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.combat.application.port.outbound.IArmorEquipmentProvider
import com.astarworks.astera.combat.domain.armor.ArmorBundle
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Test fixture: per-player in-memory armor bundle. Default empty so the
 * basic ApplyDamage tests don't need to set armor.
 */
public class FakeArmorEquipmentProvider(
    private val bundles: MutableMap<PlayerId, ArmorBundle> = mutableMapOf(),
) : IArmorEquipmentProvider {
    override fun equippedArmor(playerId: PlayerId): ArmorBundle? = bundles[playerId]

    public fun set(playerId: PlayerId, bundle: ArmorBundle) {
        bundles[playerId] = bundle
    }
}
