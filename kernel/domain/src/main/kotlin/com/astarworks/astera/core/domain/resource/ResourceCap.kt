package com.astarworks.astera.core.domain.resource

/**
 * Per-type ceiling + regen rate config (Wave 5k, ADR-0080).
 *
 * Held by the `IResourcePoolService` for fast read access.
 *
 * The values here apply when no equipped item overrides them — when a
 * weapon's [com.astarworks.astera.core.domain.item.ItemSpec.resourceMaxPool]
 * or [com.astarworks.astera.core.domain.item.ItemSpec.resourceRegenPerSecond]
 * is non-default, the weapon's value wins for the active resource.
 */
public data class ResourceCap(
    val maxPool: Int,
    val regenPerSecond: Double,
) {
    init {
        require(maxPool >= 0) { "maxPool must be >= 0, got $maxPool" }
        require(regenPerSecond.isFinite() && regenPerSecond >= 0.0) {
            "regenPerSecond must be finite and >= 0, got $regenPerSecond"
        }
    }

    public companion object {
        /** Default cap table when no YAML override exists. */
        public val DEFAULTS: Map<ResourceType, ResourceCap> = mapOf(
            ResourceType.Ammo to ResourceCap(maxPool = 30, regenPerSecond = 0.0),
            ResourceType.Energy to ResourceCap(maxPool = 100, regenPerSecond = 15.0),
        )
    }
}
