package com.astarworks.astera.match.domain.norma

import com.astarworks.astera.core.domain.item.Rarity

/**
 * Norma tunables of a match: the shared layer defaults (`content/norma-layer/layer.yaml`) overlaid with the match's
 * game mode `default_config` (a mode overrides any key). `norma_max_level`, `norma_stars_goal`, `norma_kills_goal`
 * (comma lists indexed by the level being cleared), `death_drop_ratio`, and `rarity_levels` (`rarity:level` pairs: the
 * Norma level at which weapons of that rarity become usable, ADR-0289). Missing or malformed values
 * fall back to the defaults.
 */
public data class NormaConfig(
    val maxLevel: Int = DEFAULT_MAX_LEVEL,
    val starsGoal: List<Int> = DEFAULT_STARS_GOAL,
    val killsGoal: List<Int> = DEFAULT_KILLS_GOAL,
    val deathDropRatio: Double = DEFAULT_DEATH_DROP_RATIO,
    val rarityLevels: Map<Rarity, Int> = DEFAULT_RARITY_LEVELS,
) {
    init {
        require(maxLevel >= 2) { "norma_max_level must be >= 2 (was $maxLevel)" }
        require(starsGoal.isNotEmpty() && starsGoal.all { it >= 1 }) { "norma_stars_goal must be positive numbers" }
        require(killsGoal.isNotEmpty() && killsGoal.all { it >= 1 }) { "norma_kills_goal must be positive numbers" }
        require(deathDropRatio in 0.0..1.0) { "death_drop_ratio must be within 0..1 (was $deathDropRatio)" }
        require(Rarity.entries.all { (rarityLevels[it] ?: 0) >= 1 }) { "rarity_levels must give every rarity a level >= 1" }
    }

    /** The Norma level at which weapons of [rarity] become usable (1 = from the start). */
    public fun levelFor(rarity: Rarity): Int = rarityLevels.getValue(rarity)

    /** The rarities that open exactly at [level] (above level 1; what a level-up announces). */
    public fun unlocksAt(level: Int): List<NormaUnlock> =
        Rarity.entries.filter { levelFor(it) == level && level > 1 }.map { NormaUnlock(level, it) }

    /** The nearest rarity still locked above [level] (the sidebar's "next unlock"), or `null` when none is left. */
    public fun nextUnlockAfter(level: Int): NormaUnlock? =
        Rarity.entries.filter { levelFor(it) > level }.minWithOrNull(compareBy({ levelFor(it) }, { it.ordinal }))
            ?.let { NormaUnlock(levelFor(it), it) }

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
        public val DEFAULT_RARITY_LEVELS: Map<Rarity, Int> =
            mapOf(Rarity.COMMON to 1, Rarity.UNCOMMON to 2, Rarity.RARE to 3, Rarity.EPIC to 4, Rarity.LEGENDARY to 5)

        public fun parse(raw: Map<String, String>): NormaConfig {
            fun list(key: String, fallback: List<Int>): List<Int> =
                raw[key]?.split(',')?.mapNotNull { it.trim().toIntOrNull()?.takeIf { n -> n >= 1 } }
                    ?.takeIf { it.isNotEmpty() } ?: fallback
            val rarityLevels = DEFAULT_RARITY_LEVELS + raw["rarity_levels"].orEmpty().split(',').mapNotNull { pair ->
                val (name, level) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val rarity = Rarity.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: return@mapNotNull null
                level.trim().toIntOrNull()?.takeIf { it >= 1 }?.let { rarity to it }
            }
            return NormaConfig(
                maxLevel = raw["norma_max_level"]?.trim()?.toIntOrNull()?.takeIf { it >= 2 } ?: DEFAULT_MAX_LEVEL,
                starsGoal = list("norma_stars_goal", DEFAULT_STARS_GOAL),
                killsGoal = list("norma_kills_goal", DEFAULT_KILLS_GOAL),
                deathDropRatio = raw["death_drop_ratio"]?.trim()?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }
                    ?: DEFAULT_DEATH_DROP_RATIO,
                rarityLevels = rarityLevels,
            )
        }
    }
}
