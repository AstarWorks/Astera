package com.astarworks.astera.stats.application.usecase.level

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.ILevelRewardRepository
import com.astarworks.astera.stats.application.port.outbound.LevelRewardError
import com.astarworks.astera.stats.domain.level.LevelReward

public class GrantLevelRewardsUseCase(
    private val rewards: Map<Int, LevelReward>,
    private val repository: ILevelRewardRepository,
    private val players: IPlayerGateway,
    private val messages: IMessageRenderer,
    private val hud: IHudGateway,
    private val onGranted: (PlayerId, Int, LevelReward) -> Unit = { _, _, _ -> },
) {
    /** Reconcile all eligible levels, including claims interrupted after XP was committed. */
    public fun execute(playerId: PlayerId, level: Int): Result<Int, LevelRewardError> {
        var granted = 0
        for ((earnedLevel, reward) in rewards.toSortedMap()) {
            if (earnedLevel > level) break
            when (val claim = repository.apply(playerId, earnedLevel, reward)) {
                is Result.Failure -> return Result.failure(claim.error)
                is Result.Success -> if (claim.value) {
                    granted++
                    onGranted(playerId, earnedLevel, reward)
                    val args = mapOf("level" to earnedLevel.toString(), "star" to reward.star.toString(),
                        "gold" to reward.gold.toString(), "unlocks" to reward.unlocks.sorted().joinToString(", "))
                    players.sendMessage(playerId, messages.render(playerId, CHAT, args))
                    hud.showFor(playerId, HudLayout.Subtitle(
                        component = HudComponent.Text(TITLE, placeholders = args),
                        fadeInTicks = 10, stayTicks = 60, fadeOutTicks = 10,
                    ))
                }
            }
        }
        return Result.success(granted)
    }

    public companion object {
        public val CHAT: MessageKey = MessageKey("astera.progression.reward.chat")
        public val TITLE: MessageKey = MessageKey("astera.progression.reward.title")
    }
}
