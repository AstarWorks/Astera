package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.armor.ArmorBundle
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound: which armor is the player currently wearing?
 *
 * Wave 5a binds to an in-memory stub (`StubArmorEquipmentProvider` returns
 * empty bundle). Wave 6's Paper impl reads `Player.inventory.armorContents`
 * and resolves each piece via `IArmorRegistry`.
 */
public interface IArmorEquipmentProvider {
    public fun equippedArmor(playerId: PlayerId): ArmorBundle?
}
