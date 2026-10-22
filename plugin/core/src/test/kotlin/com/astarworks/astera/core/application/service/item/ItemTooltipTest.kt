package com.astarworks.astera.core.application.service.item

import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.DropPolicy
import com.astarworks.astera.core.domain.item.DurabilityPolicy
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSkillBinding
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.SkillTrigger
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ItemTooltipTest {
    private val tooltip = ItemTooltip(FakeMessageRenderer())

    private fun spec(
        attributes: ItemAttributes = ItemAttributes.EMPTY,
        rarity: Rarity = Rarity.RARE,
        level: Int = 3,
        resourceType: ResourceType? = null,
        cost: Int = 0,
        maxPool: Int = -1,
        bound: Boolean = true,
    ) = ItemSpec(
        id = ItemId("test-item"),
        template = ItemTemplate.Vanilla(material = "IRON_SWORD"),
        displayName = LocalizedText.EMPTY,
        lore = LocalizedLore.EMPTY,
        rarity = rarity,
        levelRequirement = level,
        attributes = attributes,
        equipSlot = EquipSlot.MAINHAND,
        durability = DurabilityPolicy.DEFAULT,
        dropPolicy = DropPolicy.DEFAULT,
        enchantable = true,
        glint = false,
        skillBindings = if (bound) listOf(ItemSkillBinding(SkillTrigger.LEFT_CLICK, SkillId("sword-slash"))) else emptyList(),
        pdcTags = emptyMap(),
        resourceType = resourceType,
        resourceCostPerUse = cost,
        resourceMaxPool = maxPool,
    )

    @Test
    fun `header derives rarity and level from the spec`() {
        val lore = tooltip.lore(spec(rarity = Rarity.EPIC, level = 7), null)
        assertThat(lore.first())
            .contains("astera.item.tooltip.header")
            .contains("level=7")
            .contains("astera.item.tooltip.rarity.epic")
    }

    @Test
    fun `stat lines follow the attributes with whole numbers trimmed`() {
        val lines = tooltip.statLines(
            spec(attributes = ItemAttributes(attackDamage = 7.0, attackSpeed = 1.6, knockbackResistance = 0.25)),
            null,
        )
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).contains("stat.attack_damage").contains("value=7}")
        assertThat(lines[1]).contains("stat.attack_speed").contains("value=1.6")
        assertThat(lines[2]).contains("stat.knockback_resistance").contains("value=25")
    }

    @Test
    fun `ammo weapons show magazine and per-shot cost, energy weapons only the cost`() {
        val gun = tooltip.statLines(spec(resourceType = ResourceType.Ammo, cost = 1, maxPool = 12), null)
        assertThat(gun).hasSize(2)
        assertThat(gun[0]).contains("stat.magazine").contains("value=12")
        assertThat(gun[1]).contains("stat.cost_ammo")
        val sword = tooltip.statLines(spec(resourceType = ResourceType.Energy, cost = 15), null)
        assertThat(sword).hasSize(1)
        assertThat(sword[0]).contains("stat.cost_energy").contains("value=15")
    }

    @Test
    fun `plain items without gameplay data get no header or stats`() {
        val pane = spec(bound = false)
        assertThat(tooltip.isGear(pane)).isFalse()
        assertThat(tooltip.lore(pane, null)).isEmpty()
    }
}
