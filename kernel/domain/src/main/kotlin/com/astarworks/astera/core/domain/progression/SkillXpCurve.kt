package com.astarworks.astera.core.domain.progression

/**
 * XP → level curve for a single [SkillKind] (Wave 5multi-skill, ADR-0101).
 *
 * Pure value object. Encodes the level thresholds as a monotonically
 * increasing list of cumulative-XP requirements: [thresholds]`[n]` is the XP
 * total needed to reach level `n + 1` (so [thresholds]`[0]` is the XP to
 * reach Lv 2 from Lv 1, etc.).
 *
 * # Why a list, not a formula
 *
 * Per ADR-0101 §"XP 曲線" the curve is "tunable in YAML". A list lets
 * designers express HSB-style irregular curves (steep early ramp, flatten
 * mid-game, blow up near cap) without a polynomial. The [exponential]
 * factory keeps the simple "Lv 1→100 ≈ 100M total" default cheap to
 * generate without hand-writing 99 thresholds.
 *
 * # Invariants
 *
 *  - [thresholds] is non-empty
 *  - Every element is `>= 1` (level-up costs are positive)
 *  - Strictly increasing (`thresholds[n] < thresholds[n+1]`)
 */
public data class SkillXpCurve(
    public val thresholds: List<Long>,
) {
    init {
        require(thresholds.isNotEmpty()) { "SkillXpCurve.thresholds must be non-empty" }
        thresholds.forEachIndexed { i, t ->
            require(t >= 1L) { "thresholds[$i]=$t must be >= 1" }
        }
        for (i in 1 until thresholds.size) {
            require(thresholds[i] > thresholds[i - 1]) {
                "thresholds must be strictly increasing; thresholds[${i - 1}]=${thresholds[i - 1]} >= thresholds[$i]=${thresholds[i]}"
            }
        }
    }

    /** Max level this curve can express (size + 1, since the last threshold is the Lv (n+1) gate). */
    public val maxLevel: Int get() = thresholds.size + 1

    /**
     * Compute the level reached by a player with [totalXp] cumulative XP.
     * Level 1 corresponds to `[0, thresholds[0])`; level 2 to
     * `[thresholds[0], thresholds[1])`; level `maxLevel` to
     * `[thresholds.last(), +∞)`.
     */
    public fun levelFor(totalXp: Long): Int {
        require(totalXp >= 0L) { "totalXp must be >= 0, got $totalXp" }
        var lv = 1
        for (threshold in thresholds) {
            if (totalXp >= threshold) lv++ else break
        }
        return lv
    }

    /**
     * XP required to reach [level]. Returns 0 for level 1, the matching
     * threshold for levels 2..maxLevel, and Long.MAX_VALUE for over-cap.
     */
    public fun thresholdFor(level: Int): Long {
        require(level >= 1) { "level must be >= 1, got $level" }
        return when {
            level == 1 -> 0L
            level <= maxLevel -> thresholds[level - 2]
            else -> Long.MAX_VALUE
        }
    }

    public companion object {

        /**
         * Exponential curve (HSB-flavoured) used as the Phase 1 default.
         *
         * `thresholds[n] = floor(base * factor^n)` clamped to monotone-increasing
         * (the floor step is a no-op for the values we generate but guards against
         * future tuning that gives `factor` close to 1.0).
         *
         * Defaults: [base] = 100, [factor] = 1.12, [maxLevel] = 100 → cumulative
         * around ~85M XP at Lv 100 (matches ADR-0101 §"XP 曲線" target order).
         */
        public fun exponential(
            base: Long = 100L,
            factor: Double = 1.12,
            maxLevel: Int = 100,
        ): SkillXpCurve {
            require(base >= 1L) { "base must be >= 1, got $base" }
            require(factor > 1.0) { "factor must be > 1.0 for an exponential curve, got $factor" }
            require(maxLevel >= 2) { "maxLevel must be >= 2, got $maxLevel" }
            val out = ArrayList<Long>(maxLevel - 1)
            var prev = 0L
            for (n in 0 until maxLevel - 1) {
                val raw = (base.toDouble() * Math.pow(factor, n.toDouble())).toLong()
                // Guarantee strictly increasing even if rounding collides.
                val next = if (raw <= prev) prev + 1L else raw
                out += next
                prev = next
            }
            return SkillXpCurve(out)
        }
    }
}
