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
    val rolls = ChanceRoller()
    return listOf(
        StatusExecutor(StatusOp.ApplyStatus::class, targets) { op, ctx, victim ->
            if (rolls.passes(ctx, op.id, victim, op.chance?.let(ctx.values::num))) {
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

/**
 * Deterministic chance roll: a hash of (cast, op id, victim, nth roll of that triple). Same
 * inputs in the same order give the same outcome, so sim replays match; repeated ticks differ.
 */
private class ChanceRoller {
    private val counts = HashMap<Triple<Long, String, UUID>, Int>()

    fun passes(ctx: OpContext, opId: String, victim: UUID, chance: Double?): Boolean {
        if (chance == null || chance >= 1.0) return true
        if (chance <= 0.0) return false
        val key = Triple(ctx.cast.value, opId, victim)
        val n = counts.merge(key, 1, Int::plus)!!
        var h = ctx.cast.value * -7046029254386353131L
        h = (h xor opId.hashCode().toLong()) * -7046029254386353131L
        h = (h xor victim.mostSignificantBits xor victim.leastSignificantBits) * -7046029254386353131L
        h = (h xor n.toLong()) * -7046029254386353131L
        h = h xor (h ushr 32)
        val unit = (h ushr 11).toDouble() / (1L shl 53).toDouble()
        return unit < chance
    }
}
