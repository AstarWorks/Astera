package com.astarworks.astera.core.domain.hologram

/**
 * Vendor-neutral mirror of Bukkit
 * `org.bukkit.entity.ItemDisplay.ItemDisplayTransform` (Wave 5h, ADR-0074 §1).
 *
 * Selects which vanilla item-model perspective the ItemDisplay renders with.
 * `FIXED` is the conventional default for static holograms (item floats
 * statically with no perspective tilt). The first-/third-person and head
 * slots reuse the vanilla model variants that the item ships with — useful
 * for replicating "held weapon" silhouettes without spawning an armor stand.
 */
public enum class ItemDisplayMode {
    NONE,
    THIRD_PERSON_LEFT_HAND,
    THIRD_PERSON_RIGHT_HAND,
    FIRST_PERSON_LEFT_HAND,
    FIRST_PERSON_RIGHT_HAND,
    HEAD,
    GUI,
    GROUND,
    FIXED,
}
