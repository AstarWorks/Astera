package com.astarworks.astera.stats.domain.level

/**
 * Per-level threshold for advancing to the next level (Wave 5e).
 *
 * Bridges [LevelUpMission] (which criterion to use) to a numeric target.
 * Worker ι extends `ConfiguredLevelParameter` to carry a
 * `Map<Int, ConfiguredNormaParameter>` keyed by current level → next-level
 * criterion. `AdvanceLevelOnNormaCompletedListener` subscribes to
 * `NormaCompleted` and consults this map to decide whether to fire
 * `LeveledUp`.
 */
public data class ConfiguredNormaParameter(
    val mission: LevelUpMission,
    val threshold: Long,
) {
    init { require(threshold >= 1) { "threshold must be >= 1: $threshold" } }
}
