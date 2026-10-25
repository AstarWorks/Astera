package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.adapter.api.hud.IScoreboard
import com.astarworks.astera.core.adapter.api.hud.IScoreboardManager
import com.astarworks.astera.core.adapter.api.hud.ITeamScoreboard
import com.astarworks.astera.core.adapter.api.hud.ScoreboardId
import com.astarworks.astera.core.adapter.api.hud.ScoreboardLine
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Wave 5b default [IScoreboardManager] registered by Core at priority 0.
 *
 * Handles both line- and team-flavored boards. Each player views at most
 * one board at a time (vanilla constraint); [show] swaps. Wave 6 Paper
 * impl replaces this at priority 100.
 *
 * Cross-flavor id collisions are fail-loud (`IllegalStateException`):
 * a single id may be line OR team, never both.
 */
public class InMemoryScoreboardManager : IScoreboardManager {

    private val lineBoards: MutableMap<ScoreboardId, InMemoryScoreboard> = linkedMapOf()
    private val teamBoards: MutableMap<ScoreboardId, InMemoryTeamScoreboard> = linkedMapOf()

    private sealed interface ActiveBoard {
        val id: ScoreboardId
        data class Line(val board: InMemoryScoreboard) : ActiveBoard {
            override val id: ScoreboardId get() = board.id
        }
        data class Team(val board: InMemoryTeamScoreboard) : ActiveBoard {
            override val id: ScoreboardId get() = board.id
        }
    }

    private val viewers: MutableMap<PlayerId, ActiveBoard> = linkedMapOf()

    override fun createScoreboard(id: ScoreboardId, title: MessageKey): IScoreboard {
        check(id !in teamBoards) {
            "ScoreboardId '$id' is already registered as a team-style board."
        }
        lineBoards[id]?.let {
            it.setTitle(title)
            return it
        }
        val created = InMemoryScoreboard(id, title)
        lineBoards[id] = created
        return created
    }

    override fun createTeamScoreboard(id: ScoreboardId, title: MessageKey): ITeamScoreboard {
        check(id !in lineBoards) {
            "ScoreboardId '$id' is already registered as a line-style board."
        }
        teamBoards[id]?.let {
            it.setTitle(title)
            return it
        }
        val created = InMemoryTeamScoreboard(id, title)
        teamBoards[id] = created
        return created
    }

    override fun scoreboard(id: ScoreboardId): IScoreboard? = lineBoards[id]
    override fun teamScoreboard(id: ScoreboardId): ITeamScoreboard? = teamBoards[id]

    override fun remove(id: ScoreboardId) {
        val removedLine = lineBoards.remove(id)
        val removedTeam = teamBoards.remove(id)
        if (removedLine == null && removedTeam == null) return
        val iter = viewers.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value.id == id) iter.remove()
        }
    }

    override fun show(playerId: PlayerId, scoreboard: IScoreboard) {
        viewers[playerId] = ActiveBoard.Line(scoreboard as InMemoryScoreboard)
    }

    override fun show(playerId: PlayerId, scoreboard: ITeamScoreboard) {
        viewers[playerId] = ActiveBoard.Team(scoreboard as InMemoryTeamScoreboard)
    }

    override fun hide(playerId: PlayerId) { viewers.remove(playerId) }

    override fun currentScoreboard(playerId: PlayerId): IScoreboard? =
        (viewers[playerId] as? ActiveBoard.Line)?.board

    override fun currentTeamScoreboard(playerId: PlayerId): ITeamScoreboard? =
        (viewers[playerId] as? ActiveBoard.Team)?.board

    private class InMemoryScoreboard(
        override val id: ScoreboardId,
        initialTitle: MessageKey,
    ) : IScoreboard {
        private var titleBacking: MessageKey = initialTitle
        override val title: MessageKey get() = titleBacking

        private val lineMap: MutableMap<Int, ScoreboardLine> = linkedMapOf()

        override fun setTitle(title: MessageKey) { titleBacking = title }

        override fun addLine(index: Int, text: MessageKey, placeholders: Map<String, String>) {
            require(index in 0..14) {
                "Scoreboard line index must be in 0..14: $index"
            }
            lineMap[index] = ScoreboardLine(index, render(text, placeholders))
        }

        override fun removeLine(index: Int) { lineMap.remove(index) }

        override fun lines(): List<ScoreboardLine> = lineMap.values.sortedBy { it.index }

        private fun render(key: MessageKey, placeholders: Map<String, String>): String {
            var out = key.value
            for ((name, value) in placeholders) out = out.replace("{$name}", value)
            return out
        }
    }

    private class InMemoryTeamScoreboard(
        override val id: ScoreboardId,
        initialTitle: MessageKey,
    ) : ITeamScoreboard {
        private var titleBacking: MessageKey = initialTitle
        private val displayNames: MutableMap<String, MessageKey> = linkedMapOf()
        private val scores: MutableMap<String, Int> = linkedMapOf()

        override fun setTitle(title: MessageKey) { titleBacking = title }

        override fun addTeam(teamId: String, displayName: MessageKey) {
            displayNames[teamId] = displayName
            scores.putIfAbsent(teamId, 0)
        }

        override fun removeTeam(teamId: String) {
            displayNames.remove(teamId)
            scores.remove(teamId)
        }

        override fun setScore(teamId: String, score: Int) {
            check(teamId in displayNames) {
                "Team '$teamId' is not registered on scoreboard '$id'. Call addTeam(...) first."
            }
            scores[teamId] = score
        }

        override fun currentScore(teamId: String): Int? =
            if (teamId in displayNames) scores[teamId] else null

        override fun teams(): Map<String, Int> =
            displayNames.keys.associateWith { scores[it] ?: 0 }
    }
}
