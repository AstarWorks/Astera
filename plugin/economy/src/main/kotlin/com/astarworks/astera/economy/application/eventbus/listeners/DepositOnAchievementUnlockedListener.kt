package com.astarworks.astera.economy.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerUnlockStore
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.economy.application.usecase.currency.DepositUseCase
import com.astarworks.astera.stats.application.port.outbound.IAchievementRepository
import com.astarworks.astera.stats.domain.event.AchievementUnlocked
import org.slf4j.LoggerFactory

/**
 * Pays the catalogued `reward_gold` of an achievement (content/achievements) when it unlocks.
 *
 * `UnlockAchievementUseCase` publishes [AchievementUnlocked] exactly once per (player, achievement),
 * so the deposit is paid exactly once. Stats owns the catalog; this listener (economy depends on
 * stats, never the reverse, ADR-0022) turns the number into a wallet movement.
 */
public class DepositOnAchievementUnlockedListener(
    private val deposit: DepositUseCase,
    private val achievements: () -> IAchievementRepository?,
    private val messages: IMessageRenderer,
    private val players: IPlayerGateway,
    private val titles: () -> IPlayerUnlockStore? = { null },
    private val onTitleGranted: (PlayerId) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public suspend fun handle(event: AchievementUnlocked) {
        val achievement = achievements()?.find(event.achievementId) ?: return
        achievement.rewardTitle?.let { title ->
            if (titles()?.grant(event.playerId, "title:$title") == true) {
                onTitleGranted(event.playerId)
                players.sendMessage(event.playerId, messages.render(event.playerId, TITLE_CHAT, mapOf("title" to title)))
            }
        }
        val gold = achievement.rewardGold
        if (gold <= 0L) return
        deposit.execute(event.playerId, Currency.GOLD, gold, CurrencyChangeReason.AchievementReward)
            .onSuccess {
                players.sendMessage(
                    event.playerId,
                    messages.render(event.playerId, CHAT, mapOf("gold" to gold.toString())),
                )
            }
            .onFailure { log.warn("Achievement reward deposit failed for {} ({}): {}", event.playerId, event.achievementId, it) }
    }

    public companion object {
        public val CHAT: MessageKey = MessageKey("astera.economy.achievement-reward.chat")
        public val TITLE_CHAT: MessageKey = MessageKey("astera.economy.achievement-reward.title-chat")
    }
}
