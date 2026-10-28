package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.op.MovementOp
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Per-tick runtime of [MovementOp.DashStrike] (port of the old DashStrikeRunner semantics): one
 * teleport per tick along a fixed heading, a capsule sweep per step, `hitOnce` per dash, optional
 * immunity for the whole dash, travel clamped `stopBeforeTarget` short of the first body in the
 * corridor, direction given in caster axes (+Z forward, +X left, +Y up).
 *
 * The first step runs in the cast tick (inside [start]); the remaining steps run in [tick], which the
 * platform / cast runner calls once per server tick.
 */
public class DashStrikeDriver(
    private val movement: ISkillMovementGateway,
    private val host: MovementHost,
) {
    private class Dash(
        val cast: CastId,
        val ctx: OpContext,
        val op: MovementOp.DashStrike,
        val caster: UUID,
        val radius: Double,
        val origin: Vec3,
        val stepVector: Vec3,
        val steps: Int,
        val yaw: Double?,
    ) {
        var position: Vec3 = origin
        var stepIndex: Int = 0
        var finished: Boolean = false
        var immunityOwned: Boolean = false
        val hit: MutableSet<UUID> = HashSet()
    }

    private val active: MutableList<Dash> = ArrayList()

    /** Starts a dash for the caster; false when it cannot start (no pose, bad distance/direction, blocked at once). */
    public fun start(op: MovementOp.DashStrike, ctx: OpContext): Boolean {
        val caster = host.caster(ctx)
        val ports = ctx.ports
        val root = ports.entities.rootOf(caster) ?: return false
        val distance = ctx.values.num(op.distance)
        if (!distance.isFinite() || distance < 0.0) return false
        val steps = ctx.values.num(op.ticks).toInt().coerceAtLeast(1)
        val radius = ctx.values.num(op.radius).coerceAtLeast(0.0)
        val forward = heading(lookVector(root.yawDeg, root.pitchDeg), op.followPitch)
        val direction = op.localDirection
            ?.let { Frame.fromDirection(Vec3.ZERO, forward).directionToWorld(it.normalize()).normalize() }
            ?: forward
        if (!finite(direction) || direction.lengthSquared() < 1e-12) return false
        val level = Vec3(direction.x, 0.0, direction.z)
        val yaw = if (op.faceMovement && level.lengthSquared() > 1e-12) yawOf(level.normalize()) else null
        // An explicit zero-length, facing dash is a turn in place: no sweep, no immunity.
        if (distance == 0.0) {
            val facing = yaw ?: return false
            return movement.teleport(caster, root.feet, facing)
        }
        val travel = approachDistance(ports, caster, root.feet, direction, distance, radius, op.stopBeforeTarget)
        if (travel <= 0.0) return false
        val dash = Dash(ctx.cast, ctx, op, caster, radius, root.feet, direction * (travel / steps), steps, yaw)
        active += dash
        if (op.invulnerable) dash.immunityOwned = movement.grantInvulnerable(caster, steps + SAFETY_TICKS)
        stepOnce(dash)
        return true
    }

    /** Advance every running dash one step. */
    public fun tick() {
        for (dash in active.toList()) stepOnce(dash)
    }

    /** Ends the dashes of [cast] (cast cancelled / expired). */
    public fun cancel(cast: CastId) {
        active.filter { it.cast == cast }.forEach(::finish)
    }

    /** Ends every dash (plugin disable). */
    public fun shutdown() {
        active.toList().forEach(::finish)
    }

    public fun isDashing(cast: CastId): Boolean = active.any { it.cast == cast }

    private fun stepOnce(dash: Dash) {
        if (dash.finished) return
        if (!movement.isAlive(dash.caster)) {
            finish(dash)
            return
        }
        dash.stepIndex++
        val from = dash.position
        val to = dash.origin + dash.stepVector * dash.stepIndex.toDouble()
        if (dash.op.stopOnBlock && blockedBetween(dash.ctx.ports, from, to)) {
            finish(dash)
            return
        }
        if (!movement.teleport(dash.caster, to, dash.yaw)) {
            finish(dash)
            return
        }
        dash.position = to
        sweep(dash, from, to)
        if (!dash.finished && dash.stepIndex >= dash.steps) finish(dash)
    }

    private fun sweep(dash: Dash, from: Vec3, to: Vec3) {
        val op = dash.op
        if (op.onHit.isEmpty()) return
        val body = Vec3(0.0, BODY_HALF_HEIGHT, 0.0)
        val mid = (from + to) * 0.5 + body
        // A dash that stops stop_before_target short of its target still strikes it: the gap counts as reach.
        val contactReach = dash.radius + BODY_HALF_WIDTH + (op.stopBeforeTarget?.coerceAtLeast(0.0) ?: 0.0)
        val reach = contactReach + from.distance(to) / 2.0 + BODY_HALF_HEIGHT
        for (candidate in dash.ctx.ports.entities.nearby(mid, reach, dash.caster)) {
            if (candidate.entityId == dash.caster) continue
            if (op.hitOnce && candidate.entityId in dash.hit) continue
            val center = candidate.box.center
            if (distanceToSegment(center, from + body, to + body) > contactReach) continue
            if (!movement.isAlive(candidate.entityId)) continue
            dash.hit += candidate.entityId
            host.runWithVictim(dash.ctx, op.id, candidate.entityId, center, op.onHit)
            if (dash.finished) return
        }
    }

    private fun finish(dash: Dash) {
        if (dash.finished) return
        dash.finished = true
        active -= dash
        movement.setVelocity(dash.caster, Vec3.ZERO)
        if (dash.immunityOwned) movement.releaseInvulnerable(dash.caster)
    }

    private fun approachDistance(
        ports: SkillRuntimePorts, caster: UUID, feet: Vec3, direction: Vec3, distance: Double, radius: Double, stopBeforeTarget: Double?,
    ): Double {
        val gap = stopBeforeTarget?.coerceAtLeast(0.0) ?: return distance
        val origin = feet + Vec3(0.0, BODY_HALF_HEIGHT, 0.0)
        return ports.entities.nearby(origin, distance + gap + radius, caster)
            .asSequence()
            .filter { it.entityId != caster && movement.isAlive(it.entityId) }
            .mapNotNull { candidate ->
                val delta = candidate.box.center - origin
                val ahead = delta.dot(direction)
                if (ahead <= 0.0 || (delta - direction * ahead).length() > radius + BODY_HALF_WIDTH) null
                else (ahead - gap).coerceIn(0.0, distance)
            }
            .minOrNull() ?: distance
    }

    /** Sample the swept path (feet, knee, head probes) so a long or descending step cannot skip a thin wall or floor. */
    private fun blockedBetween(ports: SkillRuntimePorts, from: Vec3, to: Vec3): Boolean {
        val samples = ceil(from.distance(to) / 0.25).toInt().coerceAtLeast(1)
        var prev = from
        for (i in 1..samples) {
            val next = from + (to - from) * (i.toDouble() / samples)
            for (dy in PROBE_HEIGHTS) {
                val up = Vec3(0.0, dy, 0.0)
                if (ports.entities.raycastBlock(prev + up, next + up) != null) return true
            }
            prev = next
        }
        return false
    }

    private fun heading(direction: Vec3, followPitch: Boolean): Vec3 {
        val level = Vec3(direction.x, 0.0, direction.z)
        val chosen = if (followPitch || level.lengthSquared() < 1e-9) direction else level
        return chosen.normalize().takeIf { it.lengthSquared() > 0.0 } ?: Vec3(0.0, 0.0, 1.0)
    }

    private fun distanceToSegment(point: Vec3, a: Vec3, b: Vec3): Double {
        val ab = b - a
        val len2 = ab.lengthSquared()
        if (len2 < 1e-12) return point.distance(a)
        val t = ((point - a).dot(ab) / len2).coerceIn(0.0, 1.0)
        return point.distance(a + ab * t)
    }

    private fun finite(v: Vec3): Boolean = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()

    private companion object {
        const val BODY_HALF_HEIGHT: Double = 0.9
        const val BODY_HALF_WIDTH: Double = 0.3
        const val SAFETY_TICKS: Int = 40
        val PROBE_HEIGHTS: List<Double> = listOf(0.01, 0.5, 1.5)
    }
}

/** Minecraft look vector: yaw 0 faces +Z, positive yaw turns toward -X; pitch positive looks down. */
internal fun lookVector(yawDeg: Double, pitchDeg: Double): Vec3 {
    val y = Math.toRadians(yawDeg)
    val p = Math.toRadians(pitchDeg)
    return Vec3(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
}

internal fun yawOf(level: Vec3): Double = Math.toDegrees(atan2(-level.x, level.z))
