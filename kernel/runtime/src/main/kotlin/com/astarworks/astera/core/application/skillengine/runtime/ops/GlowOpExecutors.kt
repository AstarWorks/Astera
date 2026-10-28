package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.presentation.PresentationCastInfo
import com.astarworks.astera.core.domain.skillengine.op.BodyOp
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/**
 * Outline glow of an entity (vanilla `Entity.setGlowing`). No default implementation: Paper and the sim each bind it,
 * and a missing binding is a wiring error, not a silent no-op.
 */
public interface IEntityGlowGateway {
    /** True for a player that is online and not dead. `glow` never touches anything else (v1 `living(...)` filter). */
    public fun isLivingPlayer(entity: UUID): Boolean

    public fun setGlowing(entity: UUID, glowing: Boolean)
}

/**
 * The end times of running glows, across casts (v1 `WorldMechanics.glowUntil`, WM:119/585). A glow outlives its cast.
 * The runner calls [tick] at the start of every server tick, before the casts run, so a glow of `d` ticks started at
 * tick `T` switches off at the start of tick `T + d`, like v1's `runLaterTicks(d)`.
 */
public class EntityGlowDriver(private val gateway: IEntityGlowGateway) {
    private val until = HashMap<UUID, Int>()
    private var now = 0

    /** Glows each living player of [targets] for [ticks]; a running glow's end time is overwritten, never extended by max. */
    @Synchronized
    public fun glow(targets: List<UUID>, ticks: Int) {
        if (ticks <= 0) return
        for (target in targets) {
            if (!gateway.isLivingPlayer(target)) continue
            gateway.setGlowing(target, true)
            until[target] = now + ticks
        }
    }

    @Synchronized
    public fun tick(currentTick: Int) {
        now = currentTick
        val ended = until.entries.filter { it.value <= now }.map { it.key }
        for (entity in ended) {
            until.remove(entity)
            gateway.setGlowing(entity, false)
        }
    }

    /** Disable / match end: switches every running glow off (v1 `shutdown`). */
    @Synchronized
    public fun shutdown() {
        until.keys.toList().forEach { gateway.setGlowing(it, false) }
        until.clear()
    }
}

/** Executor of `glow` (body family); targets resolve like every presentation op. */
public fun glowExecutors(info: PresentationCastInfo, driver: EntityGlowDriver): List<OpExecutor<*>> = listOf(GlowExecutor(info, driver))

private class GlowExecutor(private val info: PresentationCastInfo, private val driver: EntityGlowDriver) : OpExecutor<BodyOp.Glow> {
    override val opClass: KClass<BodyOp.Glow> = BodyOp.Glow::class
    override fun execute(op: BodyOp.Glow, ctx: OpContext) {
        if (op.whenExpr?.let { ctx.values.bool(it) } == false) return
        driver.glow(info.targets(ctx.cast, op.target), ctx.values.num(op.ticks).roundToInt())
    }
}
