package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutorRegistry
import com.astarworks.astera.core.domain.skillengine.op.ControlOp
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.reflect.KClass

private fun OpContext.ticks(value: com.astarworks.astera.core.domain.skillengine.model.Value<Double>): Int =
    values.num(value).roundToInt().coerceAtLeast(0)

/** Hit-stop: freeze the clock of the listed frame subtrees and/or entities: `caster`, and `enemy` = the struck entity of the hit handler (movement and AI held, like v1 `hit_stop`). */
public class FreezeOpExecutor : OpExecutor<ControlOp.Freeze> {
    override val opClass: KClass<ControlOp.Freeze> = ControlOp.Freeze::class

    override fun execute(op: ControlOp.Freeze, ctx: OpContext) {
        val ticks = ctx.ticks(op.ticks)
        if (ticks == 0) return
        val entities = LinkedHashSet<UUID>()
        val frames = ArrayList<String>()
        for (name in op.scope) {
            when (name) {
                "caster" -> entities += ctx.host.caster
                "enemy" -> ctx.host.currentVictim?.let { entities += it }
                else -> frames += name
            }
        }
        if (entities.isNotEmpty()) ctx.ports.gameplay.freezeEntities(entities.toList(), ticks)
        if (frames.isNotEmpty()) ctx.host.freezeFrames(frames, ticks)
    }
}

/** Raise [ControlOp.Schedule.event] after a delay; `event.source` is this op's id. */
public class ScheduleOpExecutor : OpExecutor<ControlOp.Schedule> {
    override val opClass: KClass<ControlOp.Schedule> = ControlOp.Schedule::class

    override fun execute(op: ControlOp.Schedule, ctx: OpContext) {
        ctx.host.schedule(ctx.ticks(op.afterTicks), op.event, op.id)
    }
}

public class RaiseEventOpExecutor : OpExecutor<ControlOp.RaiseEvent> {
    override val opClass: KClass<ControlOp.RaiseEvent> = ControlOp.RaiseEvent::class

    override fun execute(op: ControlOp.RaiseEvent, ctx: OpContext) {
        ctx.raise(op.event)
    }
}

public class CastSkillOpExecutor : OpExecutor<ControlOp.CastSkill> {
    override val opClass: KClass<ControlOp.CastSkill> = ControlOp.CastSkill::class

    override fun execute(op: ControlOp.CastSkill, ctx: OpContext) {
        ctx.host.castSkill(op.skill)
    }
}

public class IfOpExecutor : OpExecutor<ControlOp.If> {
    override val opClass: KClass<ControlOp.If> = ControlOp.If::class

    override fun execute(op: ControlOp.If, ctx: OpContext) {
        ctx.run(if (ctx.values.bool(op.condition)) op.then else op.otherwise)
    }
}

/**
 * Iteration 0 runs now; iteration i runs `i * everyTicks` ticks later (everyTicks 0 = all now),
 * in the scope (victim binding) the loop was started in.
 */
public class LoopOpExecutor : OpExecutor<ControlOp.Loop> {
    override val opClass: KClass<ControlOp.Loop> = ControlOp.Loop::class

    override fun execute(op: ControlOp.Loop, ctx: OpContext) {
        val count = ctx.ticks(op.count)
        for (i in 0 until count) {
            if (op.everyTicks > 0 && i > 0) ctx.host.scheduleOps(i * op.everyTicks, op.body) else ctx.run(op.body)
        }
    }
}

/** Runs the body once, `after` ticks later, in the victim binding the op ran in (the contact itself is not kept). */
public class DelayOpExecutor : OpExecutor<ControlOp.Delay> {
    override val opClass: KClass<ControlOp.Delay> = ControlOp.Delay::class

    override fun execute(op: ControlOp.Delay, ctx: OpContext) {
        val after = ctx.ticks(op.afterTicks)
        if (after > 0) ctx.host.scheduleOps(after, op.body) else ctx.run(op.body)
    }
}

/** Opens an event window on the caster; the cast keeps running while it is open. */
public class WindowOpExecutor : OpExecutor<ControlOp.Window> {
    override val opClass: KClass<ControlOp.Window> = ControlOp.Window::class

    override fun execute(op: ControlOp.Window, ctx: OpContext) {
        val ticks = ctx.ticks(op.ticks)
        if (ticks > 0) ctx.host.openWindow(op, ticks)
    }
}

