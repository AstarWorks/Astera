package com.astarworks.astera.combat.platform.wiring

import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.SkillTag
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.application.skillengine.load.SkillCatalog
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skillengine.model.SkillMeta

/**
 * The cast gate's skill registry (ADR-0301). The [SkillCatalog] is the only skill store; each skill's gate data
 * (skin variants `id@skin` are not skills; cooldown, resource, E.G.O cost, reload, ready cue, name, tags) comes from its `meta:` block and is turned into a
 * [SkillSpec] (the spec type is the gate's record, nothing executes from it).
 */
public class SkillMetaRegistry(private val catalog: SkillCatalog) : ISkillRegistry {

    override fun find(key: SkillId): SkillSpec? {
        val meta = catalog.get(key.value)?.def?.meta ?: return null
        return toSpec(key, meta)
    }

    override fun all(): Collection<SkillSpec> = catalog.ids().filter { '@' !in it }.sorted().mapNotNull { find(SkillId(it)) }

    private fun toSpec(id: SkillId, meta: SkillMeta): SkillSpec {
        fun text(m: Map<String, String>, fallback: LocalizedText) =
            if (m.isEmpty()) fallback else LocalizedText(m.mapKeys { LocaleCode(it.key) })
        return SkillSpec(
            id = id,
            name = text(meta.name, LocalizedText.EMPTY),
            description = text(meta.description, LocalizedText.EMPTY),
            cooldownTicks = meta.cooldownTicks,
            resourceType = when (meta.resourceType) {
                "ammo" -> ResourceType.Ammo
                "energy" -> ResourceType.Energy
                else -> null
            },
            resourceCost = meta.resourceCost,
            reloadTicks = meta.reloadTicks.takeIf { it > 0 },
            readyCue = meta.readyCue,
            egoCost = meta.egoCost.takeIf { it > 0 },
            calleeOnlyCallers = catalog.calleeOnlyCallers(id.value),
            tags = meta.tags.mapNotNull { runCatching { SkillTag(it) }.getOrNull() }.toSet(),
        )
    }
}
