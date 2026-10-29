package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarSource
import com.astarworks.astera.core.application.skillengine.load.grammar.OffsetVec
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/** Decoded `impulse` op: a velocity added to the target, along an anchor's axes or in world axes (R5; subsumes v2 `propel`). */
public class ImpulseOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val target: TargetRef,
    val vector: OffsetVec,
) : OpNode

public object ImpulseOpSpec : OpSpec<ImpulseOp> {
    override val name: String = "impulse"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT

    private val targetField = Field.required("target", GameplayOps.target, "victim, caster or an area")
    private val vectorField = Field.required("vector", Types.offsetVec, "{of: <anchor>, forward, up, left} along that anchor's axes at the row tick, {x, y, z} in world axes, or a world CEL vec3")

    override val fields: Fields = Fields.of("impulse", targetField, vectorField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ImpulseOp, SkillDecodeError> =
        Result.Success(ImpulseOp(id, src, whenExpr, timing, values[targetField], values[vectorField]))

    override fun lower(op: ImpulseOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        return GameplayOps.targetCell(op.target, lc, path).flatMap { target ->
            vector(op.vector, lc, "$path.vector").map { vector ->
                GameplayOps.effect(lc, "impulse", mapOf<String, Val<Any>>("target" to target, "vector" to vector))
            }
        }
    }

    private fun vector(v: OffsetVec, lc: LowerContext, path: String): Result<Val<Any>, SkillDecodeError> = when (v) {
        is OffsetVec.World -> Result.Success(
            when (val w = v.value) {
                is Value.Literal -> Val.Known(w.value)
                is Value.ParamRef -> Val.Live("\${${w.name}}", null)
                is Value.Expr -> Val.Live("= ${w.source}", null)
            },
        )
        is OffsetVec.InAnchor -> {
            val name = v.of.of
            if (name !in V3Bindings.BUILTIN_FRAMES && lc.anchors.resolve(name) == null && lc.anchors.document(name) == null) {
                Result.Failure(SkillDecodeError.UndefinedReference(lc.skillId, "$path.of", name))
            } else {
                // The anchor's axes are read at the row's tick (class L), so the sum stays a live expression.
                val terms = listOf("forward" to v.parts.forward, "up" to v.parts.up, "left" to v.parts.left)
                    .filterNot { (_, n) -> n is Value.Literal && n.value == 0.0 }
                    .map { (axis, n) -> "$axis($name) * ${GrammarSource.of(n)}" }
                Result.Success(Val.Live("= " + terms.joinToString(" + ").ifEmpty { "vec(0.0, 0.0, 0.0)" }, null))
            }
        }
    }
}
