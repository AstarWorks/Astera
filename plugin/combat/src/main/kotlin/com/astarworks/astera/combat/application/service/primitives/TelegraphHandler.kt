package com.astarworks.astera.combat.application.service.primitives

import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.combat.application.service.vfx.VfxPlayer
import com.astarworks.astera.combat.application.service.vfx.VfxRequest
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IVfxRegistry
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.Audience
import com.astarworks.astera.core.domain.effect.vfx.VfxDefinition
import com.astarworks.astera.core.domain.effect.vfx.VfxId
import com.astarworks.astera.core.domain.effect.vfx.telegraph.TelegraphPlanner
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * `telegraph` (ADR-0288): a ground warning at each location of `targets`. [TelegraphPlanner] builds the effect (outer rim at
 * full size on tick 0, inner fill growing to full by `impact_tick`); it plays through the ordinary [VfxPlayer] path with the
 * mechanic's `viewers` audience applied to every display it spawns.
 *
 * Each telegraph is a generated one-shot definition, so it gets its own small [VfxPlayer] whose gateway injects the audience,
 * and both are dropped a few ticks after the effect ends.
 */
internal class TelegraphHandler(private val deps: PrimitiveDeps) : PrimitiveHandler<Mechanic.Telegraph> {

    private val sequence = AtomicLong()

    override fun handle(mechanic: Mechanic.Telegraph, ctx: MechanicContext) {
        val displays = deps.displays ?: return deps.log.debug("telegraph dropped: no display gateway")
        val world = ctx.worldId ?: return deps.log.debug("telegraph dropped: ctx.worldId is null")
        val ticks = deps.host.resolveInt(mechanic.ticks, ctx)
        val impact = mechanic.impactTick?.let { deps.host.resolveInt(it, ctx) } ?: ticks
        val radius = deps.host.resolveDouble(mechanic.radius, ctx)
        val color = TelegraphPlanner.colorFor(mechanic.teamColor, deps.teamColors()?.colorOf(ctx.actorId))
        val look = ctx.pose?.direction?.takeIf { it.lengthSquared() > 1e-9 }
        val anchors = deps.targeters.resolveLocations(mechanic.targets, ctx).take(MAX_ANCHORS)
        for (at in anchors) {
            val id = sequence.incrementAndGet()
            val definition = try {
                TelegraphPlanner.plan(
                    TelegraphPlanner.Request(
                        shape = mechanic.shape,
                        radius = radius,
                        angleDegrees = deps.host.resolveDouble(mechanic.angleDegrees, ctx),
                        width = deps.host.resolveDouble(mechanic.width, ctx),
                        ticks = ticks,
                        impactTick = impact,
                        colorRgb = color,
                        idSuffix = id.toString(),
                    ),
                )
            } catch (e: IllegalArgumentException) {
                return deps.log.warn("telegraph dropped: {}", e.message)
            }
            play(definition, at, look, world, ctx, mechanic.viewers, displays)
        }
    }

    private fun play(
        definition: VfxDefinition,
        at: Vec3,
        look: Vec3?,
        world: com.astarworks.astera.core.domain.world.WorldId,
        ctx: MechanicContext,
        viewers: Audience,
        displays: IDisplayEntityGateway,
    ) {
        val registry = OneShotRegistry(definition)
        val player = VfxPlayer(
            scheduler = deps.scheduler,
            displays = displays,
            registry = registry,
            emitParticle = {},
            log = deps.log,
        )
        val result = player.play(
            VfxRequest(
                vfxId = definition.id,
                world = world,
                origin = at,
                look = look,
                toTarget = look,
                caster = ctx.actorId,
                viewers = viewers,
                targetPlayer = ctx.targetId,
                seed = definition.id.hashCode().toLong(),
            ),
        )
        if (result is Result.Failure) {
            deps.log.debug("telegraph {} not played: {}", definition.id, result.error)
            player.shutdown()
            return
        }
        deps.scheduler.run(ExecutionScope.Entity(ctx.actorId.uuid), ticks((definition.durationTicks + CLEANUP_GRACE).toLong())) {
            player.shutdown()
        }
    }

    private class OneShotRegistry(private val definition: VfxDefinition) : IVfxRegistry {
        override fun find(key: VfxId): VfxDefinition? = definition.takeIf { it.id == key }
        override fun all(): Collection<VfxDefinition> = listOf(definition)
    }

    private companion object {
        const val MAX_ANCHORS = 4
        const val CLEANUP_GRACE = 4
    }
}
