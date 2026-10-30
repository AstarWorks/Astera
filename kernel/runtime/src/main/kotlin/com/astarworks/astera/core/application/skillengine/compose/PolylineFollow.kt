package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.runtime.port.LinePath
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.StateTable
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.Val

/**
 * `jitter.follow: <row id>` (rev4.14): a polyline line reuses another line's vertex stream, so a glow hugs its core. The target must be a line ribbon of the
 * SAME handler / body (the same row list) with the same `segments`; anything else is a load error here, never a silent straight thread at play time.
 */
internal object PolylineFollow {
    fun check(skillId: String, states: List<StateTable>, subs: Collection<SubTable>): SkillDecodeError? {
        for (rows in states.map { it.rows } + subs.map { it.rows }) {
            val lines = rows.filter(::isLine)
            for (r in lines) {
                val follow = followOf(r) ?: continue
                val a = r.action as Action.Spawn
                val target = lines.firstOrNull { it !== r && LinePath.isFollowTarget(r.instance, follow, it) }
                    ?: return SkillDecodeError.Invalid(skillId, r.src.value, "ribbon '${r.instance.text}': jitter.follow '$follow' is not a line ribbon of this handler / body (it must be another ribbon with from/to in the same list of ops)")
                val mine = segmentsOf(a)
                val theirs = segmentsOf(target.action as Action.Spawn)
                if (mine != theirs) {
                    return SkillDecodeError.Invalid(skillId, r.src.value, "ribbon '${r.instance.text}': jitter.follow '$follow' has segments: $theirs but this line has segments: $mine; a followed vertex stream needs the same count")
                }
            }
        }
        return null
    }

    private fun isLine(r: Row): Boolean {
        val a = r.action as? Action.Spawn ?: return false
        return a.kind == SpawnKind.RIBBON && (a.look.fields["shape"] as? Val.Known<*>)?.value == "line"
    }

    private fun followOf(r: Row): String? = ((r.action as Action.Spawn).look.fields["jitter_follow"] as? Val.Known<*>)?.value as? String

    private fun segmentsOf(a: Action.Spawn): Int = ((a.look.fields["segments"] as? Val.Known<*>)?.value as? Number)?.toInt() ?: 1
}
