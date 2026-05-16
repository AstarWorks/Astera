package com.astarworks.astera.testfixtures

import com.astarworks.astera.domain.model.weapon.DamageAttribute
import com.astarworks.astera.domain.model.weapon.DamageProfile
import com.astarworks.astera.domain.model.weapon.Rarity
import com.astarworks.astera.domain.model.weapon.WeaponArchetype
import com.astarworks.astera.domain.model.weapon.WeaponId
import com.astarworks.astera.domain.model.weapon.WeaponSpec

/**
 * Defaults mirror `content/weapons/example-sword.yaml` so tests can construct
 * a realistic [WeaponSpec] with one call and override only what matters.
 */
fun weaponSpec(
    id: String = "example-sword",
    displayNameKey: String = "astera.weapon.$id.name",
    loreKey: String = "astera.weapon.$id.lore",
    archetype: WeaponArchetype = WeaponArchetype.SWORD,
    rarity: Rarity = Rarity.COMMON,
    levelRequirement: Int = 1,
    materialKey: String = "IRON_SWORD",
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
    materialKey = materialKey,
    damage = DamageProfile(damageBase, damageAttribute),
    cooldownTicks = cooldownTicks,
)
