package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.op.StatusOp
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/**
 * Resolves a [TargetRef] to entities for the current handler scope (victim of the current contact,
 * the caster, or an area query). The cast runner implements it; the manager passes it to
 * [statusExecutors], because [OpContext] carries no contact.
 */
public fun interface OpTargetResolver {
    public fun resolve(ctx: OpContext, target: TargetRef): List<UUID>
}

/** Executors of the status / buff family. All route through `ISkillGameplayGateway`. */
public fun statusExecutors(targets: OpTargetResolver, casterOf: (OpContext) -> UUID): List<OpExecutor<*>> {
    return listOf(
        StatusExecutor(StatusOp.ApplyStatus::class, targets) { op, ctx, victim ->
            if (passes(ctx, op.chance?.let(ctx.values::num))) {
                ctx.ports.gameplay.applyStatus(
                    casterOf(ctx), victim, op.statusId,
                    ctx.values.num(op.stacks).roundToInt().coerceAtLeast(1),
                    ticks(ctx, op.durationTicks),
                )
            }
        },
        StatusExecutor(StatusOp.RemoveStatus::class, targets) { op, ctx, victim ->
            ctx.ports.gameplay.removeStatus(victim, op.statusId)
        },
        StatusExecutor(StatusOp.Cleanse::class, targets) { op, ctx, victim ->
            ctx.ports.gameplay.cleanse(victim, op.negativeOnly)
        },
        StatusExecutor(StatusOp.Ignite::class, targets) { op, ctx, victim ->
            ctx.ports.gameplay.ignite(casterOf(ctx), victim, ticks(ctx, op.durationTicks))
        },
        StatusExecutor(StatusOp.Silence::class, targets) { op, ctx, victim ->
            ctx.ports.gameplay.silence(casterOf(ctx), victim, ticks(ctx, op.durationTicks))
        },
        StatusExecutor(StatusOp.ModifyAttribute::class, targets) { op, ctx, victim ->
            val duration = op.durationTicks?.let { ticks(ctx, it) }
            ctx.ports.gameplay.modifyAttribute(
                casterOf(ctx), victim, op.attribute, op.operation, ctx.values.num(op.amount), duration,
            )
        },
        StatusExecutor(StatusOp.RestoreResource::class, targets) { op, ctx, victim ->
            ctx.ports.gameplay.restoreResource(victim, op.resource, ctx.values.num(op.amount))
        },
    )
}

private fun ticks(ctx: OpContext, value: com.astarworks.astera.core.domain.skillengine.model.Value<Double>): Int =
    ctx.values.num(value).roundToInt().coerceAtLeast(0)

/** One executor: resolves the op's target, then runs [action] for each entity. */
private class StatusExecutor<O : StatusOp>(
    override val opClass: KClass<O>,
    private val targets: OpTargetResolver,
    private val action: (O, OpContext, UUID) -> Unit,
) : OpExecutor<O> {
    override fun execute(op: O, ctx: OpContext) {
        targets.resolve(ctx, op.target()).forEach { action(op, ctx, it) }
    }

    private fun O.target(): TargetRef = when (this) {
        is StatusOp.ApplyStatus -> target
        is StatusOp.RemoveStatus -> target
        is StatusOp.Cleanse -> target
        is StatusOp.Ignite -> target
        is StatusOp.Silence -> target
        is StatusOp.ModifyAttribute -> target
        is StatusOp.RestoreResource -> target
    }
}

/** v1 rolled `chance:` once per target and only below 1 (`MechanicDispatcher.kt:627`), from the shared random source behind the gateway. */
private fun passes(ctx: OpContext, chance: Double?): Boolean {
    val c = (chance ?: return true).coerceIn(0.0, 1.0)
    return c >= 1.0 || ctx.ports.gameplay.rollChance(c)
}
