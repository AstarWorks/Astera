package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.frame.FrameNode
import com.astarworks.astera.core.domain.skillengine.hit.HitWindow
import com.astarworks.astera.core.domain.skillengine.model.AxisRef
import com.astarworks.astera.core.domain.skillengine.motion.AlignSpec
import com.astarworks.astera.core.domain.skillengine.motion.MotionSpec
import com.astarworks.astera.core.domain.skillengine.op.FrameOp
import com.astarworks.astera.core.domain.skillengine.op.HitOp
import com.astarworks.astera.core.domain.skillengine.op.SpawnOp
import kotlin.reflect.KClass

public fun spawnExecutors(): List<OpExecutor<*>> = listOf(
    ProjectileExecutor, AoeExecutor, SummonExecutor, SummonLightningExecutor,
)

/**
 * Projectile = a body frame in the world + a fly law + a hit volume (through the same executors a
 * hand-written `fly` / `hit` op uses). The Motion and Hit ops carry the projectile's own id, so
 * contacts arrive with `event.source == id` and `expire` / `block` handlers register under that id.
 * Terminal events (block, expire, or the hit that exhausts `pierce`) end the projectile after its
 * handler ran; `pierce: n` lets it pass through n victims.
 */
internal object ProjectileExecutor : OpExecutor<SpawnOp.Projectile> {
    override val opClass: KClass<SpawnOp.Projectile> = SpawnOp.Projectile::class

    override fun execute(op: SpawnOp.Projectile, ctx: OpContext) {
        val scope = ctx as? SkillCastScope ?: return
        if (!ctx.passes(op.whenExpr)) return
        val origin = ctx.locate(op.from) ?: return
        val body = FrameId(op.body)
        val pose = Frame.fromDirection(origin, ctx.values.vec3(op.velocity))
        if (ctx.tree.node(body) == null) {
            ctx.tree.add(FrameNode(body, FrameKind.BODY, WORLD, pose))
        } else {
            ctx.tree.reparent(body, WORLD, keepWorld = false, snap = pose)
        }

        var passed = 0
        scope.onEvent("hit", op.id) {
            ctx.run(op.onHit)
            passed++
            if (passed > op.pierce) scope.endProjectile(op.id, body)
        }
        scope.onEvent("block", op.id) {
            ctx.run(op.onBlock)
            scope.endProjectile(op.id, body)
        }
        scope.onEvent("expire", op.id) {
            ctx.run(op.onExpire)
            scope.endProjectile(op.id, body)
        }

        ctx.run(
            listOf(
                FrameOp.Motion(
                    id = op.id,
                    whenExpr = null,
                    frame = op.body,
                    spec = MotionSpec.Fly(
                        velocity = op.velocity,
                        gravity = op.gravity,
                        drag = op.drag,
                        align = AlignSpec(AxisRef("velocity"), AxisRef("world.up")),
                        maxTicks = op.maxTicks,
                    ),
                ),
                HitOp.Hit(
                    id = op.id,
                    whenExpr = null,
                    frames = listOf(op.body),
                    volume = op.volume,
                    window = HitWindow.Once,
                    swept = true,
                ),
            ),
        )
    }
}

/**
 * Aoe = a hit volume on a transient point frame at `at` (oriented like the caster), resolved once.
 * Every victim inside gets one contact (`event.source == id`) and runs `on_hit` with it in scope.
 */
internal object AoeExecutor : OpExecutor<SpawnOp.Aoe> {
    override val opClass: KClass<SpawnOp.Aoe> = SpawnOp.Aoe::class

    override fun execute(op: SpawnOp.Aoe, ctx: OpContext) {
        val scope = ctx as? SkillCastScope ?: return
        if (!ctx.passes(op.whenExpr)) return
        val origin = ctx.locate(op.at) ?: return
        val rotation = ctx.tree.worldOf(CASTER).getOrNull()?.rotation ?: Frame(origin).rotation
        val frame = FrameId("${op.id}#aoe")
        ctx.tree.add(FrameNode(frame, FrameKind.POINT, WORLD, Frame(origin, rotation)))
        try {
            val volume = ctx.resolveVolume(op.volume, frame) ?: return
            val candidates = ctx.ports.entities.nearby(volume.broadCenter, volume.broadRadius, scope.caster)
            val contacts = scope.contacts.narrow(op.id, scope.tick, frame.value, volume, Vec3.ZERO, candidates)
            for (contact in contacts) {
                scope.withContact(contact) { ctx.run(op.onHit) }
            }
        } finally {
            ctx.tree.remove(frame)
        }
    }
}

internal object SummonExecutor : OpExecutor<SpawnOp.Summon> {
    override val opClass: KClass<SpawnOp.Summon> = SpawnOp.Summon::class

    override fun execute(op: SpawnOp.Summon, ctx: OpContext) {
        val scope = ctx as? SkillCastScope ?: return
        if (!ctx.passes(op.whenExpr)) return
        val at = ctx.locate(op.at) ?: return
        val count = ctx.values.num(op.count).toInt().coerceAtLeast(0)
        scope.effects.summon(scope.caster, at, op.entityType, count, ctx.values.num(op.lifetimeTicks).toInt())
    }
}

internal object SummonLightningExecutor : OpExecutor<SpawnOp.SummonLightning> {
    override val opClass: KClass<SpawnOp.SummonLightning> = SpawnOp.SummonLightning::class

    override fun execute(op: SpawnOp.SummonLightning, ctx: OpContext) {
        val scope = ctx as? SkillCastScope ?: return
        if (!ctx.passes(op.whenExpr)) return
        val at = ctx.locate(op.at) ?: return
        scope.effects.lightning(scope.caster, at, op.visualOnly)
    }
}
