package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.AnchorRef
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.ops.world.WorldOps
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/**
 * Decoded `summon` (ADR-0313): starts an independent run of another skill at a point. The summoned skill declares exactly one `frames_in` frame
 * (the spawn point it is given) and no unvalued `required` param; it is owned by the caster for damage credit and cleanup.
 */
public class SummonOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val skill: String,
    public val anchor: AnchorRef,
    public val lasts: Int,
) : OpNode

public object SummonOpSpec : OpSpec<SummonOp> {
    override val name: String = "summon"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT

    private val skillField = Field.required("skill", Types.id, "id of the skill to run: its one frames_in frame is the spawn point")
    private val anchorField = Field.required("anchor", Types.anchorRef, "where it spawns: a declared anchor, or caster / caster.feet / contact.point, or {of, offset: {forward, up, left}}; it faces the caster's heading")
    private val lastsField = Field.required("life", WorldOps.intIn(1, 1200), "ticks the summon lives, then it is cut like a cancelled cast")

    override val fields: Fields = Fields.of("summon", skillField, anchorField, lastsField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<SummonOp, SkillDecodeError> =
        Result.Success(SummonOp(id, src, whenExpr, timing, values[skillField], values[anchorField], values[lastsField]))

    override fun lower(op: SummonOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val at = "${lc.src.value}.skill"
        val callee = lc.skills.skill(op.skill) ?: return Result.failure(SkillDecodeError.UndefinedReference(lc.skillId, at, op.skill))
        if (callee.framesIn.size != 1) {
            return Result.failure(SkillDecodeError.Invalid(lc.skillId, at, "summoned skill '${callee.id}' must declare exactly one frames_in frame (its spawn point), found ${callee.framesIn.keys}"))
        }
        if (callee.unvaluedRequiredParams.isNotEmpty()) {
            return Result.failure(SkillDecodeError.Invalid(lc.skillId, at, "summoned skill '${callee.id}' has required params without a default ${callee.unvaluedRequiredParams}; a summon cannot value them"))
        }
        if (callee.anchors.isNotEmpty() || callee.fragments.isNotEmpty() || callee.conditions.isNotEmpty()) {
            return Result.failure(SkillDecodeError.Invalid(lc.skillId, at, "summoned skill '${callee.id}' declares anchors, fragments or conditions; a summoned skill sees only its frames_in frame"))
        }
        return WorldOps.position(op.anchor, lc).map { origin ->
            val action = Action.Summon(op.skill, origin, Val.Live<Vector3d>("= forward(caster)", null), op.lasts)
            Lowered(rows = listOf(Row(0, lc.self, action, lc.src)))
        }
    }
}
