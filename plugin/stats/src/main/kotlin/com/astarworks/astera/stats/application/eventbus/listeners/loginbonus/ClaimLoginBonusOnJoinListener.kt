package com.astarworks.astera.stats.application.eventbus.listeners.loginbonus

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginBonusService
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.loginbonus.LoginBonusClaim
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Clock
import java.time.LocalDate

/**
 * Lobby only (ADR-0210): when a player joins, collect today's login bonus a moment after the join
 * messages (and the last-match recap) and tell them what they got: one chat line plus the
 * title/subtitle toast path the achievements already use. Nothing is shown when today's bonus was
 * already claimed, so rejoining stays quiet.
 */
public class ClaimLoginBonusOnJoinListener(
    private val loginBonus: ILoginBonusService,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
    private val hudGateway: IHudGateway,
    private val scheduler: IScheduler,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    public fun handle(event: PlayerJoinedServer) {
        scheduler.run(ExecutionScope.Entity(event.playerId.uuid), ticks(CLAIM_DELAY_TICKS)) { claim(event.playerId) }
    }

    /** Claims for [playerId] now; also the entry point of the manual `/astera daily claim`. */
    public fun claim(playerId: PlayerId): Boolean {
        val result = loginBonus.claimDaily(playerId, LocalDate.now(clock))
        if (result !is Result.Success) return false
        announce(playerId, result.value)
        return true
    }

    private fun announce(playerId: PlayerId, claim: LoginBonusClaim) {
        val params = mapOf(
            "day" to claim.day.toString(),
            "streak" to claim.streakDays.toString(),
            "amount" to claim.reward.amount.toString(),
            "currency" to claim.reward.currency.name,
        )
        playerGateway.sendMessage(playerId, messages.render(playerId, CHAT, params))
        hudGateway.showFor(
            playerId,
            HudLayout.Subtitle(
                component = HudComponent.Composite(
                    parts = listOf(
                        HudComponent.Text(messageKey = TOAST_TITLE, color = HudColor.GOLD, placeholders = params),
                        HudComponent.Text(messageKey = TOAST_BODY, color = HudColor.YELLOW, placeholders = params),
                    ),
                    spacing = 3,
                ),
                fadeInTicks = 10,
                stayTicks = 60,
                fadeOutTicks = 10,
            ),
        )
    }

    public companion object {
        public const val CLAIM_DELAY_TICKS: Long = 100L
        public val CHAT: MessageKey = MessageKey("astera.loginbonus.claimed.chat")
        public val TOAST_TITLE: MessageKey = MessageKey("astera.loginbonus.claimed.toast-title")
        public val TOAST_BODY: MessageKey = MessageKey("astera.loginbonus.claimed.toast-body")
    }
}
