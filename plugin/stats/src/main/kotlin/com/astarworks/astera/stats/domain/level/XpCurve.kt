package com.astarworks.astera.stats.domain.level

/**
 * Defines the XP curve for player leveling.
 *
 * The threshold to reach level n is the sum of 0 + 100 + 200 + ... + 100*(n-2),
 * which equals 100 * (n-1) * (n-2) / 2 for n >= 2, and 0 for n == 1.
 *
 * Formula simplified: thresholdToReach(n) = 100 * (n-1) * (n-2) / 2
 * But we want sum of 0+100+200+...+100*(n-2) = 100 * sum(0..n-2) = 100 * (n-2)*(n-1)/2
 *
 * Level 1 starts at 0 xp.
 * Level 2 starts at 100 xp.
 * Level 3 starts at 300 xp (100+200).
 * Level 4 starts at 600 xp (100+200+300).
 *
 * Per plan: thresholdToReach(level) = 100L * (level - 1) * level / 2
 * - level=1 → 100*0*1/2 = 0 ✓
 * - level=2 → 100*1*2/2 = 100 ✓
 * - level=3 → 100*2*3/2 = 300 ✓
 * - level=4 → 100*3*4/2 = 600 ✓
 */
public object XpCurve {

    /**
     * Total XP required to reach the *start* of [level].
     * Level 1 requires 0 XP.
     */
    public fun thresholdToReach(level: Int): Long {
        require(level >= 1) { "level must be >= 1, got $level" }
        return 100L * (level - 1) * level / 2
    }

    /**
     * Returns the [Level] achieved by [totalXp].
     * Finds the largest level n such that thresholdToReach(n) <= totalXp.
     */
    public fun levelFor(totalXp: Long): Level {
        require(totalXp >= 0) { "totalXp must be >= 0, got $totalXp" }
        // Solve: 100*(n-1)*n/2 <= totalXp  →  n^2 - n - 2*totalXp/100 <= 0
        // Using quadratic formula: n <= (1 + sqrt(1 + 8*totalXp/100)) / 2
        val n = ((1.0 + Math.sqrt(1.0 + 8.0 * totalXp / 100.0)) / 2.0).toLong()
        // Walk back to ensure correctness (floating-point might be off by 1)
        var level = n.toInt().coerceAtLeast(1)
        while (level > 1 && thresholdToReach(level) > totalXp) {
            level--
        }
        // Walk forward if we can go higher
        while (thresholdToReach(level + 1) <= totalXp) {
            level++
        }
        return Level(level)
    }
}
