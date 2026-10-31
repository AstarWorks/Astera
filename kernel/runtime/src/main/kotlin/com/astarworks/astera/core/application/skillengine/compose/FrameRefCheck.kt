package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameRefs
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.ExpansionTable
import com.astarworks.astera.core.domain.skillengine.table.HitShape
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.TargetRule
import com.astarworks.astera.core.domain.skillengine.table.Val

/**
 * ADR-0314: every `frame:` reference a row carries (a draw op's position cell, a rig's `frame` payload, a display's host) names a frame
 * declared under `frames:`; an undeclared one is a load error at the row, never a runtime fault.
 */
internal object FrameRefCheck {
    fun run(def: SkillDef, table: ExpansionTable): SkillDecodeError? {
        val declared = def.frames.map { it.id }.toSet()
        val rows = table.states.flatMap { it.rows } + table.subTables.values.flatMap { it.rows }
        for (row in rows) {
            val bad = refsOf(row).firstOrNull { it !in declared } ?: continue
            return SkillDecodeError.UndefinedReference(def.id, row.src.value, "${FrameRefs.PREFIX}$bad (not under frames:)")
        }
        return null
    }

    fun refsOf(row: Row): List<String> {
        val names = ArrayList<String>()
        fun cell(v: Val<*>?) { if (v is Val.Live) names += v.name }
        fun rule(t: TargetRule) { if (t is TargetRule.Area) names += t.aroundName }
        when (val a = row.action) {
            is Action.Spawn -> {
                names += a.anchorName
                cell(a.pose.position)
                // a ribbon line's two ends may be frame points ({frame, pos}, ADR-0314): their cells sit in the look
                if (a.kind == SpawnKind.RIBBON) for (end in listOf("from", "to")) ((a.look.fields[end] as? Val.Known)?.value as? String)?.let { names += it }
            }
            is Action.World -> { cell(a.origin); cell(a.forward) }
            is Action.Summon -> { cell(a.origin); cell(a.forward) }
            is Action.Hit -> {
                when (val s = a.shape) {
                    is HitShape.Sphere -> cell(s.center)
                    is HitShape.Box -> cell(s.center)
                    is HitShape.Capsule -> { cell(s.from); cell(s.to) }
                    is HitShape.Sector -> { cell(s.origin); cell(s.forward) }
                }
            }
            is Action.Damage -> rule(a.targets)
            is Action.Status -> rule(a.targets)
            is Action.Particle -> cell(a.position)
            is Action.ParticleDef -> cell(a.position)
            is Action.Sound -> cell(a.position)
            // only the rig carries a frames: id here; a reel's "frame" is the projectile instance it pulls on
            is Action.Effect -> {
                ((a.payload["frame"] as? Val.Known)?.value as? String)?.let { names += FrameRefs.PREFIX + it }
                // knockback / pull / impulse direction cells and an area target of any effect
                for (v in a.payload.values) {
                    if (v is Val.Live) names += v.name
                    ((v as? Val.Known)?.value as? TargetRule)?.let { rule(it) }
                }
            }
            else -> Unit
        }
        return names.mapNotNull { FrameRefs.parse(it) }.flatMap { listOf(it.frame, it.toFrame) }
    }
}
