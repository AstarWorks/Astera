package com.astarworks.astera.domain.model.weapon

import com.astarworks.astera.domain.model.i18n.MessageKey
import com.astarworks.astera.domain.model.item.ItemId

/** High-level weapon family. Phase 2 expands to bow, throwable, etc. */
public enum class WeaponArchetype { SWORD, GUN, WAND }

public enum class Rarity { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY }

/**
 * Immutable weapon definition. The YAML in `content/weapons/` is deserialized
 * (in the application layer) into [WeaponSpec] and registered with an
 * `IWeaponRegistry`.
 *
 * Per Stage 1 D (Phase 2 mid), weapons reference items by [ItemId] instead of
 * carrying a vendor material string. The item template (vanilla / external)
 * lives in `IItemRegistry` and is resolved by the adapter layer at give-time.
 */
public data class WeaponSpec(
    val id: WeaponId,
    val displayNameKey: MessageKey,
    val loreKey: MessageKey,
    val archetype: WeaponArchetype,
    val rarity: Rarity,
    val levelRequirement: Int,
    val itemId: ItemId,
    val damage: DamageProfile,
    val cooldownTicks: Int,
) {
    init {
        require(levelRequirement >= 1) { "levelRequirement must be >= 1: $levelRequirement" }
        require(cooldownTicks >= 0) { "cooldownTicks must be >= 0: $cooldownTicks" }
    }
}
