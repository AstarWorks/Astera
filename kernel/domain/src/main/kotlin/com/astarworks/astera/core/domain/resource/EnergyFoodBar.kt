package com.astarworks.astera.core.domain.resource

import kotlin.math.roundToInt

/**
 * Mapping of the shared energy pool onto the vanilla food bar (F2-2, ADR-0194).
 *
 * The food level (0..[MAX_FOOD_LEVEL]) is a lossy mirror of `energy / max`:
 *  - 0 only when energy is 0, [MAX_FOOD_LEVEL] only when it is full;
 *  - any energy in between reads 1..19, so the bar never lies about "empty" or "full".
 *
 * Vanilla only lets a player *start* sprinting above food level
 * [SPRINT_MIN_EXCLUSIVE]; that is energy above [SPRINT_THRESHOLD_RATIO], and is
 * kept on purpose (running on fumes is not allowed; sprinting itself costs nothing).
 */
public object EnergyFoodBar {

    public const val MAX_FOOD_LEVEL: Int = 20

    /** Vanilla: sprint needs a food level strictly above this. */
    public const val SPRINT_MIN_EXCLUSIVE: Int = 6

    /** Ratio of max energy at which the mirror first allows a sprint (food level 7 of 20). */
    public const val SPRINT_THRESHOLD_RATIO: Double = 0.3

    /** Food level for [current] of [max] energy. */
    public fun foodLevelFor(current: Int, max: Int): Int {
        if (max <= 0 || current <= 0) return 0
        if (current >= max) return MAX_FOOD_LEVEL
        return (current.toDouble() / max * MAX_FOOD_LEVEL).roundToInt().coerceIn(1, MAX_FOOD_LEVEL - 1)
    }
}
