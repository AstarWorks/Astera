package com.astarworks.astera.stats.domain.level

/**
 * Per-level criterion bridging `Norma` (mission completion) and
 * `Level` (XP-driven progression) — Wave 5e, RTM `LevelUpMission.kt`.
 *
 * Level advancement is gated on kill-count thresholds per the level
 * definition (see [ConfiguredNormaParameter]). Stars are an in-match item
 * and never persist (ADR-0262), so there is no star-collection variant.
 */
public enum class LevelUpMission {
    /** Advance when kill threshold is met. */
    KILL,
}
