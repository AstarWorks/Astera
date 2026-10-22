package com.astarworks.astera.items.application.admin

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.ISkillTesterDelivery
import com.astarworks.astera.core.application.port.outbound.SkillTesterError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemSkillBinding
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.SkillTesterItems
import com.astarworks.astera.core.domain.skill.SkillTrigger
import com.astarworks.astera.items.application.port.outbound.DeliveryResult
import com.astarworks.astera.items.application.port.outbound.IItemDelivery

/**
 * Hands an admin a "skill tester" (F2-5, ADR-0196): the content item
 * `skill-tester` re-templated with the id `skill-tester-<skill>` and a right-click
 * binding to that skill. The spec is built on the fly and never enters the item
 * registry; [HandleItemTriggerUseCase] recognises the id when it is clicked.
 */
public class SkillTesterDelivery(
    private val items: IItemRegistry,
    private val delivery: IItemDelivery,
) : ISkillTesterDelivery {

    override fun give(target: IMcPlayer, skill: SkillId): Result<Unit, SkillTesterError> {
        val template = items.find(SkillTesterItems.TEMPLATE) ?: return Result.failure(SkillTesterError.NoTemplate)
        val spec = template.copy(
            id = SkillTesterItems.idFor(skill),
            displayName = LocalizedText(
                mapOf(
                    LocaleCode.EN to "<light_purple>Skill Tester: ${skill.value}</light_purple>",
                    LocaleCode.JA to "<light_purple>スキルテスター: ${skill.value}</light_purple>",
                ),
            ),
            lore = LocalizedLore(
                mapOf(
                    LocaleCode.EN to listOf("<gray>Right-click: cast ${skill.value}</gray>", "<dark_gray>admin tool</dark_gray>"),
                    LocaleCode.JA to listOf("<gray>右クリック: ${skill.value} を発動</gray>", "<dark_gray>管理者ツール</dark_gray>"),
                ),
            ),
            skillBindings = listOf(ItemSkillBinding(SkillTrigger.RIGHT_CLICK, skill)),
        )
        return when (val out = delivery.give(target, spec, 1)) {
            DeliveryResult.Delivered -> Result.success(Unit)
            is DeliveryResult.InventoryFull -> Result.failure(SkillTesterError.InventoryFull)
            is DeliveryResult.UnknownTemplate -> Result.failure(SkillTesterError.Delivery("unknown template ${out.templateKey}"))
        }
    }
}
