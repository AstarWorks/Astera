package com.astarworks.astera.core.domain.effect.sound

import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import kotlin.math.pow

/**
 * Pure expansion of [PitchArc] and [SoundScale] into scheduler input (ADR-0290).
 *
 * Every pitch produced here is clamped to the vanilla playable range [SoundSpec.MIN_PITCH]..[SoundSpec.MAX_PITCH]
 * (0.5..2.0): the client cannot play outside it, so an arc or scale that leaves the range holds at the nearest bound
 * instead of failing.
 */
public object SoundPlanner {

    /** Pitch of [arc] at tick [tick] (0..[PitchArc.ticks]), eased and clamped to 0.5..2.0. */
    public fun pitchAt(arc: PitchArc, tick: Int): Double {
        val t = tick.coerceIn(0, arc.ticks).toDouble() / arc.ticks
        return clampPitch(arc.ease.interpolate(arc.from, arc.to, t))
    }

    /**
     * The pitch of [arc] for each emission: tick 0, [intervalTicks], 2x[intervalTicks] ... and always the final tick
     * [PitchArc.ticks] (so the glide reaches `to`). Keys are the tick offsets.
     */
    public fun arcPitches(arc: PitchArc, intervalTicks: Int = arc.intervalTicks): List<Pair<Int, Double>> {
        require(intervalTicks >= 1) { "intervalTicks must be >= 1: $intervalTicks" }
        val offsets = (0 until arc.ticks step intervalTicks).toMutableList()
        offsets += arc.ticks
        return offsets.map { it to pitchAt(arc, it) }
    }

    /**
     * Expands [arc] into one [ScheduledSoundSpec.Sequence]: a play of [key] at [position] per [arcPitches] entry, offsets
     * relative to the schedule call.
     */
    public fun arcSequence(
        key: String,
        volume: Double,
        arc: PitchArc,
        position: Vec3,
        world: WorldId? = null,
        intervalTicks: Int = arc.intervalTicks,
    ): ScheduledSoundSpec.Sequence = ScheduledSoundSpec.Sequence(
        steps = arcPitches(arc, intervalTicks).map { (tick, pitch) ->
            TimedSound(SoundSpec(key, volume, pitch), position, tick.toLong())
        },
        world = world,
    )

    /**
     * Pitch of the [index]-th play of [scale] (wraps around the steps): `root * 2^(semitones/12)`, clamped to 0.5..2.0.
     * [index] may be any non-negative loop index.
     */
    public fun scalePitch(scale: SoundScale, index: Long): Double {
        val semitones = scale.steps[(index.mod(scale.steps.size.toLong())).toInt()]
        return clampPitch(scale.root * 2.0.pow(semitones / 12.0))
    }

    /** [pitch] clamped to the vanilla 0.5..2.0 range. */
    public fun clampPitch(pitch: Double): Double = pitch.coerceIn(SoundSpec.MIN_PITCH, SoundSpec.MAX_PITCH)
}
