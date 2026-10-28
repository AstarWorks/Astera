package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.op.MovementOp
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.reflect.KClass

/** Ballistic launch velocity (blocks/tick) from `from` to `to` with an apex `arcHeight` above the higher end. */
public object LeapArc {
    public const val GRAVITY: Double = 0.08
    private const val MIN_APEX: Double = 0.25

    public fun velocity(from: Vec3, to: Vec3, arcHeight: Double): Vec3 {
        val dy = to.y - from.y
        val apex = max(arcHeight, 0.0) + max(dy, 0.0)
        val vy = sqrt(2.0 * GRAVITY * max(apex, MIN_APEX))
        // y(t) = vy*t - g*t^2/2 = dy, later root.
        val flightTicks = (vy + sqrt(max(vy * vy - 2.0 * GRAVITY * dy, 0.0))) / GRAVITY
        return Vec3((to.x - from.x) / flightTicks, vy, (to.z - from.z) / flightTicks)
    }
}

private abstract class MovementExecutor<O : MovementOp>(
    override val opClass: KClass<O>,
    protected val host: MovementHost,
    protected val movement: ISkillMovementGateway,
) : OpExecutor<O> {
    protected fun living(ctx: OpContext, target: TargetRef): List<UUID> =
        host.targets(ctx, target).filter(movement::isAlive)
}

/** Executors of the movement family; the platform also ticks [DashStrikeDriver] once per server tick. */
public fun movementExecutors(host: MovementHost, movement: ISkillMovementGateway, dashes: DashStrikeDriver, locks: MovementLockDriver): List<OpExecutor<*>> = listOf(
    object : MovementExecutor<MovementOp.Impulse>(MovementOp.Impulse::class, host, movement) {
        override fun execute(op: MovementOp.Impulse, ctx: OpContext) {
            val v = ctx.values.vec3(op.vector)
            for (t in living(ctx, op.target)) ctx.ports.gameplay.impulse(t, v)
        }
    },
    object : MovementExecutor<MovementOp.Knockback>(MovementOp.Knockback::class, host, movement) {
        override fun execute(op: MovementOp.Knockback, ctx: OpContext) {
            val v = ctx.values.vec3(op.direction) * ctx.values.num(op.strength)
            for (t in living(ctx, op.target)) ctx.ports.gameplay.impulse(t, v)
        }
    },
    object : MovementExecutor<MovementOp.Knockup>(MovementOp.Knockup::class, host, movement) {
        override fun execute(op: MovementOp.Knockup, ctx: OpContext) {
            val v = Vec3(0.0, ctx.values.num(op.strength), 0.0)
            for (t in living(ctx, op.target)) ctx.ports.gameplay.impulse(t, v)
        }
    },
    object : MovementExecutor<MovementOp.SetVelocity>(MovementOp.SetVelocity::class, host, movement) {
        override fun execute(op: MovementOp.SetVelocity, ctx: OpContext) {
            val v = ctx.values.vec3(op.direction) * ctx.values.num(op.magnitude)
            for (t in living(ctx, op.target)) movement.setVelocity(t, v)
        }
    },
    object : MovementExecutor<MovementOp.Propel>(MovementOp.Propel::class, host, movement) {
        override fun execute(op: MovementOp.Propel, ctx: OpContext) {
            val v = ctx.values.vec3(op.direction) * ctx.values.num(op.strength)
            for (t in living(ctx, op.target)) movement.addVelocity(t, v)
        }
    },
    object : MovementExecutor<MovementOp.Teleport>(MovementOp.Teleport::class, host, movement) {
        override fun execute(op: MovementOp.Teleport, ctx: OpContext) {
            val to = host.location(ctx, op.destination) ?: return
            // v1 Teleport (MechanicDispatcher.kt:741-748) moves dead or alive targets; a firing bound to a victim / area
            // target stops once that target is dead, gone or in another world (fireChecked, MD:502-511).
            val bound = op.target != TargetRef.Caster
            for (t in host.targets(ctx, op.target)) {
                if (bound && !(movement.isAlive(t) && movement.isInWorld(t, ctx.world))) continue
                movement.teleport(t, to, null)
            }
        }
    },
    object : MovementExecutor<MovementOp.Leap>(MovementOp.Leap::class, host, movement) {
        override fun execute(op: MovementOp.Leap, ctx: OpContext) {
            val to = host.location(ctx, op.destination) ?: return
            val arc = ctx.values.num(op.arcHeight)
            for (t in living(ctx, op.target)) {
                val from = ctx.ports.entities.rootOf(t)?.feet ?: continue
                movement.setVelocity(t, LeapArc.velocity(from, to, arc))
            }
        }
    },
    object : MovementExecutor<MovementOp.DashStrike>(MovementOp.DashStrike::class, host, movement) {
        override fun execute(op: MovementOp.DashStrike, ctx: OpContext) {
            dashes.start(op, ctx)
        }
    },
    object : MovementExecutor<MovementOp.Lock>(MovementOp.Lock::class, host, movement) {
        override fun execute(op: MovementOp.Lock, ctx: OpContext) {
            val speed = ctx.values.num(op.speed).takeIf { it.isFinite() } ?: 0.0
            val ticks = op.ticks?.let { ctx.values.num(it).toInt() }
            for (t in living(ctx, op.target)) locks.lock(ctx.cast, t, speed, ticks)
        }
    },
    object : MovementExecutor<MovementOp.Unlock>(MovementOp.Unlock::class, host, movement) {
        override fun execute(op: MovementOp.Unlock, ctx: OpContext) {
            for (t in host.targets(ctx, op.target)) locks.unlock(ctx.cast, t)
        }
    },
)
