package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IArmorRegistry
import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.domain.armor.ArmorId
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.item.ISkillTooltipSource
import com.astarworks.astera.core.application.port.outbound.item.SkillTooltipBrief
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.status.StatusEffectId
import java.util.Locale

/**
 * Builds the tooltip effect lines of a skill straight from its content (no per-item text in code):
 * line 1 is the skill's short `description` (omitted when it only repeats the name), line 2 is the
 * numbers that can be read off the spec - strongest single hit, reach, cooldown, costs, applied
 * statuses. Status one-liners come from `astera.statusdesc.<id>` in the language files.
 */
public class SkillTooltipSummary(
    private val skills: ISkillRegistry,
    private val statuses: IStatusEffectRegistry,
    private val messages: IMessageRenderer,
    private val armors: IArmorRegistry? = null,
) : ISkillTooltipSource {

    override fun armorReduction(pieceId: String): Double? = armors?.find(ArmorId(pieceId))?.reduction?.default

    override fun brief(skillId: String, viewer: PlayerId?, cooldownOverrideTicks: Int?, compact: Boolean): SkillTooltipBrief? {
        val spec = skills.find(SkillId(skillId)) ?: return null
        val name = messages.renderText(viewer, spec.name)
        val involved = involvedSpecs(spec)
        val mechanics = involved.flatMap { allMechanics(it) }
        val lines = buildList {
            if (!compact) describe(spec, name, viewer)?.let { add("<dark_gray>  </dark_gray><gray>$it</gray>") }
            stats(spec, involved, mechanics, viewer, cooldownOverrideTicks ?: spec.cooldownTicks)?.let { add("<dark_gray>  </dark_gray>$it") }
        }
        val applied = appliedStatuses(mechanics).filter { it.value != IMMUNITY }
        val statusLines = applied.mapNotNull { id ->
            val label = statuses.find(id)?.displayNameKey?.let { messages.render(viewer, it) } ?: return@mapNotNull null
            val desc = messages.render(viewer, MessageKey("$STATUS_DESC.${id.value}"))
            "<gray>$label: <white>$desc</white></gray>"
        }
        return SkillTooltipBrief(name, lines, statusLines, usesEgo = (spec.egoCost ?: 0) > 0)
    }

    private fun describe(spec: SkillSpec, name: String, viewer: PlayerId?): String? {
        val text = messages.renderText(viewer, spec.description).trim()
        if (text.isEmpty() || text == name) return null
        return firstSentence(text)
    }

    private fun stats(spec: SkillSpec, involved: List<SkillSpec>, mechanics: List<Mechanic>, viewer: PlayerId?, cooldownTicks: Int): String? {
        val tokens = mutableListOf<String>()
        fun token(key: String, vararg kv: Pair<String, String>) {
            tokens += messages.render(viewer, MessageKey("$BRIEF.$key"), kv.toMap())
        }
        val hit = strongestHit(involved, mechanics)
        if (hit > 0.0) token("damage", "value" to number(hit))
        reach(involved, mechanics)?.let { (key, value) -> token(key, "value" to number(value)) }
        if (cooldownTicks > 0) token("cooldown", "value" to number(cooldownTicks / TPS))
        spec.resourceCost?.takeIf { it > 0 }?.let { token("cost", "value" to it.toString()) }
        spec.egoCost?.takeIf { it > 0 }?.let { token("ego_cost", "value" to it.toString()) }
        val applied = appliedStatuses(mechanics).filter { it.value != IMMUNITY }
        if (applied.isNotEmpty()) {
            val labels = applied.mapNotNull { id -> statuses.find(id)?.displayNameKey?.let { messages.render(viewer, it) } }
            if (labels.isNotEmpty()) token("applies", "value" to labels.joinToString(", "))
        }
        return tokens.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    }

    /** The skill plus every skill it chains into with `cast_skill` (bounded depth). */
    private fun involvedSpecs(root: SkillSpec): List<SkillSpec> {
        val seen = linkedMapOf(root.id to root)
        var frontier = listOf(root)
        repeat(MAX_CHAIN_DEPTH) {
            frontier = frontier.flatMap { allMechanics(it).filterIsInstance<Mechanic.CastSkill>() }
                .mapNotNull { skills.find(it.skillId) }
                .filter { seen.putIfAbsent(it.id, it) == null }
        }
        return seen.values.toList()
    }

    private fun strongestHit(involved: List<SkillSpec>, mechanics: List<Mechanic>): Double {
        val fromMechanics = mechanics.filterIsInstance<Mechanic.Damage>().maxOfOrNull { d -> d.components.sumOf { it.constantValue } } ?: 0.0
        val fromParams = involved.maxOf { spec ->
            when (val p = spec.params) {
                is SkillParams.MeleeParams -> p.damage.base
                is SkillParams.AoeParams -> p.damage.base
                else -> 0.0
            }
        }
        return maxOf(fromMechanics, fromParams)
    }

    private fun reach(involved: List<SkillSpec>, mechanics: List<Mechanic>): Pair<String, Double>? {
        val dash = mechanics.filterIsInstance<Mechanic.DashStrike>().maxOfOrNull { (it.distance as? NumericValue.Literal)?.value ?: 0.0 } ?: 0.0
        if (dash > 0.0) return "dash" to dash
        return involved.firstNotNullOfOrNull { spec ->
            when (val p = spec.params) {
                is SkillParams.MeleeParams -> "range" to p.range
                is SkillParams.AoeParams -> "radius" to p.radius
                is SkillParams.TeleportParams -> "teleport" to p.maxDistance
                is SkillParams.ProjectileParams -> if (p.launchSpeed > 0.0) "projectile" to 0.0 else null
                else -> null
            }?.takeIf { it.first == "projectile" || it.second > 0.0 }
        }
    }

    private fun appliedStatuses(mechanics: List<Mechanic>): List<StatusEffectId> = mechanics.mapNotNull {
        when (it) {
            is Mechanic.ApplyStatus -> it.statusId
            is Mechanic.Ignite -> StatusEffectId("burn")
            is Mechanic.Freeze -> StatusEffectId("freeze")
            else -> null
        }
    }.distinct()

    private fun allMechanics(spec: SkillSpec): List<Mechanic> {
        val roots: List<Mechanic> = when (val ph = spec.phases) {
            is PhaseMechanics.Projectile -> ph.cast + ph.hitEntity + ph.hitBlock + ph.miss + ph.expire
            is PhaseMechanics.Melee -> ph.cast + ph.hitEntity + ph.miss
            is PhaseMechanics.Aoe -> ph.cast + ph.hitEntity + ph.expire
            is PhaseMechanics.Buff -> ph.cast + ph.expire + ph.removed
            is PhaseMechanics.Teleport -> ph.cast + ph.preTeleport + ph.postTeleport
            is PhaseMechanics.Sequence -> ph.cast
            is PhaseMechanics.Summon -> ph.cast + ph.spawn + ph.summonDeath
        }
        return roots.flatMap { flatten(it) }
    }

    private fun flatten(m: Mechanic): List<Mechanic> = listOf(m) + when (m) {
        is Mechanic.DashStrike -> m.onHit.flatMap { flatten(it) }
        is Mechanic.Counter -> m.onHit.flatMap { flatten(it) }
        is Mechanic.ForEachTarget -> (m.mechanics + m.ifEmpty).flatMap { flatten(it) }
        is Mechanic.IfThen -> (m.then_ + m.else_).flatMap { flatten(it) }
        is Mechanic.Loop -> m.mechanics.flatMap { flatten(it) }
        else -> emptyList()
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
        const val STATUS_DESC = "astera.statusdesc"
        const val SEPARATOR = "<dark_gray> | </dark_gray>"
        const val TPS = 20.0
        const val MAX_DESC = 28
        const val MAX_CHAIN_DEPTH = 3
        const val IMMUNITY = "immunity"
    }
}
