package com.astarworks.astera.match.domain.norma

/**
 * Norma tunables of a match: the shared layer defaults (`content/norma-layer/layer.yaml`) overlaid with the match's
 * game mode `default_config` (a mode overrides any key). `norma_max_level`, `norma_stars_goal`, `norma_kills_goal`
 * (comma lists indexed by the level being cleared), `norma_win` and `death_drop_ratio`. Missing or malformed values
 * fall back to the defaults.
 */
public data class NormaConfig(
    val maxLevel: Int = DEFAULT_MAX_LEVEL,
    val starsGoal: List<Int> = DEFAULT_STARS_GOAL,
    val killsGoal: List<Int> = DEFAULT_KILLS_GOAL,
    val deathDropRatio: Double = DEFAULT_DEATH_DROP_RATIO,
    /** `norma_win`: reaching [maxLevel] wins the match (alongside the mode's own objective, whichever comes first). */
    val win: Boolean = true,
) {
    init {
        require(maxLevel >= 2) { "norma_max_level must be >= 2 (was $maxLevel)" }
        require(starsGoal.isNotEmpty() && starsGoal.all { it >= 1 }) { "norma_stars_goal must be positive numbers" }
        require(killsGoal.isNotEmpty() && killsGoal.all { it >= 1 }) { "norma_kills_goal must be positive numbers" }
        require(deathDropRatio in 0.0..1.0) { "death_drop_ratio must be within 0..1 (was $deathDropRatio)" }
    }

    /** Stars to carry to clear [level] (the last list entry applies to every higher level). */
    public fun starsGoalAt(level: Int): Int = starsGoal[(level - 1).coerceIn(0, starsGoal.lastIndex)]

    /** Kills to make at [level] to clear it. */
    public fun killsGoalAt(level: Int): Int = killsGoal[(level - 1).coerceIn(0, killsGoal.lastIndex)]

    public fun targetFor(level: Int, goal: NormaGoal): Int =
        if (goal == NormaGoal.STAR) starsGoalAt(level) else killsGoalAt(level)

    public companion object {
        public const val DEFAULT_MAX_LEVEL: Int = 5
        public val DEFAULT_STARS_GOAL: List<Int> = listOf(10, 30, 60, 100)
        public val DEFAULT_KILLS_GOAL: List<Int> = listOf(1, 2, 3, 4)
        public const val DEFAULT_DEATH_DROP_RATIO: Double = 0.5

        public fun parse(raw: Map<String, String>): NormaConfig {
            fun list(key: String, fallback: List<Int>): List<Int> =
                raw[key]?.split(',')?.mapNotNull { it.trim().toIntOrNull()?.takeIf { n -> n >= 1 } }
                    ?.takeIf { it.isNotEmpty() } ?: fallback
            return NormaConfig(
                maxLevel = raw["norma_max_level"]?.trim()?.toIntOrNull()?.takeIf { it >= 2 } ?: DEFAULT_MAX_LEVEL,
                starsGoal = list("norma_stars_goal", DEFAULT_STARS_GOAL),
                killsGoal = list("norma_kills_goal", DEFAULT_KILLS_GOAL),
                deathDropRatio = raw["death_drop_ratio"]?.trim()?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }
                    ?: DEFAULT_DEATH_DROP_RATIO,
                win = raw["norma_win"]?.trim()?.lowercase() != "false",
            )
        }
    }
}
