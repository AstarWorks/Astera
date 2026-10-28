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
        val cast = live.remove(id) ?: return
        // Delayed world strikes (break_area ripple) outlive the cast that scheduled them and still need its owner.
        synchronized(finished) { finished[id] = Ended(cast.owner(id), cast.itemOf()) }
    }

    /** Owners of the most recently ended casts, bounded; a ripple lasts under 30 ticks so a few thousand casts is ample. */
    private class Ended(val owner: UUID, val item: String?)

    private val finished = object : LinkedHashMap<CastId, Ended>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CastId, Ended>): Boolean = size > FINISHED_OWNERS_KEPT
    }

    /** The item id the cast's skill is bound to (`item:` in its yaml), also for an ended cast; null for item-less skills. */
    public fun itemOf(cast: CastId): String? =
        live[cast]?.itemOf() ?: synchronized(finished) { finished[cast]?.item }

    private fun of(ctx: OpContext): CastInstance = of(ctx.cast)

    private fun of(id: CastId): CastInstance = live[id] ?: error("cast ${id.value} is not running")

    override fun caster(ctx: OpContext): UUID = of(ctx).caster(ctx)

    override fun targets(ctx: OpContext, target: TargetRef): List<UUID> = of(ctx).targets(ctx, target)

    override fun location(ctx: OpContext, spec: LocationSpec): Vec3? = of(ctx).location(ctx, spec)

    override fun runWithVictim(ctx: OpContext, victim: UUID, point: Vec3, ops: List<SkillOp>) =
        of(ctx).runWithVictim(ctx, victim, point, ops)

    override fun resolve(ctx: OpContext, target: TargetRef): List<UUID> = of(ctx).resolve(ctx, target)

    override fun owner(cast: CastId): UUID =
        live[cast]?.owner(cast) ?: synchronized(finished) { finished[cast]?.owner } ?: error("cast ${cast.value} is not running")

    override fun targets(cast: CastId, target: TargetRef): List<UUID> = of(cast).targets(cast, target)

    override fun hasRaised(cast: CastId, event: String): Boolean = live[cast]?.hasRaised(cast, event) ?: false

    private companion object {
        const val FINISHED_OWNERS_KEPT = 4096
    }
}
