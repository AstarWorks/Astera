package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.stats.application.port.outbound.INormaRegistry
import com.astarworks.astera.stats.application.usecase.level.GrantXpUseCase
import com.astarworks.astera.stats.domain.event.MissionCompleted

/**
 * On [MissionCompleted] (ADR-0210): grant the reward's XP and tell the player.
 *
 * The currency part of the reward is paid by the economy plugin's `DepositOnMissionCompletedListener`;
 * this listener owns what the stats plugin can pay (XP, so a mission also moves the level) and the
 * announcement (chat line + the title/subtitle toast the achievements use). It replaces the old
 * reward-bundle enqueue, which nothing ever consumed.
 */
public class PayOutMissionCompletedListener(
    private val grantXp: GrantXpUseCase,
    private val normas: INormaRegistry,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
    private val hudGateway: IHudGateway,
) {
    public fun handle(event: MissionCompleted) {
        val reward = event.reward
        if (reward.xp > 0L) grantXp.execute(event.playerId, reward.xp, XpSource.MissionReward)

        val norma = normas.find(event.normaId)
        val name = norma?.let { messages.render(event.playerId, it.displayKey, emptyMap()) } ?: event.normaId.value
        playerGateway.sendMessage(
            event.playerId,
            messages.render(event.playerId, COMPLETED, mapOf("norma" to name)),
        )
        val currency = reward.currencyId?.takeIf { it.isNotBlank() && reward.currencyAmount > 0L }
        playerGateway.sendMessage(
            event.playerId,
            messages.render(
                event.playerId,
                REWARD,
                mapOf(
                    "amount" to (if (currency != null) reward.currencyAmount else 0L).toString(),
                    "currency" to (currency ?: ""),
                    "xp" to reward.xp.toString(),
                ),
            ),
        )
        hudGateway.showFor(
            event.playerId,
            HudLayout.Subtitle(
                component = HudComponent.Composite(
                    parts = listOf(
                        HudComponent.Text(messageKey = TOAST_TITLE, color = HudColor.GOLD),
                        HudComponent.Text(messageKey = norma?.displayKey ?: TOAST_TITLE, color = HudColor.WHITE),
                    ),
                    spacing = 3,
                ),
                fadeInTicks = 10,
                stayTicks = 50,
                fadeOutTicks = 10,
            ),
        )
    }

    public companion object {
        public val COMPLETED: MessageKey = MessageKey("astera.norma.completed")
        public val REWARD: MessageKey = MessageKey("astera.norma.reward.delivered")
        public val TOAST_TITLE: MessageKey = MessageKey("astera.norma.toast")
    }
}
