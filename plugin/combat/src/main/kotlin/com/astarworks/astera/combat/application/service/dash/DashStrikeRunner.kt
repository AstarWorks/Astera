package com.astarworks.astera.combat.application.service.dash

import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.combat.application.service.MechanicStatuses
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.world.WorldId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.math.floor

/**
 * Runtime of `Mechanic.DashStrike`: moves the caster along its look direction with one teleport
 * per tick, sweeps a capsule of [Params.radius] along each step and runs [Params.onHit] on every
 * entity it touches. Ports are optional where the dash can degrade (no immunity status when the
 * status ports are missing, no block check when the world reports nothing).
 *
 * The first step happens in the cast tick, the remaining `ticks - 1` steps on the following ticks.
 */
internal class DashStrikeRunner(
    private val scheduler: IScheduler,
    private val worldGateway: IWorldGateway,
    private val poseLookup: IPlayerPoseLookup?,
    private val nearbyQuery: INearbyTargetQuery,
    private val effects: IEntityEffectsGateway?,
    private val applyStatus: ApplyStatusUseCase?,
    private val statusContainer: IStatusEffectContainer?,
    private val statusRegistry: IStatusEffectRegistry?,
    /** Drops dead entities (the dispatcher's `living`). */
    private val living: (List<PlayerId>) -> List<PlayerId>,
    /** Runs the `on_hit` mechanics (the dispatcher's `dispatch`). */
    private val runOnHit: (List<Mechanic>, MechanicContext) -> Unit,
    private val log: Logger = LoggerFactory.getLogger(DashStrikeRunner::class.java),
) {
    /** Resolved DashStrike parameters. */
    data class Params(
        val distance: Double,
        val ticks: Int,
        val radius: Double,
        val hitOnce: Boolean,
        val invulnerable: Boolean,
        val stopOnBlock: Boolean,
        val followPitch: Boolean,
        val onHit: List<Mechanic>,
    )

    private val active: MutableSet<Dash> = LinkedHashSet()

    /** Starts a dash for [caster]; returns false when it cannot start (unknown pose / distance). */
    fun start(caster: PlayerId, params: Params, ctx: MechanicContext): Boolean {
        val pose = poseLookup?.poseOf(caster) ?: ctx.pose
        if (pose == null) {
            log.warn("DashStrike dropped: no pose for caster {}", caster)
            return false
        }
        if (params.distance <= 0.0) return false
        val steps = params.ticks.coerceAtLeast(1)
        val dash = Dash(
            caster = caster,
            params = params,
            ctx = ctx,
            world = pose.world,
            origin = pose.position,
            stepVector = heading(pose.direction, params.followPitch) * (params.distance / steps),
            steps = steps,
            pose = pose,
        )
        active += dash
        dash.immunityApplied = params.invulnerable && grantImmunity(caster, steps)
        stepOnce(dash)
        if (!dash.finished) {
            dash.handle = scheduler.runRepeating(initialDelay = ticks(1), period = ticks(1)) { stepOnce(dash) }
        }
        return true
    }

    /** Ends every running dash (plugin disable). */
    fun shutdown() {
        active.toList().forEach { finish(it) }
    }

    private fun stepOnce(dash: Dash) {
        if (dash.finished) return
        if (living(listOf(dash.caster)).isEmpty()) {
            finish(dash)
            return
        }
        dash.stepIndex++
        val from = dash.position
        val to = dash.origin + dash.stepVector * dash.stepIndex.toDouble()
        if (dash.params.stopOnBlock && blocked(dash.world, to)) {
            finish(dash)
            return
        }
        if (!worldGateway.teleport(dash.caster, to, dash.world)) {
            finish(dash)
            return
        }
        dash.position = to
        sweep(dash, from, to)
        if (dash.stepIndex >= dash.steps) finish(dash)
    }

    private fun sweep(dash: Dash, from: Vec3, to: Vec3) {
        val params = dash.params
        if (params.onHit.isEmpty()) return
        val bodyOffset = Vec3(0.0, BODY_HALF_HEIGHT, 0.0)
        val mid = (from + to) * 0.5 + bodyOffset
        val reach = params.radius + from.distance(to) / 2.0 + BODY_HALF_HEIGHT
        val here = dash.pose.copy(
            position = to,
            eyePosition = to + Vec3(0.0, PlayerPose.STANDING_EYE_HEIGHT, 0.0),
        )
        for (candidate in nearbyQuery.findNearby(mid, reach, dash.world)) {
            if (candidate.id == dash.caster) continue
            if (params.hitOnce && candidate.id in dash.hit) continue
            val feet = candidate.position ?: poseLookup?.poseOf(candidate.id)?.position ?: continue
            val body = feet + Vec3(0.0, candidate.height / 2.0, 0.0)
            if (distanceToSegment(body, from + bodyOffset, to + bodyOffset) > params.radius) continue
            if (living(listOf(candidate.id)).isEmpty()) continue
            dash.hit += candidate.id
            runOnHit(
                params.onHit,
                dash.ctx.copy(targetId = candidate.id, origin = to, pose = here, hitLocation = feet),
            )
        }
    }

    private fun finish(dash: Dash) {
        if (dash.finished) return
        dash.finished = true
        dash.handle?.cancel()
        active -= dash
        effects?.setVelocity(dash.caster, Vec3.ZERO)
        if (dash.immunityApplied) statusContainer?.remove(dash.caster, MechanicStatuses.IMMUNITY_ID)
    }

    /**
     * Grants the immunity status for the dash; returns true when this call owns it (and must
     * remove it again). A caster that already had immunity keeps it untouched.
     */
    private fun grantImmunity(caster: PlayerId, steps: Int): Boolean {
        val status = applyStatus ?: return false
        val container = statusContainer ?: return false
        if (container.has(caster, MechanicStatuses.IMMUNITY_ID)) return false
        val spec = statusRegistry?.find(MechanicStatuses.IMMUNITY_ID) ?: MechanicStatuses.IMMUNITY
        // The container's own countdown is coarse (1 s); `finish` removes it exactly, this is only a safety net.
        status.applySpec(caster, spec, steps + SAFETY_TICKS, caster)
        return true
    }

    private fun blocked(world: WorldId, at: Vec3): Boolean =
        BLOCK_PROBE_HEIGHTS.any { dy ->
            val block = worldGateway.blockAt(world, Vec3(floor(at.x), floor(at.y + dy), floor(at.z)))
            block != null && !isPassable(block.identifier)
        }

    private fun isPassable(identifier: String): Boolean =
        identifier.endsWith("air") || identifier in PASSABLE

    private fun heading(direction: Vec3, followPitch: Boolean): Vec3 {
        val level = Vec3(direction.x, 0.0, direction.z)
        val chosen = if (followPitch || level.lengthSquared() < 1.0e-9) direction else level
        return chosen.normalize().takeIf { it.lengthSquared() > 0.0 } ?: Vec3(0.0, 0.0, 1.0)
    }

    private fun distanceToSegment(point: Vec3, a: Vec3, b: Vec3): Double {
        val ab = b - a
        val len2 = ab.lengthSquared()
        if (len2 < 1.0e-12) return point.distance(a)
        val t = ((point - a).dot(ab) / len2).coerceIn(0.0, 1.0)
        return point.distance(a + ab * t)
    }

    private class Dash(
        val caster: PlayerId,
        val params: Params,
        val ctx: MechanicContext,
        val world: WorldId,
        val origin: Vec3,
        val stepVector: Vec3,
        val steps: Int,
        val pose: PlayerPose,
    ) {
        var position: Vec3 = origin
        var stepIndex: Int = 0
        var finished: Boolean = false
        var immunityApplied: Boolean = false
        var handle: IScheduler.TaskHandle? = null
        val hit: MutableSet<PlayerId> = HashSet()
    }

    private companion object {
        const val BODY_HALF_HEIGHT: Double = 0.9
        const val SAFETY_TICKS: Int = 40

        /** Feet-relative heights probed for a solid block (knee and head). */
        val BLOCK_PROBE_HEIGHTS: List<Double> = listOf(0.5, 1.5)

        /** Non-air blocks a dash passes through. */
        val PASSABLE: Set<String> = setOf(
            "water", "lava", "light", "short_grass", "tall_grass", "fern", "large_fern", "vine",
            "snow", "torch", "wall_torch", "rail", "ladder",
        )
    }
}
