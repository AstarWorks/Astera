package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.Val

/**
 * Registry-backed id check the decoder cannot do (it has no status registry): every status id a compiled skill applies or consumes must
 * be one the host knows. The host runs it at enable and in the shipped-content test, so an unknown id is a named boot / test error
 * (skill + YAML path), never a per-call warn that silently skips the row.
 */
public object SkillIdAudit {
    /** One line per row naming an unknown status: `skill <id> <src>: status '<x>' is not a registered status`. */
    public fun unknownStatuses(skills: Collection<CompiledSkill>, known: (String) -> Boolean): List<String> {
        val out = ArrayList<String>()
        for (skill in skills) {
            val rows: List<Row> = skill.table.states.flatMap { it.rows } + skill.table.subTables.values.flatMap { it.rows }
            for (row in rows) {
                val id = when (val a = row.action) {
                    is Action.Status -> a.status
                    is Action.Effect -> if (a.op.value == "consume_status") (a.payload["status"] as? Val.Known<*>)?.value as? String else null
                    else -> null
                } ?: continue
                if (!known(id)) out += "skill ${skill.def.id} ${row.src.value}: status '$id' is not a registered status"
            }
        }
        return out
    }
}
