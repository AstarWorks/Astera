package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.op.DamageComponentSpec
import com.astarworks.astera.core.domain.skillengine.op.DamageOp
import com.astarworks.astera.core.domain.skillengine.op.StackSide
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
    ignoreInvulnerability: Boolean = false,
    ignoreArmor: Boolean = false,
    /** Per-victim damage multiplier (explosion falloff); a victim at 0 is skipped. */
    scaleOf: (UUID) -> Double = { 1.0 },
) {
    if (victims.isEmpty() || specs.isEmpty()) return
    val consumed = LinkedHashSet<Pair<UUID, String>>()
    for (victim in victims) {
        val scale = scaleOf(victim)
        if (scale <= 0.0) continue
        val parts = specs.map { spec ->
            val extra = spec.perStack?.let { ps ->
                val holder = if (ps.on == StackSide.CASTER) scope.caster else victim
                if (ps.consume) consumed += holder to ps.status
                values.num(ps.amount) * ports.gameplay.statusStacks(holder, ps.status)
            } ?: 0.0
            SkillDamage(spec.kind, (values.num(spec.amount) + extra) * scale)
        }
        val point = scope.contact?.takeIf { it.victim == victim }?.pointWorld
            ?: feetOf(victim)
            ?: fallbackPoint
            ?: Vec3.ZERO
        ports.gameplay.damage(scope.skillId, scope.caster, victim, parts, point, ignoreInvulnerability, ignoreArmor)
    }
    for ((holder, status) in consumed) ports.gameplay.removeStatus(holder, status)
}

internal object DamageExecutor : OpExecutor<DamageOp.Damage> {
    override val opClass: KClass<DamageOp.Damage> = DamageOp.Damage::class

    override fun execute(op: DamageOp.Damage, ctx: OpContext) {
        val scope = ctx as? SkillCastScope ?: return
        if (!ctx.passes(op.whenExpr)) return
        ctx.damageAll(scope, ctx.resolveTargets(scope, op.target), op.components, ctx.positionOf(scope, op.target), op.ignoreInvulnerability, op.ignoreArmor)
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
 * Explosion at the target position (v1 semantics): every enemy within `radius` (default: `power`) of the centre takes the
 * components, scaled linearly by `1 - distance / radius` (distance centre -> victim feet); the vanilla blast itself is
 * the gateway's `explode`.
 */
internal object ExplodeExecutor : OpExecutor<DamageOp.Explode> {
    override val opClass: KClass<DamageOp.Explode> = DamageOp.Explode::class

    override fun execute(op: DamageOp.Explode, ctx: OpContext) {
        val scope = ctx as? SkillCastScope ?: return
        if (!ctx.passes(op.whenExpr)) return
        val at = (op.at?.let { ctx.locate(it) } ?: ctx.positionOf(scope, op.target)) ?: return
        val power = ctx.values.num(op.power)
        scope.effects.explode(scope.caster, at, power, op.breakBlocks, op.incendiary)
        if (op.components.isEmpty()) return
        val radius = op.radius?.let { ctx.values.num(it) } ?: power
        if (radius <= 0.0) return
        val victims = ctx.ports.entities.nearby(at, radius, scope.caster).map { it.entityId }.filter { !scope.isAlly(it) }
        ctx.damageAll(scope, victims, op.components, at, scaleOf = { v ->
            // v1 PaperExplosionGateway measured from the centre to the victim body centre (feet + 0.9).
            val body = (ctx.feetOf(v) ?: at).let { Vec3(it.x, it.y + 0.9, it.z) }
            (1.0 - body.distance(at) / radius).coerceIn(0.0, 1.0)
        })
    }
}
