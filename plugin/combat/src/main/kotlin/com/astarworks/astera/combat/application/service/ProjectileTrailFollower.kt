package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IProjectileHandle
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.effect.IParticleAnimator
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.effect.animation.ParticleAnimation
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import kotlin.math.ceil

/**
 * Replays a skill's `params.trail` animation at a projectile every tick while it
 * flies (RTM `SimpleShoot`: the `Projectile` particle set was spawned at every
 * sub-step of the shot, ADR-0196).
 *
 * Each tick the follower reads [IProjectileHandle.position], and plays the animation at
 * the new position, or, with `spacing > 0`, at every `spacing` blocks along the segment
 * the projectile covered since the last tick, which keeps a fast projectile's trail a
 * continuous beam. The same animation player is reused for the whole flight so circle
 * orbits continue instead of restarting. Stops when the handle dies or reports no position.
 */
public class ProjectileTrailFollower(
    private val scheduler: IScheduler,
    private val animator: IParticleAnimator,
) {

    public fun follow(
        handle: IProjectileHandle,
        world: WorldId,
        origin: Vec3,
        direction: Vec3,
        animation: ParticleAnimation,
        spacing: Double,
    ) {
        val player = animator.open(world, animation)
        var last = origin
        // The muzzle itself is the first trail sample; ProjectileEnd/ProjectileStart live in phases.
        player.play(origin, direction)
        var task: IScheduler.TaskHandle? = null
        task = scheduler.runRepeatingTicks(initialDelayTicks = 1L, periodTicks = 1L) {
            val now = handle.position
            if (!handle.isAlive || now == null) {
                task?.cancel()
                player.cancel()
                return@runRepeatingTicks
            }
            val distance = last.distance(now)
            if (spacing > 0.0 && distance > spacing) {
                val steps = ceil(distance / spacing).toInt()
                for (i in 1..steps) {
                    val t = i.toDouble() / steps
                    player.play(last + (now - last) * t, direction)
                }
            } else {
                player.play(now, direction)
            }
            last = now
        }
    }
}
