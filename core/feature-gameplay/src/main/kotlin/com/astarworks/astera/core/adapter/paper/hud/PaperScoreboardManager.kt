package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.IScoreboard
import com.astarworks.astera.core.adapter.api.hud.IScoreboardManager
import com.astarworks.astera.core.adapter.api.hud.ITeamScoreboard
import com.astarworks.astera.core.adapter.api.hud.ScoreboardId
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/** Registry of shared models. Client state lives exclusively in PacketSink. */
public class PaperScoreboardManager(
    private val messageRenderer: IMessageRenderer,
    private val sink: com.astarworks.astera.core.adapter.api.hud.PacketSink,
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

    @Synchronized
    override fun createScoreboard(id: ScoreboardId, title: MessageKey): IScoreboard {
        check(id !in teamBoards) {
            "ScoreboardId '$id' is already registered as a team-style board."
        }
        lineBoards[id]?.let {
            it.setTitle(title)
            return it
        }
        val created = PaperScoreboard(id, title, messageRenderer, sink)
        lineBoards[id] = created
        return created
    }

    @Synchronized
    override fun createTeamScoreboard(id: ScoreboardId, title: MessageKey): ITeamScoreboard {
        check(id !in lineBoards) {
            "ScoreboardId '$id' is already registered as a line-style board."
        }
        teamBoards[id]?.let {
            it.setTitle(title)
            return it
        }
        val created = PaperTeamScoreboard(id, title, messageRenderer, sink)
        teamBoards[id] = created
        return created
    }

    @Synchronized
    override fun scoreboard(id: ScoreboardId): IScoreboard? = lineBoards[id]
    @Synchronized
    override fun teamScoreboard(id: ScoreboardId): ITeamScoreboard? = teamBoards[id]

    @Synchronized
    override fun remove(id: ScoreboardId) {
        val removedLine = lineBoards.remove(id)
        val removedTeam = teamBoards.remove(id)
        if (removedLine == null && removedTeam == null) return
        val iter = viewers.entries.iterator()
        while (iter.hasNext()) {
            val (playerId, active) = iter.next()
            if (active.id == id) {
                detachExisting(playerId)
                iter.remove()
            }
        }
    }

    @Synchronized
    override fun show(playerId: PlayerId, scoreboard: IScoreboard) {
        require(scoreboard is PaperScoreboard) {
            "PaperScoreboardManager.show only accepts PaperScoreboard instances " +
                "produced by this manager. Got: ${scoreboard::class.simpleName}"
        }
        detachExisting(playerId)
        viewers[playerId] = ActiveBoard.Line(scoreboard)
        scoreboard.addViewer(playerId)
    }

    @Synchronized
    override fun show(playerId: PlayerId, scoreboard: ITeamScoreboard) {
        require(scoreboard is PaperTeamScoreboard) {
            "PaperScoreboardManager.show only accepts PaperTeamScoreboard instances " +
                "produced by this manager. Got: ${scoreboard::class.simpleName}"
        }
        detachExisting(playerId)
        viewers[playerId] = ActiveBoard.Team(scoreboard)
        scoreboard.addViewer(playerId)
    }

    @Synchronized
    override fun hide(playerId: PlayerId) {
        detachExisting(playerId)
        viewers.remove(playerId)
    }

    @Synchronized
    override fun currentScoreboard(playerId: PlayerId): IScoreboard? =
        (viewers[playerId] as? ActiveBoard.Line)?.board

    @Synchronized
    override fun currentTeamScoreboard(playerId: PlayerId): ITeamScoreboard? =
        (viewers[playerId] as? ActiveBoard.Team)?.board

    private fun detachExisting(playerId: PlayerId) {
        when (val active = viewers[playerId]) {
            is ActiveBoard.Line -> active.board.removeViewer(playerId)
            is ActiveBoard.Team -> active.board.removeViewer(playerId)
            null -> Unit
        }
    }

}
