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
        // an instance-rooted frame (`origin: bolt`) names an op of this skill that spawns an instance; an id no op spawns would wait forever, so it is a load error
        val spawned = rows.filter { val a = it.action; a is Action.Summon || a is Action.Spawn || (a is Action.Effect && a.spawnsAnchor) }
            .map { it.instance.text.substringAfterLast('/') }.toSet()
        // a frame-point origin (`origin: {frame, pos}`) names a declared plain frame of this skill, and the chain of parents must not loop
        val byId = def.frames.associateBy { it.id }
        // an on: state.<name> frame names exactly one state of this skill (its whole path or its last segment); it is born on that state's entry
        for (f in def.frames) {
            val name = f.stateName ?: continue
            val hits = table.states.count { f.startsOnState(it.state.value) }
            if (hits != 1) return SkillDecodeError.UndefinedReference(def.id, "frames.${f.id}.on", "state '$name' (frame '${f.id}': on: state.$name names ${if (hits == 0) "no state" else "$hits states, write the full path"} of this skill)")
        }
        for (f in def.frames) {
            val parent = (f.origin as? com.astarworks.astera.core.domain.skillengine.frame.grammar.OriginSpec.Frame)?.frame ?: continue
            val target = byId[parent] ?: return SkillDecodeError.UndefinedReference(def.id, "frames.${f.id}.origin", "frame '$parent' (frame '${f.id}': not under frames:)")
            if (target.perEvent || target.perState) return SkillDecodeError.UndefinedReference(def.id, "frames.${f.id}.origin", "frame '$parent' (frame '${f.id}': an on: event / on: state frame cannot be a parent, it exists per event / state entry)")
            val seen = linkedSetOf(f.id)
            var cur: String? = parent
            while (cur != null) {
                if (!seen.add(cur)) return SkillDecodeError.UndefinedReference(def.id, "frames.${f.id}.origin", "cycle ${seen.joinToString(" -> ")} -> $cur (frame origins must not loop)")
                cur = (byId[cur]?.origin as? com.astarworks.astera.core.domain.skillengine.frame.grammar.OriginSpec.Frame)?.frame
            }
        }
        for (f in def.frames) {
            val origin = f.origin as? com.astarworks.astera.core.domain.skillengine.frame.grammar.OriginSpec.Instance ?: continue
            if (origin.name !in spawned) {
                return SkillDecodeError.UndefinedReference(def.id, "frames.${f.id}.origin", "instance '${origin.name}' (frame '${f.id}': no op of this skill spawns a projectile, summon or anchor with that id)")
            }
        }
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
