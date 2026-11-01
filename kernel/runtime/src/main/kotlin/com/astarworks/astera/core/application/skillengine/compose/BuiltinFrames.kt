package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameRefs
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import com.astarworks.astera.core.domain.skillengine.frame.grammar.AxisSpec
import com.astarworks.astera.core.domain.skillengine.frame.grammar.Coeff
import com.astarworks.astera.core.domain.skillengine.frame.grammar.FrameDecl
import com.astarworks.astera.core.domain.skillengine.frame.grammar.OriginSpec
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.table.ExpansionTable

/**
 * Frames every skill may name without declaring them (ADR-0314), so no skill repeats `{id: eye, origin: caster.eye, v: aim, w: up, s: v x w}`:
 *
 * - `caster` / `caster.<point>` (eye, feet, body, center, hand_r, hand_l, shoulder_r, shoulder_l): rooted on that point of the caster, `v` = the aim
 *   direction (`forward`, the horizontal heading, for `feet`), `w` = up, `s` = `v x w`.
 * - `weapon.<point>` (grip, tip, root, pivot, muzzle: any point of the skill item's held-hand part in `content/frames/<item>.yaml`): the point of the held
 *   weapon, with the weapon held level (its `long` axis along `v` = aim, its `edge` axis along `w` = up, as the skin rig holds it). Rooted on `caster.hand_r`
 *   (the grip) and moved by the point's offset from the grip in the model's `long` / `edge`, scaled by the rig's player scale; axes as `caster`.
 *
 * A skill declares a frame only for something non-standard: `move` / `rotate` keys, a snapshot, other axes. The compiler adds the declaration of each built-in
 * frame a skill's rows read (and the `caster.hand_r` a `weapon.<point>` roots on) to the skill's `frames:`, so the runtime sees one kind of frame.
 */
internal object BuiltinFrames {
    private val CASTER_POINTS = setOf("eye", "feet", "body", "center", "hand_r", "hand_l", "shoulder_r", "shoulder_l")
    private const val WEAPON = "weapon."

    fun isCaster(id: String): Boolean = id == "caster" || (id.startsWith("caster.") && id.removePrefix("caster.") in CASTER_POINTS)

    fun isWeapon(id: String): Boolean = id.startsWith(WEAPON) && id.length > WEAPON.length && !id.removePrefix(WEAPON).contains('.')

    /** A `caster[.point]` / `weapon.<point>` name (`weapon` alone is not one: it stays free for a skill's own frame). */
    fun isBuiltin(id: String): Boolean = isCaster(id) || isWeapon(id)

    private fun casterFrame(id: String): FrameDecl {
        val point = id.removePrefix("caster").removePrefix(".")
        val v = AxisSpec.Named(if (point == "feet") "forward" else "aim")
        return FrameDecl(id, OriginSpec.Caster(point), v, AxisSpec.Named(if (point == "feet") "up" else "head.up"), AxisSpec.Cross("v", "w"))
    }

    /**
     * [def] with the declaration of every built-in frame its rows and lines read added to `frames:`. A `weapon.<point>` the skill's item has no point
     * for (or a skill without an item) is a load error naming the frame.
     */
    fun synthesize(def: SkillDef, table: ExpansionTable, tables: FrameTables): Result<SkillDef, SkillDecodeError> {
        val rows = table.states.flatMap { it.rows } + table.subTables.values.flatMap { it.rows } + table.worldRows
        val names = LinkedHashSet<String>()
        for (row in rows) for (n in FrameRefCheck.refsOf(row)) names += n
        for (l in def.lines) { names += l.from.frame; names += l.to.frame }
        val declared = def.frames.map { it.id }.toSet() + def.framesIn.keys
        val wanted = names.filter { it !in declared && isBuiltin(it) }
        if (wanted.isEmpty()) return Result.Success(def)
        val out = ArrayList<FrameDecl>()
        fun add(d: FrameDecl) { if (out.none { it.id == d.id } && def.frames.none { it.id == d.id }) out += d }
        for (id in wanted) {
            if (isCaster(id)) { add(casterFrame(id)); continue }
            val point = id.removePrefix(WEAPON)
            val item = def.item?.takeIf { it != "any" }
                ?: return Result.Failure(SkillDecodeError.UndefinedReference(def.id, "${FrameRefs.PREFIX}$id", "weapon point '$point' (frame '$id': the skill has no item, so there is no held weapon)"))
            val table0 = tables.item(item)
            val part = table0?.handParts()?.firstOrNull() ?: table0?.partFor(null)
                ?: return Result.Failure(SkillDecodeError.UndefinedReference(def.id, "${FrameRefs.PREFIX}$id", "content/frames/$item.yaml (frame '$id': the item has no frames table)"))
            val grip = part.points["grip"]
            val p = part.points[point]
            val long = part.axes["long"]
            val edge = part.axes["edge"]
            if (grip == null || p == null || long == null || edge == null) {
                return Result.Failure(SkillDecodeError.UndefinedReference(def.id, "${FrameRefs.PREFIX}$id", "point '$point' (frame '$id': content/frames/$item.yaml part '${part.name}' needs points grip and $point, axes long and edge; has ${part.points.keys})"))
            }
            val rel = p - grip
            val k = SkinRigTransforms.PLAYER_SCALE
            add(casterFrame("caster.hand_r"))
            add(FrameDecl(id, OriginSpec.Frame("caster.hand_r", Coeff(rel.dot(long) * k, rel.dot(edge) * k, 0.0)), AxisSpec.Named("aim"), AxisSpec.Named("head.up"), AxisSpec.Cross("v", "w")))
        }
        return Result.Success(def.copy(frames = def.frames + out))
    }
}
