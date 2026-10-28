package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.application.skillengine.runtime.HitRegistration
import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutorRegistry
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.op.HitOp
import kotlin.reflect.KClass

/**
 * Registers one hit-volume instance per listed frame with the runner's hit registry; the runner
 * resolves it every tick (broad + narrow) and raises `hit` / `block` events whose `event.source`
 * is the op id.
 */
public class HitOpExecutor : OpExecutor<HitOp.Hit> {
    override val opClass: KClass<HitOp.Hit> = HitOp.Hit::class

    override fun execute(op: HitOp.Hit, ctx: OpContext) {
        for (frame in op.frames) {
            if (ctx.tree.node(FrameId(frame)) == null) {
                ctx.host.fail(op.id, "hit on unknown frame '$frame'")
                continue
            }
            ctx.host.registerHit(HitRegistration(op.id, frame, op.volume, op.window, ctx.host.tick, op.maxCount, op.swept, op.ticks))
        }
    }
}

public fun registerHitOpExecutors(registry: OpExecutorRegistry) {
    registry.register(HitOpExecutor())
}
