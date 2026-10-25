package com.astarworks.astera.core.domain.effect.scheduler

import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Tick-based scheduled particle emission descriptor — input to
 * `IParticleScheduler.schedule(...)` (Wave 5effects / ADR-0109).
 *
 * The variants capture every emission shape Wave 5pve-boss, Wave 5combat-depth,
 * and Wave 5h require from a Core-level scheduler:
 *
 *  - [EmitOnce] — single particle burst at a fixed absolute tick. The
 *    primitive replacement for `IWorldGateway.spawnParticle` when callers
 *    want a deferred fire (`atTick > currentTick`).
 *  - [Loop] — repeat a particle every `intervalTicks` for `durationTicks`.
 *    `durationTicks = 0` means "fire forever until handle is cancelled".
 *  - [Burst] — emit `count` copies of the same particle spread evenly across
 *    `spreadTicks` (random jitter per copy is implementation-defined). Models
 *    "shotgun-like" emit patterns that should not all fire on the same tick.
 *  - [Pulse] — emit at a radius that linearly interpolates from
 *    `fromRadius` to `toRadius` over `durationTicks`. The implementation
 *    emits one ring per tick (or per `stepTicks` if specified), using the
 *    underlying [ParticleSpec] count per ring.
 *  - [RingExpansion] — center-anchored expanding circle. Radius grows by
 *    `expandRate` blocks per tick for `durationTicks`. Models boss-phase
 *    transition shock-waves and ability tells.
 *
 * The world coordinate is bound at schedule time — none of the variants
 * track the casting entity, so an archetype that wants a player-following
 * effect re-schedules each tick rather than relying on the scheduler.
 *
 * # Wave 6 boundary
 *
 * Variants are deliberately concrete (no `kotlinx.serialization`,
 * no Bukkit types). The Paper adapter is a pure executor that converts
 * each emission into a `World.spawnParticle(...)` call.
 */
public sealed interface ScheduledParticleSpec {
    /** The world the emission targets. */
    public val world: WorldId?

    /**
     * Single deferred emission at absolute server tick [atTick]. If `atTick`
     * is in the past at schedule-time the implementation fires on the next
     * dispatch cycle (matches `IScheduler.scheduleAt`).
     */
    public data class EmitOnce(
        public val particle: ParticleSpec,
        public val position: Vec3,
        public val atTick: Long,
        override val world: WorldId? = null,
    ) : ScheduledParticleSpec

    /**
     * Periodic emission. First fire at `initialDelayTicks`, then every
     * `intervalTicks` until `durationTicks` has elapsed since the first
     * fire. `durationTicks == 0` means "no auto-cancel; caller controls
     * lifetime via the returned [com.astarworks.astera.core.domain.effect.EffectHandle]".
     */
    public data class Loop(
        public val particle: ParticleSpec,
        public val position: Vec3,
        public val intervalTicks: Long,
        public val durationTicks: Long,
        public val initialDelayTicks: Long = 0L,
        override val world: WorldId? = null,
    ) : ScheduledParticleSpec {
        init {
            require(intervalTicks >= 1) { "intervalTicks must be >= 1, got $intervalTicks" }
            require(durationTicks >= 0) { "durationTicks must be >= 0, got $durationTicks" }
            require(initialDelayTicks >= 0) { "initialDelayTicks must be >= 0, got $initialDelayTicks" }
        }
    }

    /**
     * `count` emissions spread evenly across `spreadTicks`. Useful for
     * shotgun / muzzle-flash patterns where bunching every particle on the
     * same tick visually fuses them into a single sprite.
     *
     * `spreadTicks = 0` falls back to a single-tick burst.
     */
    public data class Burst(
        public val particle: ParticleSpec,
        public val position: Vec3,
        public val count: Int,
        public val spreadTicks: Long,
        public val initialDelayTicks: Long = 0L,
        override val world: WorldId? = null,
    ) : ScheduledParticleSpec {
        init {
            require(count >= 1) { "count must be >= 1, got $count" }
            require(spreadTicks >= 0) { "spreadTicks must be >= 0, got $spreadTicks" }
            require(initialDelayTicks >= 0) { "initialDelayTicks must be >= 0, got $initialDelayTicks" }
        }
    }

    /**
     * Linearly interpolated pulse. Each step emits the underlying
     * [particle] at a radius interpolated from [fromRadius] to [toRadius].
     *
     *  - `stepTicks = 1` — one ring per tick (default).
     *  - `stepTicks > 1` — coarser emission, fewer rings.
     *
     * Total emissions = `(durationTicks / stepTicks) + 1`.
     */
    public data class Pulse(
        public val particle: ParticleSpec,
        public val center: Vec3,
        public val fromRadius: Double,
        public val toRadius: Double,
        public val durationTicks: Long,
        public val stepTicks: Long = 1L,
        public val initialDelayTicks: Long = 0L,
        override val world: WorldId? = null,
    ) : ScheduledParticleSpec {
        init {
            require(fromRadius >= 0.0) { "fromRadius must be >= 0, got $fromRadius" }
            require(toRadius >= 0.0) { "toRadius must be >= 0, got $toRadius" }
            require(durationTicks >= 1) { "durationTicks must be >= 1, got $durationTicks" }
            require(stepTicks >= 1) { "stepTicks must be >= 1, got $stepTicks" }
            require(initialDelayTicks >= 0) { "initialDelayTicks must be >= 0, got $initialDelayTicks" }
        }
    }

    /**
     * Expanding ring. Emits a circle of [particle] at [center] every tick
     * (or every [stepTicks]); radius grows by [expandRate] blocks per
     * tick. Total lifetime = [durationTicks]. Models boss shock-wave
     * tells and area-warning rings.
     */
    public data class RingExpansion(
        public val particle: ParticleSpec,
        public val center: Vec3,
        public val expandRate: Double,
        public val durationTicks: Long,
        public val initialRadius: Double = 0.0,
        public val stepTicks: Long = 1L,
        public val initialDelayTicks: Long = 0L,
        override val world: WorldId? = null,
    ) : ScheduledParticleSpec {
        init {
            require(expandRate > 0.0) { "expandRate must be > 0, got $expandRate" }
            require(durationTicks >= 1) { "durationTicks must be >= 1, got $durationTicks" }
            require(initialRadius >= 0.0) { "initialRadius must be >= 0, got $initialRadius" }
            require(stepTicks >= 1) { "stepTicks must be >= 1, got $stepTicks" }
            require(initialDelayTicks >= 0) { "initialDelayTicks must be >= 0, got $initialDelayTicks" }
        }
    }
}
