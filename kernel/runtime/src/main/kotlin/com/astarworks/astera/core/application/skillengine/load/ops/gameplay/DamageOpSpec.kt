package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

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
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Val

/** Decoded `damage` op: components applied to one target set (ADR-0303 10.1b, 11.3 `damage targets= amount=`). */
public class DamageOp(
    override val id: OpId,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val target: TargetRef,
    val components: List<DamageComponentDecl>,
    val ignoreInvulnerability: Boolean,
) : OpNode

public data class DamageComponentDecl(val attribute: DamageAttribute, val amount: Value<Double>)

public object DamageOpSpec : OpSpec<DamageOp> {
    override val name: String = "damage"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT

    private val componentKind = Field.required("kind", Types.enumOf("physical", "fire", "electric", "wind", "water", "earth"), "element family")
    private val componentAmount = Field.required("amount", Types.number, "damage before the matrix")
    private val componentFields: Fields = Fields.of("damage.component", componentKind, componentAmount)

    private val targetField = Field.required("target", GameplayOps.target, "victim, caster or an area")
    private val componentsField = Field.required("components", Types.list(Types.nested(componentFields)), "one or more {kind, amount}")
    private val ignoreField = Field.optional("ignore_invulnerability", GameplayOps.flag, false, "bypass invulnerability windows")

    override val fields: Fields = Fields.of("damage", targetField, componentsField, ignoreField)

    override fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<DamageOp, SkillDecodeError> =
        Grammar.guard {
            val comps = values[componentsField]
            if (comps.isEmpty()) ctx.fail("damage.components", "needs at least one component")
            DamageOp(
                id = id,
                whenExpr = whenExpr,
                timing = timing,
                target = values[targetField],
                components = comps.map { c -> DamageComponentDecl(DamageAttribute.valueOf(c[componentKind].uppercase()), c[componentAmount]) },
                ignoreInvulnerability = values[ignoreField],
            )
        }

    override fun lower(op: DamageOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        if (op.ignoreInvulnerability) {
            return Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "ignore_invulnerability has no field on Action.Damage yet (domain request)"))
        }
        return GameplayOps.targetCell(op.target, lc, path).flatMap { targets ->
            val components = ArrayList<DamageComponent>(op.components.size)
            for (c in op.components) {
                when (val amount = GameplayOps.literal(c.amount, lc, "$path.damage.components")) {
                    is Result.Failure -> return@flatMap Result.Failure(amount.error)
                    is Result.Success -> components += DamageComponent(NumericValue.Literal(amount.value), c.attribute)
                }
            }
            Result.Success(GameplayOps.lowered(lc, Action.Damage(targets, components, Val.Known(1.0))))
        }
    }
}
