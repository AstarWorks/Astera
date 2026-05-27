package com.astarworks.astera.match.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.event.PhaseChanged
import org.slf4j.LoggerFactory

/**
 * Per-phase HUD readout (Wave 5match-hud, ADR-0118 §4).
 *
 * Subscribes to [PhaseChanged] (Wave 5gamemode, ADR-0032) and pushes an
 * action-bar message to every participant of the match. The
 * `astera.hud.phase.<phase>` message key naming lets the renderer
 * substitute a per-phase string (ACTIVE → "Phase: Active",
 * SUDDEN_DEATH → "Phase: Sudden Death!", etc.) without the listener
 * embedding strings.
 *
 * # Why action bar
 *
 * Phase changes are infrequent (≤ 4 per match) and transient — a
 * subtitle would steal the screen mid-fight, a sidebar would replace
 * the score readout. Action bar is the right "ambient announcement"
 * surface.
 *
 * # Why broadcast to all participants
 *
 * Phase is a property of the match, not of a single player. A spectator
 * who joined mid-match still sees the action bar; the use case-level
 * spectator filter (Wave 6b) lives outside the listener.
 */
public class HudUpdateOnMatchPhaseChangedListener(
    private val matches: IMatchRepository,
    private val hudGateway: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PhaseChanged) {
        val match = matches.findById(event.matchId) ?: return
        val layout = HudLayout.ActionBar(
            component = HudComponent.Text(
                messageKey = messageKeyFor(event.to),
                color = colorFor(event.to),
            ),
            ttlTicks = ACTIONBAR_TTL_TICKS,
        )
        for (team in match.teams) {
            for (member in team.members) {
                showOrLog(member, layout, event.matchId.toString())
            }
        }
    }

    private fun messageKeyFor(phase: MatchPhase): MessageKey = MessageKey(
        "astera.hud.phase.${phase.name.lowercase().replace('_', '-')}",
    )

    private fun colorFor(phase: MatchPhase): HudColor = when (phase) {
        MatchPhase.WAITING -> HudColor.GRAY
        MatchPhase.COUNTDOWN -> HudColor.YELLOW
        MatchPhase.ACTIVE -> HudColor.GREEN
        MatchPhase.SUDDEN_DEATH -> HudColor.RED
        MatchPhase.ENDED -> HudColor.DARK_GRAY
    }

    private fun showOrLog(playerId: PlayerId, layout: HudLayout, matchKey: String) {
        when (val outcome = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "Phase HUD show failed for {} in match {}: {}",
                playerId,
                matchKey,
                outcome.error,
            )
        }
    }

    public companion object {
        public const val ACTIONBAR_TTL_TICKS: Int = 80
    }
}
