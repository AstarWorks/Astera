package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.HudFrame
import com.astarworks.astera.core.adapter.api.hud.HudObjective
import com.astarworks.astera.core.adapter.api.hud.HudScore
import com.astarworks.astera.core.adapter.api.hud.HudSurface
import com.astarworks.astera.core.adapter.api.hud.HudTeam
import com.astarworks.astera.core.adapter.api.hud.ITeamScoreboard
import com.astarworks.astera.core.adapter.api.hud.PacketSink
import com.astarworks.astera.core.adapter.api.hud.ScoreboardId
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.UUID

/** Team-score sidebar model; shares the packet-only client state with line sidebars and labels. */
public class PaperTeamScoreboard(
    override val id: ScoreboardId,
    initialTitle: MessageKey,
    private val messageRenderer: IMessageRenderer,
    private val sink: PacketSink,
) : ITeamScoreboard {
    private var title: MessageKey = initialTitle
    private data class Row(val key: MessageKey, val entry: String, val score: Int = 0)
    private val rows = linkedMapOf<String, Row>()
    private val viewers = linkedSetOf<PlayerId>()
    private var nextOrdinal = 0

    @Synchronized
    override fun setTitle(title: MessageKey) {
        this.title = title
        refresh()
    }

    @Synchronized
    override fun addTeam(teamId: String, displayName: MessageKey) {
        rows[teamId] = rows[teamId]?.copy(key = displayName) ?: Row(displayName, nextEntryToken())
        refresh()
    }

    @Synchronized
    override fun removeTeam(teamId: String) {
        if (rows.remove(teamId) != null) refresh()
    }

    @Synchronized
    override fun setScore(teamId: String, score: Int) {
        val row = checkNotNull(rows[teamId]) { "Team '$teamId' is not registered on scoreboard '$id'." }
        rows[teamId] = row.copy(score = score)
        refresh()
    }

    @Synchronized
    override fun currentScore(teamId: String): Int? = rows[teamId]?.score

    @Synchronized
    override fun teams(): Map<String, Int> = rows.mapValues { it.value.score }

    @Synchronized
    public fun addViewer(playerId: PlayerId) {
        viewers.add(playerId)
        draw(playerId)
    }

    @Synchronized
    public fun removeViewer(playerId: PlayerId) {
        viewers.remove(playerId)
        sink.submit(playerId, HudSurface.SIDEBAR, HudFrame())
    }

    @Synchronized
    public fun viewers(): Set<UUID> = viewers.map { it.uuid }.toSet()

    private fun refresh() { viewers.forEach(::draw) }

    private fun draw(viewer: PlayerId) {
        sink.submit(viewer, HudSurface.SIDEBAR, HudFrame(
            objectives = mapOf("astera-team" to HudObjective(messageRenderer.render(viewer, title), 1)),
            teams = rows.map { (teamId, row) ->
                ScoreboardTeamNames.teamName(id, teamId) to HudTeam(
                    prefix = messageRenderer.render(viewer, row.key), members = setOf(row.entry),
                )
            }.toMap(),
            scores = rows.values.associate { HudScore("astera-team", it.entry) to it.score },
        ))
    }

    private fun nextEntryToken(): String {
        val ordinal = nextOrdinal++
        check(ordinal < 256) { "Scoreboard '$id' exhausted its 256 opaque entry tokens." }
        val codes = "0123456789abcdef"
        return "§${codes[ordinal / 16]}§${codes[ordinal % 16]}§r"
    }
}
