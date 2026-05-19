package com.astarworks.astera.items.testfixtures

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.DurabilityPolicy
import com.astarworks.astera.core.domain.item.DropPolicy
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSkillBinding
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.SkillTrigger

/**
 * Realistic [ItemSpec] builder for Items-plugin unit tests (Wave _items-1,
 * ADR-0073). Defaults mirror the layout `content/items/example-sword.yaml`
 * lands with so a single call produces a fully-valid spec; tests override
 * only the fields they exercise.
 */
public fun itemSpec(
    id: String = "example-sword",
    template: ItemTemplate = ItemTemplate.Vanilla(material = "IRON_SWORD"),
    rarity: Rarity = Rarity.COMMON,
    levelRequirement: Int = 1,
    equipSlot: EquipSlot = EquipSlot.MAINHAND,
    attributes: ItemAttributes = ItemAttributes.EMPTY,
    durability: DurabilityPolicy = DurabilityPolicy.DEFAULT,
    dropPolicy: DropPolicy = DropPolicy.DEFAULT,
    enchantable: Boolean = true,
    glint: Boolean? = false,
    skillBindings: List<ItemSkillBinding> = emptyList(),
    displayName: LocalizedText = LocalizedText(mapOf(LocaleCode.EN to id.replace('-', ' '))),
    lore: LocalizedLore = LocalizedLore.EMPTY,
    pdcTags: Map<String, String> = emptyMap(),
): ItemSpec = ItemSpec(
    id = ItemId(id),
    template = template,
    displayName = displayName,
    lore = lore,
    rarity = rarity,
    levelRequirement = levelRequirement,
    attributes = attributes,
    equipSlot = equipSlot,
    durability = durability,
    dropPolicy = dropPolicy,
    enchantable = enchantable,
    glint = glint,
    skillBindings = skillBindings,
    pdcTags = pdcTags,
)

/** Convenience: spec with a single skill binding for trigger tests. */
public fun itemSpecWithBinding(
    id: String = "example-sword",
    trigger: SkillTrigger = SkillTrigger.LEFT_CLICK,
    skillId: SkillId = SkillId("fireball"),
    cooldownOverrideTicks: Int? = null,
): ItemSpec = itemSpec(
    id = id,
    skillBindings = listOf(
        ItemSkillBinding(
            trigger = trigger,
            skillId = skillId,
            cooldownOverrideTicks = cooldownOverrideTicks,
        ),
    ),
)
