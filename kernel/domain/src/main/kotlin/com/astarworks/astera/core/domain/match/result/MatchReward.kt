package com.astarworks.astera.core.domain.match.result

/** Why a row of the reward breakdown exists. */
public enum class RewardLineKind { PARTICIPATION, RESULT, KILLS, ASSISTS, OBJECTIVES, MVP }

/**
 * One row of a player's reward breakdown: [count] of something (kills,
 * assists; 1 for the flat rows) worth [gold] GOLD and [xp] XP in
 * total (already multiplied).
 */
public data class RewardLine(
    val kind: RewardLineKind,
    val count: Int,
    val gold: Long,
    val xp: Long,
)

/**
 * What one player was paid for one match, plus where it left their level.
 * [levelBefore] / [levelAfter] / [xpIntoLevel] / [xpForNextLevel] are `0` when
 * the stats plugin (XP) is not present.
 */
public data class MatchRewardResult(
    val outcome: MatchOutcome,
    val lines: List<RewardLine>,
    val levelBefore: Int = 0,
    val levelAfter: Int = 0,
    val xpIntoLevel: Long = 0L,
    val xpForNextLevel: Long = 0L,
) {
    val gold: Long get() = lines.sumOf { it.gold }
    val xp: Long get() = lines.sumOf { it.xp }
    val leveledUp: Boolean get() = levelAfter > levelBefore && levelBefore > 0
}
