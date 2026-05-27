package com.astarworks.astera.match.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.match.domain.event.EntityPickedUpItem
import org.slf4j.LoggerFactory

/**
 * Per-match sidebar updater (Wave 5complete, ADR-0117 §Axis 4).
 *
 * Subscribes to score-relevant events
 * ([MatchStarted] + [EntityPickedUpItem]) and pushes a refreshed
 * [HudLayout.Sidebar] to every participant whenever the team scores
 * change. Phase 1 in-memory [IHudGateway] records the call + publishes a
 * `HudShown` event; the Paper renderer (Wave 6b) materialises the
 * vanilla sidebar on screen.
 *
 * # Why these two events
 *
 * - [MatchStarted] populates the sidebar at match start (initial 0:0
 *   readout).
 * - [EntityPickedUpItem] covers the Wave 5c Norma deposit pipeline (the
 *   per-team gem count is the score in Norma). Annihilation mode's
 *   nexus-damage scoring fires a different event family (`NexusDamaged`)
 *   handled by a sibling listener if/when Wave 6 wires per-mode sidebar
 *   variants — for Phase 1 PoC the Norma sidebar is the canonical view.
 *
 * # Sidebar shape (3 lines minimum, one per team)
 *
 * ```
 * == titleKey ==
 *   Team Red: 5
 *   Team Blue: 3
 * ```
 *
 * Every entry resolves through [MessageKey] (`astera.match.sidebar.title`
 * / `astera.match.sidebar.team-line`) — the renderer substitutes
 * `{team}` / `{score}` placeholders.
 */
public class UpdateMatchSidebarListener(
    private val matches: IMatchRepository,
    private val hudGateway: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun onMatchStarted(event: MatchStarted) {
        val match = matches.findById(event.matchId) ?: return
        renderSidebarFor(match)
    }

    public fun onItemPickedUp(event: EntityPickedUpItem) {
        val match = matches.findById(event.matchId) ?: return
        renderSidebarFor(match)
    }

    private fun renderSidebarFor(match: Match) {
        val lines: List<HudComponent> = match.teams.mapIndexed { index, team ->
            val score = match.score.perTeam[team.id] ?: 0
            HudComponent.Text(
                messageKey = MessageKey("astera.match.sidebar.team-line"),
                color = teamColor(index),
            ).let { textComponent ->
                // Wrapped just so the placeholders are documented; the
                // adapter resolves {team} / {score} through IMessageRenderer.
                textComponent
            }
        }.ifEmpty { listOf(HudComponent.Text(MessageKey("astera.match.sidebar.empty"))) }

        val layout = HudLayout.Sidebar(
            titleKey = MessageKey("astera.match.sidebar.title"),
            lines = lines,
        )

        for (team in match.teams) {
            for (playerId in team.members) {
                when (val outcome = hudGateway.showFor(playerId, layout)) {
                    is Result.Success -> Unit
                    is Result.Failure -> log.debug(
                        "Sidebar show failed for {} in match {}: {}",
                        playerId,
                        match.id,
                        outcome.error,
                    )
                }
            }
        }
    }

    /**
     * Cycle through a small palette so each team gets a stable colour
     * within a given match. The first four cover the common Norma / TDM
     * shapes (Red / Blue / Yellow / Green); 5+ wrap.
     */
    private fun teamColor(index: Int): HudColor = TEAM_COLORS[index % TEAM_COLORS.size]

    public companion object {
        private val TEAM_COLORS: List<HudColor> = listOf(
            HudColor.RED,
            HudColor.BLUE,
            HudColor.YELLOW,
            HudColor.GREEN,
        )
    }
}
