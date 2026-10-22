package com.astarworks.astera.match.application.eventbus.listeners.killfeed

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * On [PlayerKilled], surface a kill notification through three HUD
 * layers (Wave 5complete, ADR-0117 §Axis 4):
 *
 *   - **Killer's screen**: subtitle ("You eliminated <victim>").
 *   - **Victim's screen**: action bar ("Killed by <killer>").
 *   - **All match participants**: a chat-broadcast equivalent via the HUD
 *     gateway's `ActionBar` slot (a richer per-match killfeed channel is
 *     deferred to Wave 6b when the Paper renderer can pin entries to a
 *     scrolling sidebar). For Phase 1 PoC the action bar is sufficient
 *     to confirm the wiring.
 *
 * # Why HUD, not chat broadcast
 *
 * The killfeed is read-only ambient noise; routing it through the chat
 * broadcaster would compete with player-typed chat. The Wave 5j HUD
 * gateway already supports the three surfaces (Subtitle / ActionBar /
 * Sidebar) so reusing the same port keeps the rendering policy in one
 * place (Wave 6b `PaperHudGateway`).
 *
 * # No match context = drop
 *
 * If the victim is not in any active match (admin `/kill` in lobby, PvE
 * world, etc.) the listener returns without rendering anything. PvE
 * kills get their own audit listener.
 */
public class KillfeedOnPlayerKilledListener(
    private val matches: IMatchRepository,
    private val hudGateway: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerKilled) {
        val victimMatch = matches.findMatchOf(event.victim) ?: return

        // Victim — actionbar "you were killed by <killer>".
        showOrLog(
            playerId = event.victim,
            layout = HudLayout.ActionBar(
                component = HudComponent.Text(
                    messageKey = MessageKey("astera.killfeed.victim"),
                    color = HudColor.RED,
                ),
                ttlTicks = ACTIONBAR_TTL_TICKS,
                priority = 1,
            ),
            context = "victim",
        )

        // Killer — subtitle "you eliminated <victim>" (skip self-kills).
        val killer = event.killer
        if (killer != null && killer != event.victim) {
            showOrLog(
                playerId = killer,
                layout = HudLayout.Subtitle(
                    component = HudComponent.Text(
                        messageKey = MessageKey("astera.killfeed.killer"),
                        color = HudColor.GOLD,
                    ),
                ),
                context = "killer",
            )
        }

        // Match-wide killfeed entry — every participant sees a brief
        // actionbar. Cheap enough at Phase 1 player counts (≤ 32) that
        // we don't paginate.
        val killfeedLine = HudLayout.ActionBar(
            component = HudComponent.Text(
                messageKey = MessageKey("astera.killfeed.entry"),
                color = HudColor.YELLOW,
            ),
            ttlTicks = ACTIONBAR_TTL_TICKS,
            priority = 1,
        )
        for (team in victimMatch.teams) {
            for (member in team.members) {
                if (member == event.victim || member == killer) continue
                showOrLog(member, killfeedLine, context = "broadcast")
            }
        }
    }

    private fun showOrLog(playerId: com.astarworks.astera.core.domain.player.PlayerId, layout: HudLayout, context: String) {
        when (val outcome = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "Killfeed HUD ({}) failed for {}: {}",
                context,
                playerId,
                outcome.error,
            )
        }
    }

    public companion object {
        public const val ACTIONBAR_TTL_TICKS: Int = 60
    }
}
