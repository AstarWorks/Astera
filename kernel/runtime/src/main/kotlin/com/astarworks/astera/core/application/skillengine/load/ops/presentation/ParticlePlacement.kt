package com.astarworks.astera.core.application.skillengine.load.ops.presentation

import com.astarworks.astera.core.application.skillengine.compose.AnchorNames
import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.AnchorRef
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVec
import com.astarworks.astera.core.application.skillengine.load.ops.OccurrenceBake
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.joml.Vector3d

/**
 * Where one copy of a `particle` / `particle_def` is emitted (ADR-0303 2.2, R10, R14): the anchor point, displaced by the anchor's
 * `offset` (anchor axes) and by the op's `shift` (per copy; `{forward, up, left}` = anchor axes, a whole vec = world axes).
 * No displacement lowers to the bare anchor cell; otherwise to one `= expr` cell with the occurrence baked in ([OccurrenceBake]).
 */
internal object ParticlePlacement {
    class Placed(val position: Val<Vector3d>, val sweep: Double?)

    fun place(op: String, lc: LowerContext, anchor: AnchorRef, shift: FrameVec?): Result<Placed, SkillDecodeError> {
        fun invalid(message: String): Result<Placed, SkillDecodeError> =
            Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "$op: $message"))

        val resolved = lc.anchors.resolve(anchor.of)
            ?: return invalid("anchor '${anchor.of}' is not declared in this scope")
        if (anchor.offset == null && shift == null) {
            return Result.Success(Placed(Val.Live(resolved.instance.text, null), anchor.sweep))
        }
        val parts = AnchorNames.split(anchor.of)
        if (parts == null || parts.index != null) {
            return invalid("anchor '${anchor.of}' is a family member; offset / shift need a plain anchor name (write the displacement on the family's own op)")
        }
        val frame = parts.point?.let { "point(${parts.base}, '$it')" } ?: parts.base

        val terms = ArrayList<String>()
        terms += "pos($frame)"
        for (v in listOf(anchor.offset, (shift as? FrameVec.Parts))) {
            if (v is FrameVec.Parts) {
                for ((axis, c) in listOf("forward" to v.forward, "up" to v.up, "left" to v.left)) {
                    val src = componentSource(c) ?: return invalid("a \${param} in an offset / shift was not substituted before lowering")
                    if (src != "0.0") terms += "axis($frame, '$axis') * $src"
                }
            }
        }
        if (shift is FrameVec.Whole) {
            val w = shift.value
            terms += when (w) {
                is Value.Expr -> "(${w.source})"
                is Value.Literal -> "vec(${w.value.x}, ${w.value.y}, ${w.value.z})"
                is Value.ParamRef -> return invalid("a \${param} in an offset / shift was not substituted before lowering")
            }
        }
        val source = OccurrenceBake.bake(terms.joinToString(" + "), lc.occurrence)
        return Result.Success(Placed(Val.Live("= $source", null), anchor.sweep))
    }

    private fun componentSource(c: Value<Double>): String? = when (c) {
        is Value.Literal -> c.value.toString()
        is Value.Expr -> "(${c.source})"
        is Value.ParamRef -> null
    }
}
