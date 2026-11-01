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
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/** Decoded `status` op: apply a status to a target set for the `for:` window (ADR-0303 10.1b). */
public class StatusOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val target: TargetRef,
    val status: String,
    val stacks: Value<Double>,
) : OpNode

public object StatusOpSpec : OpSpec<StatusOp> {
    override val name: String = "status"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT

    private val targetField = Field.required("target", GameplayOps.target, "victim, caster or an area")
    private val statusField = Field.required("status", Types.id, "status id")
    private val stacksField = Field.required("stacks", Types.number, "stacks applied")

    override val fields: Fields = Fields.of("status", targetField, statusField, stacksField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<StatusOp, SkillDecodeError> =
        Result.Success(
            StatusOp(
                id = id,
                src = src,
                whenExpr = whenExpr,
                timing = timing,
                target = values[targetField],
                status = values[statusField],
                stacks = values[stacksField],
            ),
        )

    override fun lower(op: StatusOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        val chanceError = holdChanceError(op, lc, path)
        if (chanceError != null) return Result.Failure(chanceError)
        return GameplayOps.statusId(lc, path, "status", op.status).flatMap { _ -> GameplayOps.targetRule(op.target, lc, path) }.flatMap { targets ->
            GameplayOps.duration(op.timing, lc, path, "for").flatMap { forTicks ->
                GameplayOps.whole(op.stacks, lc, "$path.stacks").map { stacks ->
                    GameplayOps.lowered(lc, Action.Status(targets, op.status, Val.Known(stacks), forTicks))
                }
            }
        }
    }

    /**
     * Hold table (founder 2026-10-31): a movement-hold status (freeze, shock, root, slow family) always lands. Gating it on `rand()` / `roll()`
     * in `when:` is a load error; the strength of a hold is its `for:` window, not a dice roll.
     */
    private fun holdChanceError(op: StatusOp, lc: LowerContext, path: String): SkillDecodeError? {
        if (op.status !in HOLD_STATUSES) return null
        val source = (op.whenExpr as? WhenRef.Expr)?.compiled?.source ?: return null
        if (!CHANCE_CALL.containsMatchIn(source)) return null
        return SkillDecodeError.Invalid(
            lc.skillId, path,
            "hold status '${op.status}' must not be gated by chance (when: $source); hold strength is the for: window, not rand()/roll() (hold table)",
        )
    }

    private val HOLD_STATUSES: Set<String> = setOf("freeze", "shock", "root", "slow", "sinking", "stun")
    private val CHANCE_CALL = Regex("""\b(rand|roll)\s*\(""")
}
