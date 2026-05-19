package com.astarworks.astera.stats.domain.level

/**
 * Per-level criterion bridging `Norma` (mission completion) and
 * `Level` (XP-driven progression) — Wave 5e, RTM `LevelUpMission.kt`.
 *
 * README §レベルシステム rationale: "レベルアップには一定のスター or
 * キルが必要 (スターは消費しない)". Astera honors this by gating
 * level advancement on either kill-count or star-collection thresholds
 * per the level definition (see [ConfiguredNormaParameter]).
 */
public enum class LevelUpMission {
    /** Advance when kill threshold is met. */
    KILL,

    /** Advance when star-collection threshold is met. */
    STAR,
}
