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
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/** Decoded `damage` op: components applied to one target set (ADR-0303 10.1b, 11.3 `damage targets= amount=`). */
public class DamageOp(
    override val id: OpId,
    override val src: SrcPath,
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

    private val kindFields: List<Pair<DamageAttribute, Field<Value<Double>?>>> = DamageAttribute.entries.map {
        it to Field.optional(it.name.lowercase(), Types.nullable(Types.number), null, "${it.name.lowercase()} damage before the matrix")
    }
    private val damageSpec: Fields = Fields.of("damage.kinds", *kindFields.map { it.second }.toTypedArray())

    private val targetField = Field.required("target", GameplayOps.target, "victim, caster or an area")
    private val damageField = Field.required("damage", Types.nested(damageSpec), "{kind: amount} map, one or more kinds")
    private val ignoreField = Field.optional("ignore_invulnerability", GameplayOps.flag, false, "bypass invulnerability windows")

    override val fields: Fields = Fields.of("damage", targetField, damageField, ignoreField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<DamageOp, SkillDecodeError> =
        Grammar.guard {
            val d = values[damageField]
            val comps = kindFields.mapNotNull { (attr, f) -> d[f]?.let { DamageComponentDecl(attr, it) } }
            if (comps.isEmpty()) ctx.fail("damage.damage", "needs at least one damage kind")
            DamageOp(
                id = id,
                src = src,
                whenExpr = whenExpr,
                timing = timing,
                target = values[targetField],
                components = comps,
                ignoreInvulnerability = values[ignoreField],
            )
        }

    override fun lower(op: DamageOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        return GameplayOps.targetRule(op.target, lc, path).flatMap { targets ->
            val components = op.components.map { c ->
                val base = when (val v = c.amount) {
                    is Value.Literal -> NumericValue.Literal(v.value)
                    is Value.ParamRef -> NumericValue.Cel("\${${v.name}}")
                    is Value.Expr -> NumericValue.Cel(v.source)
                }
                DamageComponent(base, c.attribute)
            }
            Result.Success(GameplayOps.lowered(lc, Action.Damage(targets, components, op.ignoreInvulnerability)))
        }
    }
}
