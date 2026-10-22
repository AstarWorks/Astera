package com.astarworks.astera.economy.domain.reward

import com.astarworks.astera.core.domain.match.result.MatchOutcome
import com.astarworks.astera.core.domain.match.result.MatchRewardResult
import com.astarworks.astera.core.domain.match.result.PlayerMatchLine
import com.astarworks.astera.core.domain.match.result.RewardLine
import com.astarworks.astera.core.domain.match.result.RewardLineKind

/** STAR, GOLD and XP of one reward row. */
public data class RewardAmounts(val star: Long = 0L, val gold: Long = 0L, val xp: Long = 0L) {
    init {
        require(star >= 0 && gold >= 0 && xp >= 0) { "reward amounts must be >= 0" }
    }

    public operator fun times(count: Int): RewardAmounts = RewardAmounts(star * count, gold * count, xp * count)

    public val isZero: Boolean get() = star == 0L && gold == 0L && xp == 0L

    public companion object {
        public val ZERO: RewardAmounts = RewardAmounts()
    }
}

/**
 * Every row of the reward formula for one game mode.
 *
 * `participation` is paid to everyone who was in the match; exactly one of
 * `win` / `lose` / `draw` is paid on top by the player's outcome; the `per*`
 * rows are multiplied by the player's own counters; `mvp` goes to the one MVP.
 */
public data class RewardSet(
    val participation: RewardAmounts,
    val win: RewardAmounts,
    val lose: RewardAmounts,
    val draw: RewardAmounts,
    val perKill: RewardAmounts,
    val perAssist: RewardAmounts,
    val perObjective: RewardAmounts,
    val mvp: RewardAmounts,
)

/** A mode's overrides of the default [RewardSet]; a `null` row falls back to the default. */
public data class RewardOverrides(
    val participation: RewardAmounts? = null,
    val win: RewardAmounts? = null,
    val lose: RewardAmounts? = null,
    val draw: RewardAmounts? = null,
    val perKill: RewardAmounts? = null,
    val perAssist: RewardAmounts? = null,
    val perObjective: RewardAmounts? = null,
    val mvp: RewardAmounts? = null,
) {
    internal fun applyTo(base: RewardSet): RewardSet = RewardSet(
        participation = participation ?: base.participation,
        win = win ?: base.win,
        lose = lose ?: base.lose,
        draw = draw ?: base.draw,
        perKill = perKill ?: base.perKill,
        perAssist = perAssist ?: base.perAssist,
        perObjective = perObjective ?: base.perObjective,
        mvp = mvp ?: base.mvp,
    )
}

/** `content/balance/match-rewards.yaml` in domain form. */
public data class MatchRewardTable(
    val default: RewardSet,
    val modes: Map<String, RewardOverrides> = emptyMap(),
) {
    /** The rows that apply to [modeKey] (the default for an unknown or blank mode). */
    public fun forMode(modeKey: String): RewardSet =
        modes[modeKey]?.applyTo(default) ?: default

    public companion object {
        /** Used when the YAML is missing or broken: the pre-F3-4 behaviour, 100 STAR to winners. */
        public val FALLBACK: MatchRewardTable = MatchRewardTable(
            default = RewardSet(
                participation = RewardAmounts.ZERO,
                win = RewardAmounts(star = 100L),
                lose = RewardAmounts.ZERO,
                draw = RewardAmounts.ZERO,
                perKill = RewardAmounts.ZERO,
                perAssist = RewardAmounts.ZERO,
                perObjective = RewardAmounts.ZERO,
                mvp = RewardAmounts.ZERO,
            ),
        )
    }
}

/**
 * Pure reward arithmetic: what [line] earned in a match of [modeKey] under
 * [table]. Level fields of the result are left for the caller to fill in
 * after the XP is granted.
 */
public object MatchRewardCalculator {

    public fun calculate(
        table: MatchRewardTable,
        modeKey: String,
        outcome: MatchOutcome,
        line: PlayerMatchLine,
        isMvp: Boolean,
    ): MatchRewardResult {
        val set = table.forMode(modeKey)
        val rows = buildList {
            add(row(RewardLineKind.PARTICIPATION, 1, set.participation))
            add(
                row(
                    RewardLineKind.RESULT, 1,
                    when (outcome) {
                        MatchOutcome.WIN -> set.win
                        MatchOutcome.LOSE -> set.lose
                        MatchOutcome.DRAW -> set.draw
                    },
                ),
            )
            add(row(RewardLineKind.KILLS, line.kills, set.perKill * line.kills))
            add(row(RewardLineKind.ASSISTS, line.assists, set.perAssist * line.assists))
            add(row(RewardLineKind.OBJECTIVES, line.objective, set.perObjective * line.objective))
            if (isMvp) add(row(RewardLineKind.MVP, 1, set.mvp))
        }
        return MatchRewardResult(outcome = outcome, lines = rows.filter { it.star > 0 || it.gold > 0 || it.xp > 0 })
    }

    private fun row(kind: RewardLineKind, count: Int, amounts: RewardAmounts): RewardLine =
        RewardLine(kind, count, amounts.star, amounts.gold, amounts.xp)
}
