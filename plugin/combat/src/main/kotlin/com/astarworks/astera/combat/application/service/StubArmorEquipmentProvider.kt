package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IArmorEquipmentProvider
import com.astarworks.astera.combat.domain.armor.ArmorBundle
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * No-op implementation of [IArmorEquipmentProvider]. Returns `null` for every
 * player so `ApplyDamageUseCase` falls through the armor-reduction modifier
 * with zero side effects.
 *
 * Wave 5a binds this in `AsteraCombatPlugin`; Wave 6 replaces it with a Paper
 * implementation that reads `Player.inventory.armorContents` and resolves each
 * piece via [com.astarworks.astera.combat.application.port.outbound.IArmorRegistry].
 */
public class StubArmorEquipmentProvider : IArmorEquipmentProvider {
    override fun equippedArmor(playerId: PlayerId): ArmorBundle? = null
}
