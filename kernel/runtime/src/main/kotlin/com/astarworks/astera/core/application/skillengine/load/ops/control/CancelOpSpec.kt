package com.astarworks.astera.core.application.skillengine.load.ops.control

import com.astarworks.astera.core.application.skillengine.compose.CancelLowering
import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
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
import com.astarworks.astera.core.domain.skillengine.table.OpId

/** What a `cancel` ends (ADR-0303 10.6). */
public sealed interface CancelTarget {
    public data object Cast : CancelTarget
    public data object State : CancelTarget

    /** `<id>`, `volley[2]`, `g/ball`; [fromRoot] = written with a leading `/`, else relative to the enclosing group. */
    public data class Instance(val path: List<PathSegment>, val fromRoot: Boolean) : CancelTarget

    public data class PathSegment(val op: String, val index: Int?)
}

/** Decoded `cancel` op (10.6): ends the cast, the current state, or one named instance, running exit actions. */
public class CancelOp(
    override val id: OpId,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val target: CancelTarget,
) : OpNode

public object CancelOpSpec : OpSpec<CancelOp> {
    private val SEGMENT = Regex("""^([A-Za-z_][A-Za-z0-9_-]*)(?:\[(\d+)])?$""")

    private val scope = Field.required(
        "scope", Types.id,
        "cast | state | <instance id>: the whole cast, the current state of this machine level, or one instance and what it owns",
    )

    override val name: String = "cancel"
    override val section: OpSection = OpSection.CONTROL
    override val timing: TimingKind? = null
    override val fields: Fields = Fields.of("cancel", scope)

    override fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<CancelOp, SkillDecodeError> =
        Grammar.guard {
            val path = "cancel[${id.value}].scope"
            val text = values[scope]
            val target = when (text) {
                "cast" -> CancelTarget.Cast
                "state" -> CancelTarget.State
                else -> {
                    val fromRoot = text.startsWith("/")
                    val segments = text.removePrefix("/").split('/').map { part ->
                        val m = SEGMENT.matchEntire(part) ?: ctx.fail(path, "'$text' is not cast, state or an instance id such as 'ball' or 'volley[2]/ball'")
                        CancelTarget.PathSegment(m.groupValues[1], m.groupValues[2].ifEmpty { null }?.toInt())
                    }
                    CancelTarget.Instance(segments, fromRoot)
                }
            }
            CancelOp(id, whenExpr, timing, target)
        }

    override fun lower(op: CancelOp, lc: LowerContext): Result<Lowered, SkillDecodeError> = CancelLowering.lower(op.id, op.target, lc)
}
