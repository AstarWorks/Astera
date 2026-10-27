package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IArmorEquipmentProvider
import com.astarworks.astera.combat.application.port.outbound.IArmorRegistry
import com.astarworks.astera.combat.application.port.outbound.IWornItems
import com.astarworks.astera.combat.domain.armor.ArmorBundle
import com.astarworks.astera.combat.domain.armor.ArmorId
import com.astarworks.astera.combat.domain.armor.ArmorSlot
import com.astarworks.astera.combat.domain.armor.ArmorSpec
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.item.ARMOR_PIECE_TAG
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Real [IArmorEquipmentProvider]: every worn Astera item whose YAML names an `armor_piece` contributes that
 * `content/armor/` piece, but only in the slot the piece is authored for (an item in the wrong slot gives nothing).
 */
public class ItemArmorEquipmentProvider(
    private val worn: IWornItems,
    private val items: () -> IItemRegistry?,
    private val armors: IArmorRegistry,
) : IArmorEquipmentProvider {
    override fun equippedArmor(playerId: PlayerId): ArmorBundle? {
        val registry = items() ?: return null
        val equipped = HashMap<ArmorSlot, ArmorSpec>()
        for ((slot, itemId) in worn.wornItems(playerId)) {
            val pieceId = registry.find(itemId)?.pdcTags?.get(ARMOR_PIECE_TAG) ?: continue
            val piece = armors.find(ArmorId(pieceId)) ?: continue
            if (piece.slot == slot) equipped[slot] = piece
        }
        return ArmorBundle(playerId, equipped)
    }
}
