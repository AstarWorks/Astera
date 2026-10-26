package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.HudFrame
import com.astarworks.astera.core.adapter.api.hud.HudObjective
import com.astarworks.astera.core.adapter.api.hud.HudScore
import com.astarworks.astera.core.adapter.api.hud.HudSurface
import com.astarworks.astera.core.adapter.api.hud.HudTeam
import com.astarworks.astera.core.adapter.api.hud.IScoreboard
import com.astarworks.astera.core.adapter.api.hud.PacketSink
import com.astarworks.astera.core.adapter.api.hud.ScoreboardId
import com.astarworks.astera.core.adapter.api.hud.ScoreboardLine
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.UUID

/** Shared sidebar model. Only the sink owns per-player wire state and dispatches to Entity scope. */
public class PaperScoreboard(
    override val id: ScoreboardId,
    initialTitle: MessageKey,
    private val messageRenderer: IMessageRenderer,
    private val sink: PacketSink,
) : IScoreboard {
    private var titleBacking: MessageKey = initialTitle
    private data class Line(val key: MessageKey, val placeholders: Map<String, String>)
    private val lineMap = linkedMapOf<Int, Line>()
    private val viewers = linkedSetOf<PlayerId>()

    override val title: MessageKey @Synchronized get() = titleBacking

    @Synchronized
    override fun setTitle(title: MessageKey) {
        titleBacking = title
        refresh()
    }

    @Synchronized
    override fun addLine(index: Int, text: MessageKey, placeholders: Map<String, String>) {
        require(index in 0..14) { "Scoreboard line index must be in 0..14: $index" }
        lineMap[index] = Line(text, placeholders.toMap())
        refresh()
    }

    @Synchronized
    override fun removeLine(index: Int) {
        if (lineMap.remove(index) != null) refresh()
    }

    @Synchronized
    override fun lines(): List<ScoreboardLine> = lineMap.toSortedMap().map { (index, line) ->
        ScoreboardLine(index, messageRenderer.render(null, line.key, line.placeholders))
    }

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
            objectives = mapOf("astera" to HudObjective(messageRenderer.render(viewer, titleBacking), 1, true)),
            teams = lineMap.map { (index, line) ->
                ScoreboardTeamNames.lineName(index) to HudTeam(
                    prefix = messageRenderer.render(viewer, line.key, line.placeholders),
                    members = setOf(entryToken(index)),
                )
            }.toMap(),
            scores = lineMap.keys.associate { HudScore("astera", entryToken(it)) to it },
        ))
    }

    private fun entryToken(index: Int): String = "§${"0123456789abcde"[index]}§r"
}
