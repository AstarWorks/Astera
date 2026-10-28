package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.table.Branch
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import com.astarworks.astera.core.domain.skillengine.table.StateTable
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.TickRange

/**
 * ADR-0303 C3 / 11.4: event-relative sub-tables. Merges the machine's exit sub-tables with the op-contributed ones (projectile `on:`, part emits),
 * clamps windows into the owner's state duration, forces maxFirings >= 1, and checks table closure: every branch's sub-table exists, and
 * no two sub-tables share a key.
 */
public object EventScoper {
    public fun scope(
        skillId: String,
        states: List<StateTable>,
        machineSubs: Map<EventPath, SubTable>,
        opSubs: List<SubTable>,
        config: EngineConfig,
    ): Result<Map<EventPath, SubTable>, SkillDecodeError> {
        val out = LinkedHashMap<EventPath, SubTable>(machineSubs)
        val byState = states.associateBy { it.state }
        for (s in opSubs) {
            if (s.event in out) return Result.Failure(SkillDecodeError.TableClosure(s.event.value, "sub-table key declared twice", skillId))
            out[s.event] = s
        }
        for ((k, s) in out.toMap()) {
            val st = byState[s.owner]
                ?: return Result.Failure(SkillDecodeError.TableClosure(k.value, "owner '${s.owner.value}' is not a state", skillId))
            out[k] = s.copy(window = clamp(s.window, st, config), maxFirings = maxOf(1, s.maxFirings))
        }
        for (st in states) for (b in st.branches) closure(skillId, st.state, b, out)?.let { return Result.Failure(it) }
        for ((_, s) in out) for (b in s.branches) closure(skillId, s.owner, b, out)?.let { return Result.Failure(it) }
        return Result.Success(out)
    }

    private fun closure(skillId: String, owner: StatePath, b: Branch, subs: Map<EventPath, SubTable>): SkillDecodeError? {
        val sub = b.subTable
        if (sub != null && sub !in subs) return SkillDecodeError.TableClosure(sub.value, "branch '${b.event.value}' of '${owner.value}' has no sub-table", skillId)
        return null
    }

    private fun clamp(w: TickRange, st: StateTable, config: EngineConfig): TickRange {
        val hi = when (val d = st.duration) {
            is com.astarworks.astera.core.domain.skillengine.table.Duration.Fixed -> d.ticks
            is com.astarworks.astera.core.domain.skillengine.table.Duration.Bounded -> d.range.max
        }
        val max = minOf(w.max, maxOf(hi, 0), config.maxCastTicks)
        return TickRange(minOf(w.min, max), max)
    }
}
