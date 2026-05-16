package com.astarworks.astera.domain.model.item

/**
 * Stable identifier for an item template registered with `IItemRegistry`.
 *
 * **Astera's primary key for every item reference.** Weapons, GUI slots,
 * loadouts, shop entries, inventory contents — every Kotlin call site that
 * touches an item resolves through an `ItemId`. The same id flows from
 * `content/items/` YAML files to the YAML DTO ([com.astarworks.astera.application.config.ItemYamlConfig])
 * to the registry to whatever consumes it.
 *
 * Naming rule: lowercase kebab-case (`example-sword`, `lightning-blade`).
 * Validated at construction so YAML typos surface immediately — same regex
 * shape as [com.astarworks.astera.domain.model.weapon.WeaponId] (Stage 1 D
 * deliberately mirrors the weapon-id pattern so the two stay consistent).
 *
 * The id stays opaque to the domain — what `External(key)` template means
 * (Oraxen, ItemAdder, MythicItems, …) is decided in adapter modules.
 */
@JvmInline
public value class ItemId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "ItemId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