/** First case whose guard holds; otherwise the `else` list. */
public class ChooseOpExecutor : OpExecutor<ControlOp.Choose> {
    override val opClass: KClass<ControlOp.Choose> = ControlOp.Choose::class

    override fun execute(op: ControlOp.Choose, ctx: OpContext) {
        val hit = op.cases.firstOrNull { (guard, _) -> ctx.values.bool(guard) }
        ctx.run(hit?.second ?: op.otherwise)
    }
}

/** One branch, picked by weight with the cast's deterministic random. */
public class WeightedBranchOpExecutor : OpExecutor<ControlOp.WeightedBranch> {
    override val opClass: KClass<ControlOp.WeightedBranch> = ControlOp.WeightedBranch::class

    override fun execute(op: ControlOp.WeightedBranch, ctx: OpContext) {
        val weights = op.branches.map { (w, _) -> ctx.values.num(w).coerceAtLeast(0.0) }
        val total = weights.sum()
        if (total <= 0.0) return
        val pick = ctx.host.nextRandom() * total
        var acc = 0.0
        for ((i, w) in weights.withIndex()) {
            acc += w
            if (pick < acc) {
                ctx.run(op.branches[i].second)
                return
            }
        }
    }
}

/** Run the body once per target, with that target bound as the current victim. */
public class ForEachOpExecutor : OpExecutor<ControlOp.ForEach> {
    override val opClass: KClass<ControlOp.ForEach> = ControlOp.ForEach::class

    override fun execute(op: ControlOp.ForEach, ctx: OpContext) {
        for (target in ctx.host.resolveTargets(op.targets).distinct()) {
            ctx.host.withVictim(target) { ctx.run(op.body) }
        }
    }
}

public class CounterOpExecutor : OpExecutor<ControlOp.Counter> {
    override val opClass: KClass<ControlOp.Counter> = ControlOp.Counter::class

    override fun execute(op: ControlOp.Counter, ctx: OpContext) {
        ctx.host.addCounter(op.key, ctx.values.num(op.delta))
    }
}

/** Cooldown of [ControlOp.SetCooldown.skill] on the caster; 0 ticks resets it. */
public class SetCooldownOpExecutor : OpExecutor<ControlOp.SetCooldown> {
    override val opClass: KClass<ControlOp.SetCooldown> = ControlOp.SetCooldown::class

    override fun execute(op: ControlOp.SetCooldown, ctx: OpContext) {
        ctx.host.cooldowns.set(ctx.host.caster, op.skill, ctx.ticks(op.ticks))
    }
}

public class GiveItemOpExecutor : OpExecutor<ControlOp.GiveItem> {
    override val opClass: KClass<ControlOp.GiveItem> = ControlOp.GiveItem::class

    override fun execute(op: ControlOp.GiveItem, ctx: OpContext) {
        val count = ctx.ticks(op.count)
        if (count == 0) return
        for (target in ctx.host.resolveTargets(op.target)) ctx.host.items.give(target, op.item, count)
    }
}

public class ConsumeItemOpExecutor : OpExecutor<ControlOp.ConsumeItem> {
    override val opClass: KClass<ControlOp.ConsumeItem> = ControlOp.ConsumeItem::class

    override fun execute(op: ControlOp.ConsumeItem, ctx: OpContext) {
        val count = ctx.ticks(op.count)
        if (count == 0) return
        for (target in ctx.host.resolveTargets(op.target)) ctx.host.items.consume(target, op.item, count)
    }
}

public fun registerControlOpExecutors(registry: OpExecutorRegistry) {
    registry.register(FreezeOpExecutor())
    registry.register(ScheduleOpExecutor())
    registry.register(RaiseEventOpExecutor())
    registry.register(CastSkillOpExecutor())
    registry.register(IfOpExecutor())
    registry.register(LoopOpExecutor())
    registry.register(DelayOpExecutor())
    registry.register(WindowOpExecutor())
    registry.register(ChooseOpExecutor())
    registry.register(WeightedBranchOpExecutor())
    registry.register(ForEachOpExecutor())
    registry.register(CounterOpExecutor())
    registry.register(SetCooldownOpExecutor())
    registry.register(GiveItemOpExecutor())
    registry.register(ConsumeItemOpExecutor())
}
