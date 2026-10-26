package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.application.port.outbound.IPlayerLabelGateway
import com.astarworks.astera.core.domain.hud.PlayerLabel
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.Team
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.RenderType
import org.bukkit.scoreboard.Scoreboard
import java.util.UUID

/**
 * Paper [IPlayerLabelGateway] (ADR-0206): the labels live on the **viewer's own**
 * scoreboard (the sidebar already gives every viewer a private one), so one viewer's
 * styling never leaks to another.
 *
 *  - one scoreboard team per group (`ast_np_<group>`): the team colour colours the
 *    nameplate and the tab-list row, and vanilla sorts tab rows by team name, so a
 *    team's players are listed together;
 *  - one `BELOW_NAME` dummy objective (`ast_np_hp`) whose per-player score is the HP:
 *    "134 ❤" under the nameplate. Exact HP, not the half-heart mirror of ADR-0149.
 *
 * Does nothing for a viewer still on the shared main scoreboard (styling it would
 * restyle everyone who has not got a private board yet); the match service re-applies
 * on its poll, so the labels land as soon as the sidebar gave the viewer a board.
 * The last applied state is remembered per viewer, so an unchanged call is free and
 * a swapped scoreboard is detected and re-styled.
 *
 * Concealed labels (the opposing team, ADR-0206 amendment) get the name-tag visibility
 * `NEVER` (so no nameplate through walls), no HP row and are unlisted from the viewer's tab
 * list (`Player.unlistPlayer`; the entity itself stays visible in the world).
 *
 * Main thread only (scoreboard mutation).
 */
public class PaperPlayerLabelGateway(
    private val server: Server,
) : IPlayerLabelGateway {

    private data class Applied(val board: Scoreboard, val names: Map<String, PlayerLabel>)

    private val applied: MutableMap<UUID, Applied> = mutableMapOf()

    override fun apply(viewer: PlayerId, labels: Map<PlayerId, PlayerLabel>) {
        val player = server.getPlayer(viewer.uuid) ?: run {
            applied.remove(viewer.uuid)
            return
        }
        val board = player.scoreboard
        if (board === server.scoreboardManager?.mainScoreboard) return
        val names = labels.mapNotNull { (id, label) -> server.getPlayer(id.uuid)?.name?.let { it to label } }.toMap()
        if (names.isEmpty()) {
            clear(viewer)
            return
        }
        // Tab rows are server-driven (a joining or reconnecting player is re-listed for
        // everyone), so enforce them on every call, before the unchanged-input shortcut.
        syncTabListing(player, names)
        val previous = applied[viewer.uuid]
        if (previous != null && previous.board === board && previous.names == names) return
        val stale = if (previous != null && previous.board === board) previous.names else emptyMap()

        syncTeams(board, names)
        syncHp(board, names, stale)
        applied[viewer.uuid] = Applied(board, names)
    }

    override fun clear(viewer: PlayerId) {
        val previous = applied.remove(viewer.uuid) ?: return
        server.getPlayer(viewer.uuid)?.let { player ->
            for (name in previous.names.keys) server.getPlayerExact(name)?.let { player.listPlayer(it) }
        }
        wipe(previous.board)
    }

    private fun syncTeams(board: Scoreboard, names: Map<String, PlayerLabel>) {
        val byGroup = names.entries.groupBy({ it.value.group }, { it.key })
        for ((group, members) in byGroup) {
            val color = names.getValue(members.first()).colorName
            val team = board.getTeam(teamName(group)) ?: board.registerNewTeam(teamName(group))
            team.color(namedColor(color))
            team.setOption(
                Team.Option.NAME_TAG_VISIBILITY,
                if (members.all { names.getValue(it).concealed }) Team.OptionStatus.NEVER else Team.OptionStatus.ALWAYS,
            )
            for (entry in team.entries - members.toSet()) team.removeEntry(entry)
            for (member in members) if (!team.hasEntry(member)) team.addEntry(member)
        }
        val wanted = byGroup.keys.map(::teamName).toSet()
        for (team in board.teams) if (team.name.startsWith(TEAM_PREFIX) && team.name !in wanted) team.unregister()
    }

    /** Concealed players get no row in the viewer's tab list; everyone else is listed. */
    private fun syncTabListing(viewer: Player, names: Map<String, PlayerLabel>) {
        for ((name, label) in names) {
            val subject = server.getPlayerExact(name) ?: continue
            if (subject.uniqueId == viewer.uniqueId) continue
            if (label.concealed) {
                if (viewer.isListed(subject)) viewer.unlistPlayer(subject)
            } else if (!viewer.isListed(subject)) {
                viewer.listPlayer(subject)
            }
        }
    }

    private fun syncHp(board: Scoreboard, names: Map<String, PlayerLabel>, stale: Map<String, PlayerLabel>) {
        val withHp = names.filterValues { it.hp != null && !it.concealed }
        if (withHp.isEmpty()) {
            board.getObjective(HP_OBJECTIVE)?.unregister()
            return
        }
        val objective = board.getObjective(HP_OBJECTIVE)
            ?: board.registerNewObjective(
                HP_OBJECTIVE,
                Criteria.DUMMY,
                Component.text("❤", NamedTextColor.RED),
                RenderType.INTEGER,
            ).also { it.displaySlot = DisplaySlot.BELOW_NAME }
        for ((name, label) in withHp) objective.getScore(name).score = label.hp ?: 0
        for (gone in stale.keys - withHp.keys) board.resetScores(gone)
    }

    private fun wipe(board: Scoreboard) {
        for (team in board.teams.toList()) if (team.name.startsWith(TEAM_PREFIX)) team.unregister()
        board.getObjective(HP_OBJECTIVE)?.unregister()
    }

    private fun namedColor(name: String): NamedTextColor =
        NamedTextColor.NAMES.value(name.lowercase()) ?: NamedTextColor.WHITE

    private fun teamName(group: String): String = (TEAM_PREFIX + group).take(MAX_TEAM_NAME)

    private companion object {
        const val TEAM_PREFIX = "ast_np_"
        const val HP_OBJECTIVE = "ast_np_hp"
        // Vanilla caps team names at 16 chars on the wire in older protocols; keep it short anyway.
        const val MAX_TEAM_NAME = 32
    }
}
