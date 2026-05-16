package com.astarworks.astera.application.config

import com.astarworks.astera.domain.model.weapon.DamageAttribute
import com.astarworks.astera.domain.model.weapon.DamageProfile
import com.astarworks.astera.domain.model.weapon.Rarity
import com.astarworks.astera.domain.model.weapon.WeaponArchetype
import com.astarworks.astera.domain.model.weapon.WeaponId
import com.astarworks.astera.domain.model.weapon.WeaponSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/weapons/` definition files.
 *
 * Kept separate from [WeaponSpec] so that the domain stays free of
 * serialization annotations and so the YAML schema can evolve independently
 * of the domain model (Phase 2+ adds `trigger`, `effects`, `status_effects`).
 */
@Serializable
data class WeaponYamlConfig(
    val id: String,
    @SerialName("display_name_key") val displayNameKey: String,
    @SerialName("lore_key") val loreKey: String,
    val archetype: String,
    val rarity: String,
    @SerialName("level_requirement") val levelRequirement: Int,
    val material: String,
    val damage: DamageYamlConfig,
    @SerialName("cooldown_ticks") val cooldownTicks: Int,
) {
    fun toSpec(): WeaponSpec = WeaponSpec(
        id = WeaponId(id),
        displayNameKey = displayNameKey,
        loreKey = loreKey,
        archetype = WeaponArchetype.valueOf(archetype.uppercase()),
        rarity = Rarity.valueOf(rarity.uppercase()),
        levelRequirement = levelRequirement,
        materialKey = material,
        damage = DamageProfile(damage.base, DamageAttribute.valueOf(damage.attribute.uppercase())),
        cooldownTicks = cooldownTicks,
    )
}

@Serializable
data class DamageYamlConfig(
    val base: Double,
    val attribute: String,
)
