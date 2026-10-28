package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IArmorRegistry
import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.domain.armor.ArmorId
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.item.ISkillTooltipSource
import com.astarworks.astera.core.application.port.outbound.item.SkillTooltipBrief
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import java.util.Locale

/**
 * Item-tooltip effect lines of a skill, read from the skill's v2 `meta:` block (ADR-0301) through the cast gate's
 * [ISkillRegistry]: the localized name, the short description and the cooldown / cost numbers.
 */
public class SkillTooltipSummary(
    private val skills: ISkillRegistry,
    private val messages: IMessageRenderer,
    private val armors: IArmorRegistry? = null,
) : ISkillTooltipSource {

    override fun armorReduction(pieceId: String): Double? = armors?.find(ArmorId(pieceId))?.reduction?.default

    override fun brief(skillId: String, viewer: PlayerId?, cooldownOverrideTicks: Int?, compact: Boolean): SkillTooltipBrief? {
        val spec = skills.find(SkillId(skillId)) ?: return null
        val name = messages.renderText(viewer, spec.name)
        val description = messages.renderText(viewer, spec.description).trim()
        val cooldownTicks = cooldownOverrideTicks ?: spec.cooldownTicks
        val tokens = buildList {
            if (cooldownTicks > 0) add(messages.render(viewer, MessageKey("$BRIEF.cooldown"), mapOf("value" to number(cooldownTicks / TPS))))
            spec.resourceCost?.takeIf { it > 0 }?.let { add(messages.render(viewer, MessageKey("$BRIEF.cost"), mapOf("value" to it.toString()))) }
            spec.egoCost?.takeIf { it > 0 }?.let { add(messages.render(viewer, MessageKey("$BRIEF.ego_cost"), mapOf("value" to it.toString()))) }
        }
        val lines = buildList {
            if (!compact && description.isNotEmpty() && description != name) add("<dark_gray>  </dark_gray><gray>${firstSentence(description)}</gray>")
            if (tokens.isNotEmpty()) add("<dark_gray>  </dark_gray>" + tokens.joinToString(SEPARATOR))
        }
        return SkillTooltipBrief(name, lines, usesEgo = (spec.egoCost ?: 0) > 0)
    }

    private fun firstSentence(text: String): String {
        val cut = text.indexOfFirst { it == '。' || it == '.' || it == '\n' }
        val s = if (cut in 1 until text.length - 1) text.substring(0, cut + 1) else text
        return s.trimEnd('.', '。').let { if (it.length > MAX_DESC) it.take(MAX_DESC) + "…" else it }
    }

    private fun number(v: Double): String =
        if (v == Math.rint(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.1f", v)

    private companion object {
        const val BRIEF = "astera.skillbrief"
        const val SEPARATOR = "<dark_gray> | </dark_gray>"
        const val TPS = 20.0
        const val MAX_DESC = 28
    }
}
