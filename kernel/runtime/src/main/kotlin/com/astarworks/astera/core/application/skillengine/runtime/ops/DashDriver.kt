package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame

/**
 * Per-tick driver of the `dash` effect rows (R9, R40): one teleport per tick along a fixed
 * heading, distance accumulated over `ticks` steps through `ease`, travel clamped `stop_before_target` short of the first body in
 * the corridor, optional immunity for the whole dash. The corridor hit is a sibling `hit` row, not the driver's business.
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
    )

    private class Dash(
        val key: String,
        val p: Params,
        val easing: Easing,
        val origin: Vec3,
        val direction: Vec3,
        val travel: Double,
    ) {
        var step: Int = 0
        var position: Vec3 = origin
    }

    private val active = LinkedHashMap<String, Dash>()

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
        val dash = Dash(key, p, easing, root.feet, direction, travel)
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
        val to = dash.origin + dash.direction * (dash.travel * dash.easing.apply(progress))
        if (dash.p.stopOnBlock && movement.probeBlock(cast, dash.position, to) != null) {
            finish(dash)
            return
        }
        movement.teleport(cast, cast.caster, to, null, null)
        dash.position = to
        if (dash.step >= dash.p.ticks) finish(dash)
    }

    private fun finish(dash: Dash) {
        if (active.remove(dash.key) == null) return
        val v = movement.velocity(cast, cast.caster)
        movement.addVelocity(cast, cast.caster, Vec3(-v.x, -v.y, -v.z))
        if (dash.p.invulnerable) movement.invulnerable(cast, cast.caster, 0)
    }

    private companion object {
        const val SAFETY_TICKS: Int = 2
    }
}
