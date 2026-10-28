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
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath

/**
 * Decoded `remove` op: the presentation-only early end of the listed instances (ADR-0303 5, 10.6). It runs no exit
 * actions; `cancel` is the form that does. It is never the only end of an instance (the owner's lifetime ends it).
 */
public class RemoveOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val remove: List<String>,
) : OpNode

public object RemoveOpSpec : OpSpec<RemoveOp> {
    override val name: String = "remove"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT

    private val removeField = Field.required("remove", Types.list(Types.id), "instance ids to end early")

    override val fields: Fields = Fields.of("remove", removeField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<RemoveOp, SkillDecodeError> =
        Result.Success(RemoveOp(id, src, whenExpr, timing, values[removeField]))

    override fun lower(op: RemoveOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        return GameplayOps.anchors(op.remove, lc, path).map { instances ->
            Lowered(rows = instances.map { GameplayOps.row(lc, it, Action.End(it)) })
        }
    }
}
