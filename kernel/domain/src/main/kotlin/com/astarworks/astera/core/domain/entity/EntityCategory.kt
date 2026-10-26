package com.astarworks.astera.core.domain.entity

/**
 * Vendor-neutral entity taxonomy used by [EntityTypeSpec] to classify the
 * entries returned from [com.astarworks.astera.core.application.port.outbound.entity.IEntityRegistry].
 *
 * Mirrors the variants of [SpawnSpec] one-to-one **plus** EXPERIENCE_ORB and
 * ITEM (which are world-spawned by other systems but listed here so the
 * registry can describe every entity type the server knows about).
 */
public enum class EntityCategory {
    MOB,
    NPC,
    PROJECTILE,
    DISPLAY,
    EXPERIENCE_ORB,
    ITEM,
}

/**
 * Catalog entry describing one entity type known to the platform. The [key]
 * matches the keys used in [SpawnSpec.Mob.typeKey], [SpawnSpec.Projectile.typeKey],
 * and [SpawnSpec.CitizensNpc.typeKey].
 */
public data class EntityTypeSpec(
    public val key: String,
    public val category: EntityCategory,
) {
    init {
        require(key.isNotBlank()) { "EntityTypeSpec.key must not be blank" }
    }
}
