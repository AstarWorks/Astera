package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.hit.HitBox

/**
 * Per-tick driver of the `dash` effect rows (R9, R40): one teleport per tick along a fixed
 * heading, distance accumulated over `ticks` steps through `ease`, optional immunity for the whole dash. The corridor hit is a
 * sibling `hit` row, not the driver's business.
 *
 * The caster never ends a step inside another body's hitbox ([DashClearance]): a plain dash stops [DashClearance.GAP] short of the
 * front edge of the first body in its way (and ends there); a `pass_through` dash runs on and each step that would end inside a body
 * ends [DashClearance.GAP] behind it instead. A pass-through whose way out is blocked by a solid block cannot be resolved: it stops
 * where it stands and reports a fault ([drainFaults]).
 *
 * One instance per cast. The first step runs inside [start] (the cast tick), the rest in [tick], which the player calls once per
 * tick before it plays that tick's rows.
 */
public class DashDriver(
    private val cast: CastRef,
    private val movement: ISkillMovementGateway,
    private val query: ISkillEntityQuery,
) {
    /** The evaluated payload of a `dash` row. [directionLocal] is in the caster frame (x = left, y = up, z = forward). */
    public data class Params(
        val speed: Double,
        val ticks: Int,
        val directionLocal: Vec3,
        val ease: String,
        val followPitch: Boolean,
        val stopOnBlock: Boolean,
        val invulnerable: Boolean,
        val faceMovement: Boolean,
        val passThrough: Boolean,
    )

    private class Dash(
        val key: String,
        val p: Params,
        val easing: Easing,
        val origin: Vec3,
        val direction: Vec3,
        val travel: Double,
        val height: Double,
    ) {
        var step: Int = 0
        var position: Vec3 = origin
    }

    private val active = LinkedHashMap<String, Dash>()
    private val faults = ArrayList<String>()

    /** Faults raised since the last call (a position that could not be resolved); the player logs them. */
    public fun drainFaults(): List<String> = faults.toList().also { faults.clear() }

    public fun isActive(key: String): Boolean = key in active

    /** Starts the dash [key]; a message when the payload cannot be played, null when it started (or clamped to nothing at once). */
    public fun start(key: String, p: Params): String? {
        if (!p.speed.isFinite() || p.speed <= 0.0) return "dash speed ${p.speed} must be a positive number"
        if (p.ticks < 1) return "dash needs at least 1 tick"
        val easing = Easing.fromKey(p.ease) ?: return "dash ease '${p.ease}' is not an easing"
        val root = query.rootOf(cast, cast.caster) ?: return "dash: the caster has no pose"
        val local = p.directionLocal
        if (local.lengthSquared() < 1e-12) return "dash direction is the zero vector"
        val frame = Frame.fromYawPitch(Vec3.ZERO, root.yawDeg, if (p.followPitch) root.pitchDeg else 0.0)
        val direction = frame.directionToWorld(local.normalize()).normalize()
        val travel = p.speed * p.ticks
        if (travel <= 0.0) return null
        active.remove(key)?.let { finish(it) }
        val dash = Dash(key, p, easing, root.feet, direction, travel, root.height)
        active[key] = dash
        if (p.invulnerable) movement.invulnerable(cast, cast.caster, p.ticks + SAFETY_TICKS)
        if (p.faceMovement) movement.faceMovement(cast, cast.caster, direction)
        stepOnce(dash)
        return null
    }

    /** Advance every running dash one step. */
    public fun tick() {
        for (dash in active.values.toList()) stepOnce(dash)
    }

    /** `dash.end` row (or an `until:` event): stops dash [key] where it stands. */
    public fun end(key: String) {
        active[key]?.let { finish(it) }
    }

    /** Cast cancelled or finished: stops every dash of the cast. */
    public fun cancelAll() {
        active.values.toList().forEach { finish(it) }
    }

    private fun stepOnce(dash: Dash) {
        if (dash.key !in active) return
        dash.step++
        val progress = dash.step.toDouble() / dash.p.ticks
        // Never behind the last resolved spot: a pass-through step pushed past a body must not be pulled back by the nominal curve.
        val along = maxOf(dash.travel * dash.easing.apply(progress), (dash.position - dash.origin).dot(dash.direction))
        var to = dash.origin + dash.direction * along
        var stopped = false
        val bodies = bodiesAlong(dash, to)
        if (bodies.isNotEmpty()) {
            if (dash.p.passThrough) {
                to = DashClearance.behind(bodies, to, dash.direction) ?: run {
                    faults += "dash '${dash.key}': no clear spot behind the bodies in the way (${bodies.size} body box(es) around $to)"
                    finish(dash)
                    return
                }
            } else {
                DashClearance.frontEdge(bodies, dash.position, to)?.let { to = it; stopped = true }
            }
        }
        if (dash.p.stopOnBlock) {
            val block = movement.probeBlock(cast, dash.position, to)
            if (block != null) {
                if (bodies.any { DashClearance.inside(it, to) }) faults += "dash '${dash.key}': the way out of a body at $to is blocked by ${block.blockKey}"
                finish(dash)
                return
            }
        }
        if (to != dash.position) movement.teleport(cast, cast.caster, to, null, null)
        dash.position = to
        if (stopped || dash.step >= dash.p.ticks) finish(dash)
    }

    /** The forbidden feet boxes of every body near the segment [Dash.position] -> [to] (grown by the caster's own box). */
    private fun bodiesAlong(dash: Dash, to: Vec3): List<HitBox> {
        val mid = (dash.position + to) * 0.5
        val radius = dash.position.distance(to) / 2.0 + BODY_REACH
        return query.bodiesNear(cast, mid, radius, cast.caster).map { DashClearance.forbidden(it, dash.height) }
    }

    private fun finish(dash: Dash) {
        if (active.remove(dash.key) == null) return
        val v = movement.velocity(cast, cast.caster)
        movement.addVelocity(cast, cast.caster, Vec3(-v.x, -v.y, -v.z))
        if (dash.p.invulnerable) movement.invulnerable(cast, cast.caster, 0)
    }

    private companion object {
        const val SAFETY_TICKS: Int = 2

        /** Broad-phase margin around the segment: covers the half extent of the largest body (a boss-sized mob). */
        const val BODY_REACH: Double = 4.0
    }
}
