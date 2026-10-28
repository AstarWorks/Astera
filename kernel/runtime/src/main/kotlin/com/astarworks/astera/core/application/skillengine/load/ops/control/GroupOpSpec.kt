package com.astarworks.astera.core.application.skillengine.load.ops.control

import com.astarworks.astera.core.application.skillengine.compose.ChildLowering
import com.astarworks.astera.core.application.skillengine.compose.GroupUnroller
import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath

/**
 * Decoded timing group (ADR-0303 2.0a): `{id, at, every, for, times, until, lasts, do}`.
 *
 * [rawDo] is the YAML list as written. A group's children are ops of any kind, so they are decoded by the document's
 * `OpListDecoder` (which owns the registry) and attached with [withBody]; [lowerChild] is that decoder's way to lower a child
 * with the spec that decoded it. A group that was never given a body fails at lowering.
 */
public class GroupOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val rawDo: List<Any?>,
    public val body: List<OpNode> = emptyList(),
    public val lowerChild: ChildLowering? = null,
) : OpNode {
    public fun withBody(children: List<OpNode>, lowering: ChildLowering): GroupOp =
        GroupOp(id, src, whenExpr, timing, rawDo, children, lowering)
}

public object GroupOpSpec : OpSpec<GroupOp> {
    override val name: String = "group"
    override val section: OpSection = OpSection.CONTROL
    override val timing: TimingKind? = TimingKind.GROUP

    private val rawList: FieldType<List<Any?>> = object : FieldType<List<Any?>> {
        override val label = "non-empty list of ops"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): List<Any?> {
            val l = raw as? List<*> ?: ctx.mismatch(path, label, raw)
            if (l.isEmpty()) ctx.fail(path, "a group needs at least one op in do")
            return l
        }
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "array", "minItems" to 1, "items" to linkedMapOf("type" to "object"))
    }

    private val doField = Field.required("do", rawList, "ops (or nested groups) run once per occurrence, clock reset to the occurrence tick")

    override val fields: Fields = Fields.of("group", doField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<GroupOp, SkillDecodeError> =
        Result.Success(GroupOp(id, src, whenExpr, timing, values[doField]))

    /** One occurrence of the body, rows relative to the occurrence tick 0 (no outer horizon known here). [GroupUnroller.unroll] is the schedule. */
    override fun lower(op: GroupOp, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        GroupUnroller.lowerBody(op, lc, null)
}
