package com.astarworks.astera.weapons.testfixtures

import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.weapons.domain.weapon.DamageProfile
import com.astarworks.astera.weapons.domain.weapon.Rarity
import com.astarworks.astera.weapons.domain.weapon.WeaponArchetype
import com.astarworks.astera.weapons.domain.weapon.WeaponId
import com.astarworks.astera.weapons.domain.weapon.WeaponSpec

/**
 * Realistic [WeaponSpec] builder for tests. Defaults mirror
 * `content/weapons/example-sword.yaml` so a single call yields a fully-valid
 * spec; tests override only the fields they exercise.
 *
 * ADR-0022 Phase 3: lifted out of `:plugin:test-fixtures.Specs.kt` (which
 * was Core-only) into the Weapons plugin's own test source set.
 */
public fun weaponSpec(
    id: String = "example-sword",
    displayNameKey: MessageKey = MessageKey("astera.weapon.$id.name"),
    loreKey: MessageKey = MessageKey("astera.weapon.$id.lore"),
    archetype: WeaponArchetype = WeaponArchetype.SWORD,
    rarity: Rarity = Rarity.COMMON,
    levelRequirement: Int = 1,
    itemId: ItemId = ItemId(id),
    damageBase: Double = 8.0,
    damageAttribute: DamageAttribute = DamageAttribute.PHYSICAL,
    cooldownTicks: Int = 20,
): WeaponSpec = WeaponSpec(
    id = WeaponId(id),
    displayNameKey = displayNameKey,
    loreKey = loreKey,
    archetype = archetype,
    rarity = rarity,
    levelRequirement = levelRequirement,
    itemId = itemId,
    damage = DamageProfile(damageBase, damageAttribute),
    cooldownTicks = cooldownTicks,
)
