package com.astarworks.astera.combat.application.service.vfx

import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.combat.application.service.TargeterResolver
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.vfx.VfxFreeze
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import org.slf4j.Logger

/**
 * `Mechanic.PlayVfx` glue between the dispatcher and [VfxPlayer]: resolves the anchors and
 * orientation vectors. Entity targets anchor on the entity's feet (no eye height; use the
 * definition's offsets) and may be followed; location targets are fixed.
 */
internal class VfxMechanics(
    private val player: VfxPlayer,
    private val targeters: TargeterResolver,
    private val poseLookup: IPlayerPoseLookup?,
    private val scheduler: IScheduler,
    private val isLiving: (com.astarworks.astera.core.domain.player.PlayerId) -> Boolean,
    private val log: Logger,
) {

    fun shutdown() = player.shutdown()

    fun play(m: Mechanic.PlayVfx, ctx: MechanicContext, scale: Double, follow: Boolean?, freeze: List<VfxFreeze> = emptyList()) {
        val world = ctx.worldId ?: return log.debug("PlayVfx {} dropped: ctx.worldId is null", m.vfxId)
        val trackedTarget = if (m.trackBoundTarget) {
            val id = ctx.targetId ?: return
            if (ctx.boundTargetWorld != world) return
            id
        } else null
        val casterPosition = ctx.pose?.position ?: ctx.origin
        val look = ctx.pose?.direction?.takeIf { it.lengthSquared() > 1e-9 }

        val entities = targeters.resolvePlayers(m.targets, ctx).take(MAX_ANCHORS)
        val anchors: List<Pair<Vec3, (() -> Vec3?)?>> = if (entities.isNotEmpty()) {
            entities.map { id ->
                val position = poseLookup?.poseOf(id)?.position ?: ctx.origin
                position to { poseLookup?.poseOf(id)?.position }
            }
        } else {
            targeters.resolveLocations(m.targets, ctx).take(MAX_ANCHORS).map { it to null }
        }

        anchors.forEachIndexed { index, (position, live) ->
            val request = VfxRequest(
                vfxId = m.vfxId,
                world = world,
                origin = position,
                look = look,
                toTarget = (position - casterPosition).takeIf { it.lengthSquared() > 1e-9 },
                scale = scale,
                follow = follow,
                caster = ctx.actorId,
                freeze = freeze,
                anchor = live,
                anchorEntityId = entities.getOrNull(index),
                target = trackedTarget?.let { id -> {
                    val position = if (ctx.boundTargetState?.cancelled != true && isLiving(id))
                        poseLookup?.poseOf(id)?.takeIf { it.world == world }?.position else null
                    if (position == null) ctx.boundTargetState?.cancelled = true
                    position
                } },
                seed = scheduler.currentTick() * 1_000_003L + ctx.actorId.hashCode() * 31L + index,
            )
            when (val result = player.play(request)) {
                is Result.Success -> Unit
                is Result.Failure -> log.debug("PlayVfx {} not played: {}", m.vfxId, result.error)
            }
        }
    }

    private companion object {
        const val MAX_ANCHORS = 8
    }
}
