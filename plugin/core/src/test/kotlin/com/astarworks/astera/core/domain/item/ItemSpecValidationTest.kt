package com.astarworks.astera.core.domain.item

import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.SkillTrigger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

/**
 * Validates the per-record `init` invariants for every new item domain type
 * introduced in Wave _items-1 (ADR-0073).
 */
class ItemSpecValidationTest {

    @Test
    fun `DropPolicy rejects dropOnDeath and keepOnDeath both true`() {
        assertThatIllegalArgumentException()
            .isThrownBy { DropPolicy(dropOnDeath = true, keepOnDeath = true) }
    }

    @Test
    fun `DropPolicy accepts default (drop on death, no keep)`() {
        val policy = DropPolicy()
        assertThat(policy.dropOnDeath).isTrue
        assertThat(policy.keepOnDeath).isFalse
    }

    @Test
    fun `DropPolicy accepts keep without drop`() {
        val policy = DropPolicy(dropOnDeath = false, keepOnDeath = true)
        assertThat(policy.dropOnDeath).isFalse
        assertThat(policy.keepOnDeath).isTrue
    }

    @Test
    fun `ItemAttributes rejects knockbackResistance above 1`() {
        assertThatIllegalArgumentException()
            .isThrownBy { ItemAttributes(knockbackResistance = 1.5) }
    }

    @Test
    fun `ItemAttributes rejects knockbackResistance below 0`() {
        assertThatIllegalArgumentException()
            .isThrownBy { ItemAttributes(knockbackResistance = -0.1) }
    }

    @Test
    fun `ItemAttributes accepts boundary values for knockbackResistance`() {
        ItemAttributes(knockbackResistance = 0.0)
        ItemAttributes(knockbackResistance = 1.0)
    }

    @Test
    fun `ItemAttributes rejects negative movementSpeedMultiplier`() {
        assertThatIllegalArgumentException()
            .isThrownBy { ItemAttributes(movementSpeedMultiplier = -0.1) }
    }

    @Test
    fun `ItemSkillBinding rejects negative cooldown override`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                ItemSkillBinding(
                    trigger = SkillTrigger.LEFT_CLICK,
                    skillId = SkillId("fireball"),
                    cooldownOverrideTicks = -1,
                )
            }
    }

    @Test
    fun `ItemSkillBinding accepts zero cooldown override`() {
        val b = ItemSkillBinding(
            trigger = SkillTrigger.LEFT_CLICK,
            skillId = SkillId("fireball"),
            cooldownOverrideTicks = 0,
        )
        assertThat(b.cooldownOverrideTicks).isZero
    }

    @Test
    fun `ItemSkillBinding accepts null cooldown override (defer to skill default)`() {
        val b = ItemSkillBinding(
            trigger = SkillTrigger.RIGHT_CLICK,
            skillId = SkillId("fireball"),
        )
        assertThat(b.cooldownOverrideTicks).isNull()
    }

    @Test
    fun `ItemSpec rejects levelRequirement below 1`() {
        assertThatIllegalArgumentException().isThrownBy { sampleSpec(levelRequirement = 0) }
    }

    @Test
    fun `ItemSpec accepts levelRequirement of 1`() {
        val spec = sampleSpec(levelRequirement = 1)
        assertThat(spec.levelRequirement).isEqualTo(1)
    }

    private fun sampleSpec(levelRequirement: Int): ItemSpec = ItemSpec(
        id = ItemId("sample"),
        template = ItemTemplate.Vanilla(material = "STONE"),
        displayName = com.astarworks.astera.core.domain.i18n.LocalizedText.EMPTY,
        lore = com.astarworks.astera.core.domain.i18n.LocalizedLore.EMPTY,
        rarity = Rarity.COMMON,
        levelRequirement = levelRequirement,
        attributes = ItemAttributes.EMPTY,
        equipSlot = EquipSlot.MAINHAND,
        durability = DurabilityPolicy.DEFAULT,
        dropPolicy = DropPolicy.DEFAULT,
        enchantable = true,
        glint = null,
        skillBindings = emptyList(),
        pdcTags = emptyMap(),
    )
}
