package com.astarworks.astera.domain.model.weapon

import com.astarworks.astera.domain.model.i18n.MessageKey

/** High-level weapon family. Phase 2 expands to bow, throwable, etc. */
public enum class WeaponArchetype { SWORD, GUN, WAND }

public enum class Rarity { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY }

/**
 * Immutable weapon definition. The YAML in `content/weapons/` is deserialized
 * (in the application layer) into [WeaponSpec] and registered with an
 * `IWeaponRegistry`.
 *
 * `materialKey` is a vendor-neutral material reference (e.g. `IRON_SWORD` for
 * vanilla, `oraxen:my_blade` for Oraxen). The adapter layer resolves it to a
 * concrete item.
 */
public data class WeaponSpec(
    val id: WeaponId,
    val displayNameKey: MessageKey,
    val loreKey: MessageKey,
    val archetype: WeaponArchetype,
    val rarity: Rarity,
    val levelRequirement: Int,
    val materialKey: String,
    val damage: DamageProfile,
    val cooldownTicks: Int,
) {
    init {
        require(levelRequirement >= 1) { "levelRequirement must be >= 1: $levelRequirement" }
        require(cooldownTicks >= 0) { "cooldownTicks must be >= 0: $cooldownTicks" }
        require(materialKey.isNotBlank()) { "materialKey must not be blank" }
    }
}
