package com.astarworks.astera.match.application.eventbus.listeners.killfeed

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.service.death.KillWeaponLabel
import com.astarworks.astera.match.application.service.hud.TeamColors
import org.slf4j.LoggerFactory

/**
 * Kill feed (ADR-0206): on [PlayerKilled] of a match participant, every member of
 * the match gets one chat line naming killer, victim and weapon, each name in its
 * team colour and every word in the reader's language; the killer additionally
 * gets a subtitle with the victim's name. The victim's own screen (title, killer,
 * weapon, respawn countdown) belongs to the death-respawn coordinator, so the old
 * anonymous "you were killed" action-bar toast is gone.
 *
 * Chat, not the action bar: the action bar is the toast channel (ADR-0177) and a
 * feed has to stay readable while several kills land within a few seconds.
 *
 * No match context = drop (admin `/kill` in the lobby, PvE).
 */
public class KillfeedOnPlayerKilledListener(
    private val matches: IMatchRepository,
    private val hudGateway: IHudGateway,
    private val messages: IMessageRenderer,
    private val players: IPlayerGateway,
    private val items: IItemRegistry? = null,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerKilled) {
        val match = matches.findMatchOf(event.victim) ?: return
        val killer = event.killer?.takeIf { it != event.victim }

        for (viewer in match.teams.flatMap { it.members }) {
            players.sendMessage(viewer, line(viewer, match, event, killer))
        }

        if (killer != null) {
            showOrLog(
                killer,
                HudLayout.Subtitle(
                    component = HudComponent.Text(
                        messageKey = MessageKey("astera.killfeed.killer"),
                        color = HudColor.GOLD,
                        placeholders = mapOf("victim" to nameOf(event.victim)),
                    ),
                ),
            )
        }
    }

    private fun line(viewer: PlayerId, match: Match, event: PlayerKilled, killer: PlayerId?): String {
        val weapon = KillWeaponLabel.of(viewer, event.source, items, messages)
        val victimTeam = match.teams.firstOrNull { event.victim in it.members }
        val placeholders = mutableMapOf(
            "victim" to nameOf(event.victim),
            "victim-color" to TeamColors.nameOf(victimTeam, viewer, messages),
        )
        val key = if (killer == null) {
            "astera.killfeed.chat.death"
        } else {
            placeholders["killer"] = nameOf(killer)
            placeholders["killer-color"] =
                TeamColors.nameOf(match.teams.firstOrNull { killer in it.members }, viewer, messages)
            if (weapon != null) {
                placeholders["weapon"] = weapon
                "astera.killfeed.chat.kill-weapon"
            } else {
                "astera.killfeed.chat.kill"
            }
        }
        return messages.render(viewer, MessageKey(key), placeholders)
    }

    private fun nameOf(player: PlayerId): String = players.nameOf(player) ?: player.uuid.toString().take(8)

    private fun showOrLog(playerId: PlayerId, layout: HudLayout) {
        when (val outcome = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug("Killfeed HUD failed for {}: {}", playerId, outcome.error)
        }
    }
}
