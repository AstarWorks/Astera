package com.astarworks.astera.core.domain.item

/**
 * Where an [ItemSpec] is worn / held (Wave _items-1, ADR-0073).
 *
 * Vendor-neutral mirror of Bukkit's `EquipmentSlot` — domain consumers branch
 * on this enum, the Paper adapter maps to/from `EquipmentSlot` at the
 * boundary. Astera's inventory model never lets a single item bind to
 * multiple slots; YAML emits exactly one.
 */
public enum class EquipSlot {
    MAINHAND,
    OFFHAND,
    HELMET,
    CHESTPLATE,
    LEGGINGS,
    BOOTS,
}
