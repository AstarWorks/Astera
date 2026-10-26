package com.astarworks.astera.core.domain.progression

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Per-player, per-axis progression state (Wave 5multi-skill, ADR-0101).
 *
 * Composite identity is `(playerId, kind)`. Each axis tracks its own
 * cumulative [xp] and the derived [level]. The repository owns persistence;
 * level is recomputed from [xp] via the registered XP curve when xp changes.
 *
 * # Invariants
 *
 *  - [xp] is non-negative cumulative (matches [com.astarworks.astera.stats.domain.level.Xp]).
 *  - [level] is `>= 1` and is the cached result of applying the axis' XP
 *    curve to [xp]; consumers MUST treat it as derived (the repository is
 *    the only writer that re-derives it).
 *
 * Pure value object (no behaviour). XP-gain logic lives in
 * [com.astarworks.astera.stats.application.usecase.progression.AwardSkillXpUseCase].
 */
public data class SkillProgression(
    public val playerId: PlayerId,
    public val kind: SkillKind,
    public val xp: Long,
    public val level: Int,
) {
    init {
        require(xp >= 0L) { "SkillProgression.xp must be >= 0, got $xp (kind=$kind)" }
        require(level >= 1) { "SkillProgression.level must be >= 1, got $level (kind=$kind)" }
    }

    public companion object {
        /**
         * Convenience constructor for a fresh progression at level 1 / 0 xp.
         * The XP-curve registry is the source of truth for "what level does
         * 0 xp map to" — this helper assumes level 1, matching every published
         * curve.
         */
        public fun initial(playerId: PlayerId, kind: SkillKind): SkillProgression =
            SkillProgression(playerId, kind, xp = 0L, level = 1)
    }
}
