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
 * Explosion at the target position. With components, the resolved target (or, when `radius` is set,
 * every enemy within it) also takes them; the vanilla blast itself is the gateway's `explode`.
 *
 * Damage and push follow v1's explosion: the effective radius is `radius`, else `power`; a body whose feet are
 * `d` from the blast takes `components * (1 - d / radius)` (nothing at or beyond the edge) and is pushed away from
 * the blast centre by an impulse of length `1 - d / radius` (straight up when it sits exactly on the centre).
 * `d` is measured to the victim's FEET (v1 `PaperExplosionGateway`: `victim.location`, `dx/dy/dz` to the centre,
 * `falloff = 1 - distance / radius`), not to the body centre.
 * Radius 0 means "only what stands on the centre": uniform damage, no push.
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
        val radius = op.radius?.let { ctx.values.num(it) }
        val bodies: List<Pair<UUID, Vec3>> = if (radius == null && op.at == null) {
            ctx.resolveTargets(scope, op.target).map { it to feetPosition(ctx, it) }
        } else {
            ctx.ports.entities.nearby(at, radius ?: 0.0, scope.caster).filter { !scope.isAlly(it.entityId) }.map { it.entityId to feetPosition(ctx, it.entityId) }
        }
        val reach = (radius ?: power).coerceAtLeast(0.0)
        for ((victim, centre) in bodies) {
            val falloff = if (reach > 0.0) (1.0 - centre.distance(at) / reach).coerceIn(0.0, 1.0) else 1.0
            if (falloff <= 0.0) continue
            ctx.damageAll(scope, listOf(victim), op.components, at, scaleOf = { falloff })
            if (reach > 0.0) {
                val away = centre - at
                val radial = if (away.lengthSquared() < 1e-12) Vec3(0.0, 1.0, 0.0) else away.normalize()
                ctx.ports.gameplay.impulse(victim, radial * falloff)
            }
        }
    }

    private fun feetPosition(ctx: OpContext, victim: UUID): Vec3 = ctx.ports.entities.rootOf(victim)?.feet ?: Vec3.ZERO
}
