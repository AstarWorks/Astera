package com.astarworks.astera.domain.model.projectile

/**
 * Vanilla Minecraft projectile entity types Astera can choose as a visual
 * carrier for a projectile. The adapter resolves these to Bukkit's
 * `EntityType` (e.g. `ARROW` → `EntityType.ARROW`).
 *
 * Names are intentionally NOT exhaustively mapped to every vanilla
 * projectile — these are the ones useful for Astera's weapon archetypes.
 */
public enum class VanillaProjectileType {
    ARROW,
    SNOWBALL,
    TRIDENT,
    FIREBALL,
    SMALL_FIREBALL,
    EGG,
    EXPERIENCE_BOTTLE,
}
