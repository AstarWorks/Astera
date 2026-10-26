package com.astarworks.astera.core.domain.effect.sound

import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Tick-based scheduled sound emission descriptor — input to
 * `ISoundScheduler.schedule(...)` (Wave 5effects / ADR-0109).
 *
 * Mirrors [com.astarworks.astera.core.domain.effect.scheduler.ScheduledParticleSpec]
 * with sound-shaped variants:
 *
 *  - [EmitOnce] — single play at an absolute tick (deferred fire).
 *  - [Delayed] — sugar: play after [delayTicks] from now (resolved by the
 *    scheduler against [com.astarworks.astera.core.application.port.outbound.IScheduler.currentTick]
 *    at schedule time).
 *  - [Loop] — repeat the same sound every [intervalTicks] for
 *    [durationTicks] (`0` = caller-controlled lifetime).
 *  - [Sequence] — fire a list of `(sound, atTick)` pairs in order. Useful
 *    for multi-stage cues (charge-up → release → echo).
 */
public sealed interface ScheduledSoundSpec {

    /** The world the sound plays in. */
    public val world: WorldId?

    public data class EmitOnce(
        public val sound: SoundSpec,
        public val position: Vec3,
        public val atTick: Long,
        override val world: WorldId? = null,
    ) : ScheduledSoundSpec

    public data class Delayed(
        public val sound: SoundSpec,
        public val position: Vec3,
        public val delayTicks: Long,
        override val world: WorldId? = null,
    ) : ScheduledSoundSpec {
        init {
            require(delayTicks >= 0) { "delayTicks must be >= 0, got $delayTicks" }
        }
    }

    public data class Loop(
        public val sound: SoundSpec,
        public val position: Vec3,
        public val intervalTicks: Long,
        public val durationTicks: Long,
        public val initialDelayTicks: Long = 0L,
        override val world: WorldId? = null,
    ) : ScheduledSoundSpec {
        init {
            require(intervalTicks >= 1) { "intervalTicks must be >= 1, got $intervalTicks" }
            require(durationTicks >= 0) { "durationTicks must be >= 0, got $durationTicks" }
            require(initialDelayTicks >= 0) { "initialDelayTicks must be >= 0, got $initialDelayTicks" }
        }
    }

    public data class Sequence(
        public val steps: List<TimedSound>,
        override val world: WorldId? = null,
    ) : ScheduledSoundSpec {
        init {
            require(steps.isNotEmpty()) { "Sequence steps must not be empty" }
        }
    }
}

/**
 * One step in a [ScheduledSoundSpec.Sequence]. [atTick] is interpreted as
 * a relative offset from the schedule call (`0` = "fire on next dispatch
 * cycle"); use a [com.astarworks.astera.core.application.port.outbound.IScheduler.currentTick]
 * + offset if you need absolute scheduling.
 */
public data class TimedSound(
    public val sound: SoundSpec,
    public val position: Vec3,
    public val atTick: Long,
) {
    init {
        require(atTick >= 0) { "atTick must be >= 0, got $atTick" }
    }
}
