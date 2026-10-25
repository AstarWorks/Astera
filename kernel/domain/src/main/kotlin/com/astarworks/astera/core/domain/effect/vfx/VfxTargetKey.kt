package com.astarworks.astera.core.domain.effect.vfx

/** Element-relative interpolation-clock time and fraction of the live target correction. */
public data class VfxTargetKey(public val t: Int, public val weight: Double) {
    init {
        require(t >= 0) { "target track time must be non-negative" }
        require(weight.isFinite() && weight in 0.0..1.0) { "target track weight must be finite and within 0..1" }
    }
}

/** Linear interpolation, holding the first/last value outside the keyed interval. */
public fun List<VfxTargetKey>.targetWeightAt(t: Int): Double {
    if (isEmpty()) return 0.0
    if (t <= first().t) return first().weight
    val next = indexOfFirst { it.t > t }
    if (next < 0) return last().weight
    val a = this[next - 1]
    val b = this[next]
    return a.weight + (b.weight - a.weight) * (t - a.t).toDouble() / (b.t - a.t)
}
