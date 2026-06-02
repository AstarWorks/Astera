package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.IScoreboard
import com.astarworks.astera.core.adapter.api.hud.IScoreboardManager
import com.astarworks.astera.core.adapter.api.hud.ITeamScoreboard
import com.astarworks.astera.core.adapter.api.hud.ScoreboardId
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server

/**
 * Wave 6b Paper [IScoreboardManager] registered at priority 100, replacing
 * the Wave 5b `InMemoryScoreboardManager`.
 *
 * Same lifecycle and id-collision semantics as the in-memory fake (see
 * `IScoreboardManager` KDoc): each [ScoreboardId] is line OR team, never
 * both; cross-flavor collisions throw [IllegalStateException]; idempotent
 * `create*` calls update the title and return the existing instance.
 *
 * Wave 6b backs every created board with a fresh vanilla `Scoreboard`
 * obtained from `Server.scoreboardManager.newScoreboard`. The vanilla
 * `mainScoreboard` is never mutated — restoring viewers via [hide]
 * reassigns them to the main scoreboard so they revert to baseline.
 *
 * Threading: same constraint as `PaperScoreboard` — mutating methods run
 * on the main server thread.
 */
public class PaperScoreboardManager(
    private val server: Server,
    private val messageRenderer: IMessageRenderer? = null,
) : IScoreboardManager {

    private val lineBoards: MutableMap<ScoreboardId, PaperScoreboard> = linkedMapOf()
    private val teamBoards: MutableMap<ScoreboardId, PaperTeamScoreboard> = linkedMapOf()

    private sealed interface ActiveBoard {
        val id: ScoreboardId
        data class Line(val board: PaperScoreboard) : ActiveBoard {
            override val id: ScoreboardId get() = board.id
        }
        data class Team(val board: PaperTeamScoreboard) : ActiveBoard {
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
        val vanilla = server.scoreboardManager
            ?.newScoreboard
            ?: error(
                "Server.scoreboardManager is null — cannot create scoreboard '$id'. " +
                    "This happens before the first world is loaded; create boards on onEnable or later.",
            )
        val created = PaperScoreboard(id, title, vanilla, server, messageRenderer)
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
        val vanilla = server.scoreboardManager
            ?.newScoreboard
            ?: error(
                "Server.scoreboardManager is null — cannot create team scoreboard '$id'. " +
                    "This happens before the first world is loaded; create boards on onEnable or later.",
            )
        val created = PaperTeamScoreboard(id, title, vanilla, server, messageRenderer)
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
            val (playerId, active) = iter.next()
            if (active.id == id) {
                detachFromMain(playerId)
                iter.remove()
            }
        }
    }

    override fun show(playerId: PlayerId, scoreboard: IScoreboard) {
        require(scoreboard is PaperScoreboard) {
            "PaperScoreboardManager.show only accepts PaperScoreboard instances " +
                "produced by this manager. Got: ${scoreboard::class.simpleName}"
        }
        detachExisting(playerId)
        viewers[playerId] = ActiveBoard.Line(scoreboard)
        scoreboard.addViewer(playerId)
    }

    override fun show(playerId: PlayerId, scoreboard: ITeamScoreboard) {
        require(scoreboard is PaperTeamScoreboard) {
            "PaperScoreboardManager.show only accepts PaperTeamScoreboard instances " +
                "produced by this manager. Got: ${scoreboard::class.simpleName}"
        }
        detachExisting(playerId)
        viewers[playerId] = ActiveBoard.Team(scoreboard)
        scoreboard.addViewer(playerId)
    }

    override fun hide(playerId: PlayerId) {
        detachExisting(playerId)
        viewers.remove(playerId)
    }

    override fun currentScoreboard(playerId: PlayerId): IScoreboard? =
        (viewers[playerId] as? ActiveBoard.Line)?.board

    override fun currentTeamScoreboard(playerId: PlayerId): ITeamScoreboard? =
        (viewers[playerId] as? ActiveBoard.Team)?.board

    private fun detachExisting(playerId: PlayerId) {
        when (val active = viewers[playerId]) {
            is ActiveBoard.Line -> active.board.removeViewer(playerId)
            is ActiveBoard.Team -> active.board.removeViewer(playerId)
            null -> Unit
        }
    }

    private fun detachFromMain(playerId: PlayerId) {
        val mainScoreboard = server.scoreboardManager?.mainScoreboard ?: return
        server.getPlayer(playerId.uuid)?.let { it.scoreboard = mainScoreboard }
    }
}
