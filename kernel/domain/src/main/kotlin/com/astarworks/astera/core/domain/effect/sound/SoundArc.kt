package com.astarworks.astera.core.domain.effect.sound

import com.astarworks.astera.core.domain.animation.Easing

/**
 * Pitch glide of one `play_sound`: pitch moves from [from] to [to] over [ticks] ticks along [ease]. Expanded into a
 * `ScheduledSoundSpec.Sequence` by the sound planner; every pitch is clamped to the vanilla 0.5..2.0 range.
 */
public data class PitchArc(
    public val from: Double,
    public val to: Double,
    public val ticks: Int,
    public val ease: Easing = Easing.LINEAR,
) {
    init {
        require(ticks >= 1) { "pitch_arc ticks must be >= 1: $ticks" }
        require(from > 0.0 && to > 0.0) { "pitch_arc from/to must be > 0: $from -> $to" }
    }
}

/**
 * Musical scale for a `play_sound`: successive plays step through [steps] (semitones above [root] pitch).
 * [root] is a pitch multiplier (1.0 = unshifted).
 */
public data class SoundScale(
    public val root: Double = 1.0,
    public val steps: List<Int>,
) {
    init {
        require(root > 0.0) { "scale root must be > 0: $root" }
        require(steps.isNotEmpty()) { "scale steps must not be empty" }
    }
}
