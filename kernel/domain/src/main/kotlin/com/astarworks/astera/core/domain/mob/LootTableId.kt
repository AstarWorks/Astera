package com.astarworks.astera.core.domain.mob

/**
 * Stable identifier for a loot table referenced by a [MobSpec.lootTable]
 * (Wave 5pve, ADR-0085).
 *
 * Loot tables live outside this Wave's deliverable shape — the Phase 4
 * `IPveLootDistributor` resolves the id against a separate `ILootTableRegistry`
 * port (introduced when `content/loot/` lands). For Wave 5pve we ship only
 * the id type so [MobSpec] can reference loot without baking in a list of
 * `ItemId` (which would couple the spec to a static drop table and prevent
 * weighted / conditional drops later).
 *
 * Naming rule: same namespaced lower-kebab-case shape as [MobTypeId].
 */
@JvmInline
public value class LootTableId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "LootTableId must be namespaced lower-kebab-case (e.g., astera:forest-wolf-drops): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9_]*:[a-z][a-z0-9-]*$")

        /** Canonical "no drops" sentinel — registries may treat as empty table. */
        public val EMPTY: LootTableId = LootTableId("astera:empty")
    }
}
