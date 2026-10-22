package com.astarworks.astera.stats.application.eventbus.listeners.quest

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IQuestChainRegistry
import com.astarworks.astera.core.domain.event.QuestStageCompleted
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Tells the player a quest stage (or the whole chain) was cleared (ADR-0210): a chat line naming
 * the stage plus the title/subtitle toast the achievements use. Stage rewards are paid by the quest
 * service itself.
 */
public class NotifyOnQuestStageCompletedListener(
    private val chains: IQuestChainRegistry,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
    private val hudGateway: IHudGateway,
) {
    public fun handle(event: QuestStageCompleted) {
        val chain = chains.find(event.chainId)
        val stage = chain?.stage(event.completedStageId)
        val finished = event.nextStageId == null
        val headline = MessageKey(if (finished) "astera.quest.progress.chain-completed" else "astera.quest.progress.stage-completed")
        playerGateway.sendMessage(event.playerId, messages.render(event.playerId, headline, emptyMap()))
        if (chain != null) {
            playerGateway.sendMessage(
                event.playerId,
                messages.render(
                    event.playerId,
                    MessageKey("astera.quest.progress.stage-line"),
                    mapOf(
                        "quest" to messages.render(event.playerId, chain.titleKey, emptyMap()),
                        "stage" to (stage?.let { messages.render(event.playerId, it.titleKey, emptyMap()) } ?: event.completedStageId.value),
                    ),
                ),
            )
        }
        hudGateway.showFor(
            event.playerId,
            HudLayout.Subtitle(
                component = HudComponent.Composite(
                    parts = listOf(
                        HudComponent.Text(messageKey = headline, color = HudColor.GOLD),
                        HudComponent.Text(messageKey = stage?.titleKey ?: headline, color = HudColor.WHITE),
                    ),
                    spacing = 3,
                ),
                fadeInTicks = 10,
                stayTicks = 60,
                fadeOutTicks = 10,
            ),
        )
    }
}
