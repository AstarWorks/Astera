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
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/**
 * Decoded `hurt` op (R44): plays the target's hurt animation as if struck from the anchor [from]. It deals no damage and
 * pushes nothing (v2 `victim_anim` minus its dead `strength`).
 */
public class HurtOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val target: TargetRef,
    val from: String,
) : OpNode

public object HurtOpSpec : OpSpec<HurtOp> {
    override val name: String = "hurt"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT

    private val targetField = Field.required("target", GameplayOps.target, "victim, caster or an area")
    private val fromField = Field.required("from", Types.id, "anchor the hurt comes from")

    override val fields: Fields = Fields.of("hurt", targetField, fromField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<HurtOp, SkillDecodeError> =
        Result.Success(HurtOp(id, src, whenExpr, timing, values[targetField], values[fromField]))

    override fun lower(op: HurtOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        return GameplayOps.targetCell(op.target, lc, path).flatMap { target ->
            GameplayOps.anchor(op.from, lc, "$path.from").map { inst ->
                GameplayOps.effect(lc, "hurt", mapOf<String, Val<Any>>("target" to target, "from" to Val.Known("instance:" + inst.text)))
            }
        }
    }
}
