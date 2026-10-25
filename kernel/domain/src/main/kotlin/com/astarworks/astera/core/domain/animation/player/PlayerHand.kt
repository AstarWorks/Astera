package com.astarworks.astera.core.domain.animation.player

/**
 * Which hand a player swung (Wave 5animation, ADR-0113).
 *
 * Vendor-neutral mirror of Bukkit `EquipmentSlot.{HAND, OFF_HAND}` but
 * scoped to the swing-animation domain — the broader [com.astarworks.astera.core.domain.item.EquipSlot]
 * carries armor slots that are irrelevant here. The Paper adapter (Wave 6)
 * maps `EquipmentSlot.HAND` ↔ [MainHand] and `EquipmentSlot.OFF_HAND` ↔
 * [OffHand] at the boundary.
 *
 * Why not reuse `EquipSlot`?
 * - `EquipSlot` includes 4 armor slots that have no swing animation.
 * - Tightening the type at the port surface forces callers to think about
 *   "which hand is swinging" rather than "which slot is being acted on".
 * - Konsist-friendlier: `core.domain.animation.*` stays pure (ADR-0113)
 *   without dragging `item.*` in for an enum that's a strict subset.
 */
public enum class PlayerHand {
    /** Main hand — the slot the player's hotbar selector currently points to. */
    MainHand,

    /** Off-hand — the dedicated off-hand slot (slot index 40 in Minecraft). */
    OffHand,
}
