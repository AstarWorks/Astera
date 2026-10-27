package com.astarworks.astera.combat.platform.wiring

import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.SkillTag
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skillengine.model.SkillMeta

/**
 * The cast gate's skill registry (ADR-0301, SKL-08). A skill that the v2 catalog knows AND that carries a `meta:` block
 * gets its gate data (cooldown, resource, E.G.O cost, reload, ready cue, name, tags) from that block, replacing the v1
 * flat [SkillSpec] fields (a v2-only skill with no v1 yaml gets a placeholder spec). A skill without `meta` keeps its
 * v1 spec unchanged. [metaOf] is bound by `SkillEnginePaperRuntime` once the v2 catalog exists.
 */
public class SkillMetaRegistry(private val v1: ISkillRegistry) : ISkillRegistry {

    @Volatile
    public var metaOf: (String) -> SkillMeta? = { null }

    override fun find(key: SkillId): SkillSpec? {
        val base = v1.find(key)
        val meta = metaOf(key.value)?.takeIf { it != SkillMeta() } ?: return base
        return overlay(key, base, meta)
    }

    override fun all(): Collection<SkillSpec> {
        return v1.all().mapNotNull { find(it.id) }
    }

    private fun overlay(id: SkillId, base: SkillSpec?, meta: SkillMeta): SkillSpec {
        fun text(m: Map<String, String>, fallback: LocalizedText) =
            if (m.isEmpty()) fallback else LocalizedText(m.mapKeys { LocaleCode(it.key) })
        val seed = base ?: SkillSpec(
            id = id,
            name = LocalizedText.EMPTY,
            description = LocalizedText.EMPTY,
            archetype = SkillArchetype.SEQUENCE,
            cooldownTicks = 0,
            params = SkillParams.SequenceParams,
        )
        return seed.copy(
            name = text(meta.name, seed.name),
            description = text(meta.description, seed.description),
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
            tags = if (meta.tags.isEmpty()) seed.tags else meta.tags.mapNotNull { runCatching { SkillTag(it) }.getOrNull() }.toSet(),
        )
    }
}
