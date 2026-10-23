package com.astarworks.astera.economy.application.config

import com.astarworks.astera.core.application.config.ConfigError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.economy.domain.reward.MatchRewardTable
import com.astarworks.astera.economy.domain.reward.RewardAmounts
import com.astarworks.astera.economy.domain.reward.RewardOverrides
import com.astarworks.astera.economy.domain.reward.RewardSet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/balance/match-rewards.yaml` (F3-4).
 *
 * ```yaml
 * default:                      # every row is required here
 *   participation: { gold: 10, xp: 25 }
 *   win:  { gold: 120, xp: 150 }
 *   ...
 * modes:                        # optional per-mode overrides, any subset of rows
 *   annihilation:
 *     win: { gold: 180, xp: 200 }
 * ```
 *
 * Each amount row has `gold` and `xp`, all default 0, all `>= 0`.
 */
@Serializable
public data class MatchRewardsYamlConfig(
    public val default: SetBlock,
    public val modes: Map<String, OverridesBlock> = emptyMap(),
) {
    @Serializable
    public data class Amounts(
        public val gold: Long = 0L,
        public val xp: Long = 0L,
    )

    @Serializable
    public data class SetBlock(
        public val participation: Amounts,
        public val win: Amounts,
        public val lose: Amounts,
        public val draw: Amounts,
        @SerialName("per_kill") public val perKill: Amounts,
        @SerialName("per_assist") public val perAssist: Amounts,
        @SerialName("per_objective") public val perObjective: Amounts,
        public val mvp: Amounts,
    )

    @Serializable
    public data class OverridesBlock(
        public val participation: Amounts? = null,
        public val win: Amounts? = null,
        public val lose: Amounts? = null,
        public val draw: Amounts? = null,
        @SerialName("per_kill") public val perKill: Amounts? = null,
        @SerialName("per_assist") public val perAssist: Amounts? = null,
        @SerialName("per_objective") public val perObjective: Amounts? = null,
        public val mvp: Amounts? = null,
    )

    public fun toDomain(): Result<MatchRewardTable, ConfigError> {
        fun check(field: String, a: Amounts): ConfigError? {
            if (a.gold < 0 || a.xp < 0) {
                return ConfigError.OutOfRange(field = field, rawValue = a.toString(), constraint = "amounts must be >= 0")
            }
            return null
        }
        val rows = listOf(
            "default.participation" to default.participation, "default.win" to default.win,
            "default.lose" to default.lose, "default.draw" to default.draw,
            "default.per_kill" to default.perKill, "default.per_assist" to default.perAssist,
            "default.per_objective" to default.perObjective, "default.mvp" to default.mvp,
        ) + modes.flatMap { (mode, o) ->
            listOf(
                "modes.$mode.participation" to o.participation, "modes.$mode.win" to o.win,
                "modes.$mode.lose" to o.lose, "modes.$mode.draw" to o.draw,
                "modes.$mode.per_kill" to o.perKill, "modes.$mode.per_assist" to o.perAssist,
                "modes.$mode.per_objective" to o.perObjective, "modes.$mode.mvp" to o.mvp,
            )
        }
        for ((field, amounts) in rows) {
            if (amounts != null) check(field, amounts)?.let { return Result.failure(it) }
        }
        return Result.success(
            MatchRewardTable(
                default = RewardSet(
                    participation = default.participation.toDomain(),
                    win = default.win.toDomain(),
                    lose = default.lose.toDomain(),
                    draw = default.draw.toDomain(),
                    perKill = default.perKill.toDomain(),
                    perAssist = default.perAssist.toDomain(),
                    perObjective = default.perObjective.toDomain(),
                    mvp = default.mvp.toDomain(),
                ),
                modes = modes.mapValues { (_, o) ->
                    RewardOverrides(
                        participation = o.participation?.toDomain(),
                        win = o.win?.toDomain(),
                        lose = o.lose?.toDomain(),
                        draw = o.draw?.toDomain(),
                        perKill = o.perKill?.toDomain(),
                        perAssist = o.perAssist?.toDomain(),
                        perObjective = o.perObjective?.toDomain(),
                        mvp = o.mvp?.toDomain(),
                    )
                },
            ),
        )
    }

    private fun Amounts.toDomain(): RewardAmounts = RewardAmounts(gold = gold, xp = xp)
}
