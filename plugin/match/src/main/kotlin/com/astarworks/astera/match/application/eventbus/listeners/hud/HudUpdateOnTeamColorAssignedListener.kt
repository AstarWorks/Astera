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
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * Per-player tab-list team-color readout (Wave 5match-hud, ADR-0118 §6).
 *
 * # Why MatchStarted instead of a "team-assigned" event
 *
 * Teams are populated as part of the [Match] aggregate when the match
 * forms — there is no separate `PlayerJoinedTeam` / `TeamColorAssigned`
 * event in the codebase. Round 10 audit confirmed this: the assignment
 * happens inside `JoinMatchUseCase.join` (Match.addParticipant) before
 * `MatchStarted` fires, so the listener that wants "set tab-list color
 * for every participant" simply subscribes to `MatchStarted` and reads
 * the team membership off the snapshot.
 *
 * # What this listener actually does
 *
 * Pushes a [HudLayout.TabList] for every participant with a per-team
 * color carried in the [HudComponent.Text.color] field. The Wave 6b
 * `PaperHudGateway` materialises the tab-list color by calling
 * `Player.playerListName(Component.text(name).color(...))`; Phase 1
 * in-memory gateway records the call only.
 *
 * # Why TabList, not Sidebar
 *
 * The sidebar is owned by `UpdateMatchSidebarListener` (Wave 5complete,
 * score readout). The tab list is the canonical surface for "who is on
 * which team" — pressing TAB shows all players coloured by team.
 */
public class HudUpdateOnTeamColorAssignedListener(
    private val matches: IMatchRepository,
    private val hudGateway: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: MatchStarted) {
        val match = matches.findById(event.matchId) ?: return
        renderTabListFor(match)
    }

    private fun renderTabListFor(match: Match) {
        match.teams.forEachIndexed { index, team ->
            val color = teamColor(index)
            val header = HudComponent.Text(
                messageKey = MessageKey("astera.hud.tablist.team-header"),
                color = color,
            )
            val footer = HudComponent.Text(
                messageKey = MessageKey("astera.hud.tablist.team-footer"),
                color = color,
            )
            val layout = HudLayout.TabList(header = header, footer = footer)
            for (member in team.members) {
                showOrLog(member, layout, match.id.toString())
            }
        }
    }

    private fun showOrLog(playerId: PlayerId, layout: HudLayout, matchKey: String) {
        when (val outcome = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "Tab-list HUD show failed for {} in match {}: {}",
                playerId,
                matchKey,
                outcome.error,
            )
        }
    }

    /**
     * Mirror of `UpdateMatchSidebarListener.teamColor` palette so the
     * sidebar and tab-list agree on which colour each team gets. Keep
     * the two lists in sync if either side changes.
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
