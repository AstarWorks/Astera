package com.astarworks.astera.core.application.service.entity

import com.astarworks.astera.core.application.port.outbound.entity.IEntityRegistry
import com.astarworks.astera.core.domain.entity.EntityCategory
import com.astarworks.astera.core.domain.entity.EntityTypeSpec

/**
 * Default [IEntityRegistry] — built from a static list at construction.
 *
 * Wave 5l ships a small starter catalog covering the entities Astera's
 * current YAML content references (Wave 5d barriers, Wave 5e spawn-point
 * decoration, etc.). Wave 6a's Paper-side `PaperEntityRegistry` will
 * supersede this with the full Bukkit `EntityType` enumeration registered
 * at priority 100 — until then this in-memory list is the single source
 * of truth for the spawner's [com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner.spawn]
 * type-key validation.
 *
 * Keys are stored case-insensitively (lowercased on insert, lowercased on
 * lookup).
 */
public class InMemoryEntityRegistry(
    entries: Collection<EntityTypeSpec> = DEFAULT_ENTRIES,
) : IEntityRegistry {

    private val byKey: Map<String, EntityTypeSpec> = entries.associateBy { it.key.lowercase() }

    override fun resolve(typeKey: String): EntityTypeSpec? =
        byKey[typeKey.lowercase()]

    override fun all(): Collection<EntityTypeSpec> = byKey.values

    public companion object {
        /**
         * Minimal starter catalog. Intentionally short — Wave 6a Paper
         * registry overlays this with the full Bukkit `EntityType` set.
         */
        public val DEFAULT_ENTRIES: List<EntityTypeSpec> = listOf(
            // Generic vanilla mobs Astera's PoC content references.
            EntityTypeSpec("minecraft:zombie", EntityCategory.MOB),
            EntityTypeSpec("minecraft:skeleton", EntityCategory.MOB),
            EntityTypeSpec("minecraft:creeper", EntityCategory.MOB),
            EntityTypeSpec("minecraft:villager", EntityCategory.NPC),

            // Vanilla projectiles routed via SpawnSpec.Projectile.
            EntityTypeSpec("minecraft:arrow", EntityCategory.PROJECTILE),
            EntityTypeSpec("minecraft:fireball", EntityCategory.PROJECTILE),
            EntityTypeSpec("minecraft:snowball", EntityCategory.PROJECTILE),

            // Display family (1.19.4+) used by SpawnSpec.DisplayNpc.
            EntityTypeSpec("minecraft:text_display", EntityCategory.DISPLAY),
            EntityTypeSpec("minecraft:item_display", EntityCategory.DISPLAY),
            EntityTypeSpec("minecraft:block_display", EntityCategory.DISPLAY),

            // ArmorStand backing the minimal NPC adapter.
            EntityTypeSpec("minecraft:armor_stand", EntityCategory.NPC),

            // Loot / xp routed by other systems but listed here so admin
            // tooling can describe them.
            EntityTypeSpec("minecraft:experience_orb", EntityCategory.EXPERIENCE_ORB),
            EntityTypeSpec("minecraft:item", EntityCategory.ITEM),
        )
    }
}
