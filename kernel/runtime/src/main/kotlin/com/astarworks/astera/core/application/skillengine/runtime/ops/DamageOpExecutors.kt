package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.op.DamageComponentSpec
import com.astarworks.astera.core.domain.skillengine.op.DamageOp
import java.util.UUID
import kotlin.reflect.KClass

public fun damageExecutors(): List<OpExecutor<*>> = listOf(
    DamageExecutor, HealExecutor, ResurrectExecutor, ExplodeExecutor,
)

private fun OpContext.damageAll(
    scope: SkillCastScope,
    victims: List<UUID>,
    specs: List<DamageComponentSpec>,
    fallbackPoint: Vec3?,
) {
    if (victims.isEmpty() || specs.isEmpty()) return
    val parts = specs.map { SkillDamage(it.kind, values.num(it.amount)) }
    for (victim in victims) {
        val point = scope.contact?.takeIf { it.victim == victim }?.pointWorld
            ?: feetOf(victim)
            ?: fallbackPoint
            ?: Vec3.ZERO
        ports.gameplay.damage(scope.caster, victim, parts, point)
    }
}

internal object DamageExecutor : OpExecutor<DamageOp.Damage> {
    override val opClass: KClass<DamageOp.Damage> = DamageOp.Damage::class

    override fun execute(op: DamageOp.Damage, ctx: OpContext) {
        val scope = ctx as? SkillCastScope ?: return
        if (!ctx.passes(op.whenExpr)) return
        ctx.damageAll(scope, ctx.resolveTargets(scope, op.target), op.components, ctx.positionOf(scope, op.target))
    }
}

internal object HealExecutor : OpExecutor<DamageOp.Heal> {
    override val opClass: KClass<DamageOp.Heal> = DamageOp.Heal::class

    override fun execute(op: DamageOp.Heal, ctx: OpContext) {
        val scope = ctx as? SkillCastScope ?: return
        if (!ctx.passes(op.whenExpr)) return
        val amount = ctx.values.num(op.amount)
        for (victim in ctx.resolveTargets(scope, op.target)) scope.effects.heal(scope.caster, victim, amount)
    }
}

internal object ResurrectExecutor : OpExecutor<DamageOp.Resurrect> {
    override val opClass: KClass<DamageOp.Resurrect> = DamageOp.Resurrect::class

    override fun execute(op: DamageOp.Resurrect, ctx: OpContext) {
        val scope = ctx as? SkillCastScope ?: return
        if (!ctx.passes(op.whenExpr)) return
        val fraction = ctx.values.num(op.healthFraction).coerceIn(0.0, 1.0)
        for (victim in ctx.resolveTargets(scope, op.target)) scope.effects.resurrect(scope.caster, victim, fraction)
    }
}

/**
 * Explosion at the target position. With components, the resolved target (or, when `radius` is set,
 * every enemy within it) also takes them; the vanilla blast itself is the gateway's `explode`.
 */
internal object ExplodeExecutor : OpExecutor<DamageOp.Explode> {
    override val opClass: KClass<DamageOp.Explode> = DamageOp.Explode::class

    override fun execute(op: DamageOp.Explode, ctx: OpContext) {
        val scope = ctx as? SkillCastScope ?: return
        if (!ctx.passes(op.whenExpr)) return
        val at = ctx.positionOf(scope, op.target) ?: return
        scope.effects.explode(scope.caster, at, ctx.values.num(op.power), op.breakBlocks, op.incendiary)
        if (op.components.isEmpty()) return
        val radius = op.radius?.let { ctx.values.num(it) }
        val victims = if (radius == null) {
            ctx.resolveTargets(scope, op.target)
        } else {
            ctx.ports.entities.nearby(at, radius, scope.caster).map { it.entityId }.filter { !scope.isAlly(it) }
        }
        ctx.damageAll(scope, victims, op.components, at)
    }
}
