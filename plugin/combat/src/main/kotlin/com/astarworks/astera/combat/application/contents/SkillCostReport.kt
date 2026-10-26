package com.astarworks.astera.combat.application.contents

import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.domain.skill.SkillCost
import com.astarworks.astera.core.application.port.outbound.IVfxRegistry
import com.astarworks.astera.core.domain.skill.SkillId

/** Shared by runtime load logging and content-lint. Unknown/cyclic dependencies saturate. */
public object SkillCostReport {
    public fun particleCost(registry: com.astarworks.astera.core.application.port.outbound.IParticleRegistry?, id: com.astarworks.astera.core.domain.effect.ParticleId, count: Long): Long {
        val def = registry?.find(id) ?: return Long.MAX_VALUE
        val points = maxOf(def.bakedShape.pointCount?.toLong() ?: 1, if (count > 1) count else def.count.toLong())
        return SkillCost.multiply(32, points)
    }

    public fun vfxCost(registry: IVfxRegistry?, id: com.astarworks.astera.core.domain.effect.vfx.VfxId): Long {
        val def = registry?.find(id) ?: return Long.MAX_VALUE
        // Rigs can generate 256 slots apiece; path interpolation has at most 24 points per tick.
        val slots = def.elements.fold(0L) { sum, el -> SkillCost.add(sum, el.slotCount.toLong()) }
        val rigSlots = SkillCost.multiply(def.rigs.size.toLong(), 256)
        return SkillCost.multiply(SkillCost.add(slots, rigSlots), def.durationTicks.toLong() * 32)
    }

    public fun top(skills: ISkillRegistry, vfx: IVfxRegistry? = null,
        particles: com.astarworks.astera.core.application.port.outbound.IParticleRegistry? = null,
    ): List<Pair<SkillId, Long>> {
        val memo = mutableMapOf<SkillId, Long>()
        fun estimate(id: SkillId, stack: Set<SkillId>): Long {
            memo[id]?.let { return it }
            if (id in stack) return Long.MAX_VALUE
            val spec = skills.find(id) ?: return Long.MAX_VALUE
            return SkillCost.estimate(spec.phases, spec.params,
                skill = { estimate(it, stack + id) },
                vfx = { vfxCost(vfx, it) },
                particle = { id, count -> particleCost(particles, id, count) },
            ).also { memo[id] = it }
        }
        return skills.all().map { it.id to estimate(it.id, emptySet()) }
            .sortedWith(compareByDescending<Pair<SkillId, Long>> { it.second }.thenBy { it.first.value }).take(10)
    }

    public fun format(entries: List<Pair<SkillId, Long>>): String = entries.joinToString("\n") { (id, ops) ->
        "$id: ${if (ops == Long.MAX_VALUE) "unbounded" else ops.toString()} ops"
    }
}
