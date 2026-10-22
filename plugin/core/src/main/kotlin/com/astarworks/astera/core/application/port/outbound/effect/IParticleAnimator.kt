package com.astarworks.astera.core.application.port.outbound.effect

import com.astarworks.astera.core.domain.effect.EffectHandle
import com.astarworks.astera.core.domain.effect.animation.ParticleAnimation
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: plays a time-animated [ParticleAnimation] (ADR-0187; the particle
 * scheduler ADR-0077 deferred). Backs `Mechanic.ScheduleParticleAnimation` and the
 * per-tick projectile trail.
 *
 * The default implementation expands the animation with the pure
 * `ParticleAnimationSession` and fires each spawn through `IWorldGateway.spawnParticle`
 * on its tick, so the Paper adapter (real particles) and the game-sim recorder (trace
 * particles) both see the same stream.
 */
public interface IParticleAnimator {

    /** Play [animation] once, anchored at [origin] facing [direction]. */
    public fun play(world: WorldId, origin: Vec3, direction: Vec3, animation: ParticleAnimation): EffectHandle =
        open(world, animation).play(origin, direction)

    /**
     * A stateful player for [animation]: every [Player.play] continues the same
     * circle orbits (RTM kept this state per scheduler), which is what a trail that
     * re-plays the animation each tick needs.
     */
    public fun open(world: WorldId, animation: ParticleAnimation): Player

    public interface Player {
        /** Anchor the animation at [origin] facing [direction]; spawns with offset 0 happen before this returns. */
        public fun play(origin: Vec3, direction: Vec3): EffectHandle

        /** Cancel every spawn still pending from earlier [play] calls. */
        public fun cancel()
    }
}
