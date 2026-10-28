package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.skillengine.runtime.ops.MovementHost
import com.astarworks.astera.core.application.skillengine.runtime.ops.OpTargetResolver
import com.astarworks.astera.core.application.skillengine.runtime.presentation.PresentationCastInfo
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The executors that are built once (movement, status, presentation) need a host. Every host role is implemented by
 * the running [CastInstance]; this router hands each call to the instance of the cast it names, so there is exactly
 * one cast context and no second implementation of the host interfaces.
 */
public class CastHosts : MovementHost, OpTargetResolver, PresentationCastInfo {
    private val live = ConcurrentHashMap<CastId, CastInstance>()

    internal fun register(cast: CastInstance) {
        live[cast.id] = cast
    }

    internal fun unregister(id: CastId) {
        live.remove(id)
    }

    private fun of(ctx: OpContext): CastInstance = of(ctx.cast)

    private fun of(id: CastId): CastInstance = live[id] ?: error("cast ${id.value} is not running")

    override fun caster(ctx: OpContext): UUID = of(ctx).caster(ctx)

    override fun targets(ctx: OpContext, target: TargetRef): List<UUID> = of(ctx).targets(ctx, target)

    override fun location(ctx: OpContext, spec: LocationSpec): Vec3? = of(ctx).location(ctx, spec)

    override fun runWithVictim(ctx: OpContext, source: String, victim: UUID, point: Vec3, ops: List<SkillOp>) =
        of(ctx).runWithVictim(ctx, source, victim, point, ops)

    override fun resolve(ctx: OpContext, target: TargetRef): List<UUID> = of(ctx).resolve(ctx, target)

    override fun owner(cast: CastId): UUID = of(cast).owner(cast)

    override fun targets(cast: CastId, target: TargetRef): List<UUID> = of(cast).targets(cast, target)

    override fun hasRaised(cast: CastId, event: String): Boolean = live[cast]?.hasRaised(cast, event) ?: false
}
