package com.astarworks.astera.combat.domain.armor

/**
 * The four armor equipment slots. Mirrors vanilla Minecraft's slot taxonomy
 * so Wave 6's `IArmorEquipmentProvider` Paper impl can map 1:1 from
 * `EquipmentSlot.HEAD/CHEST/LEGS/FEET`.
 */
public enum class ArmorSlot {
    HELMET,
    CHESTPLATE,
    LEGGINGS,
    BOOTS,
}
