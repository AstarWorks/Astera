package com.astarworks.astera.economy.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerUnlockStore
import com.astarworks.astera.core.domain.advancement.AdvancementReward
import com.astarworks.astera.core.domain.event.AdvancementRewardGranted
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Makes a quest / advancement `grant_title` reward real: the title entitlement (`title:<id>`, the
 * same form the achievement and level rewards use) is stored and the player is told. Without this
 * subscriber the reward fan-out ([AdvancementRewardGranted]) had no consumer for titles, so a
 * finished quest chain paid its GOLD but silently dropped its title.
 *
 * The unlock store is idempotent (`grant` returns false for an already owned entitlement), so a
 * re-published event never double-announces.
 */
public class GrantTitleOnRewardGrantedListener(
    private val unlocks: () -> IPlayerUnlockStore?,
    private val messages: IMessageRenderer,
    private val players: IPlayerGateway,
    private val titleName: (String) -> MessageKey?,
    private val onTitleGranted: (PlayerId) -> Unit = {},
) {
    public fun handle(event: AdvancementRewardGranted) {
        val reward = event.reward as? AdvancementReward.GrantTitle ?: return
        val id = reward.titleId.value
        if (unlocks()?.grant(event.playerId, "title:$id") != true) return
        onTitleGranted(event.playerId)
        val shown = titleName(id)?.let { messages.render(event.playerId, it, emptyMap()) } ?: id
        players.sendMessage(event.playerId, messages.render(event.playerId, TITLE_CHAT, mapOf("title" to shown)))
    }

    public companion object {
        public val TITLE_CHAT: MessageKey = DepositOnAchievementUnlockedListener.TITLE_CHAT
    }
}
