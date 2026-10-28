package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Val

/** Decoded `impulse` op: a velocity added to the target (world axes). */
public class ImpulseOp(
    override val id: OpId,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val target: TargetRef,
    val vector: Value<Vec3>,
) : OpNode

public object ImpulseOpSpec : OpSpec<ImpulseOp> {
    override val name: String = "impulse"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT

    private val targetField = Field.required("target", GameplayOps.target, "victim, caster or an area")
    private val vectorField = Field.required("vector", Types.worldVec3, "[x, y, z] in world axes")

    override val fields: Fields = Fields.of("impulse", targetField, vectorField)

    override fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ImpulseOp, SkillDecodeError> =
        Result.Success(ImpulseOp(id, whenExpr, timing, values[targetField], values[vectorField]))

    override fun lower(op: ImpulseOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        return GameplayOps.targetCell(op.target, lc, path).flatMap { target ->
            GameplayOps.literalVec(op.vector, lc, "$path.vector").map { vector ->
                GameplayOps.effect(lc, "impulse", mapOf<String, Val<Any>>("target" to target, "vector" to Val.Known(vector)))
            }
        }
    }
}
