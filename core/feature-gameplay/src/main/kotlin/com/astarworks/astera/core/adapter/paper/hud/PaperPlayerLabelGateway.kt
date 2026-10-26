package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.HudFrame
import com.astarworks.astera.core.adapter.api.hud.HudObjective
import com.astarworks.astera.core.adapter.api.hud.HudScore
import com.astarworks.astera.core.adapter.api.hud.HudSurface
import com.astarworks.astera.core.adapter.api.hud.HudTeam
import com.astarworks.astera.core.adapter.api.hud.PacketSink
import com.astarworks.astera.core.application.port.outbound.IPlayerLabelGateway
import com.astarworks.astera.core.domain.hud.PlayerLabel
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server

/** Labels occupy their own slice, so replacing/hiding a sidebar never tears them down. */
public class PaperPlayerLabelGateway(private val server: Server, private val sink: PacketSink) : IPlayerLabelGateway {
    override fun apply(viewer: PlayerId, labels: Map<PlayerId, PlayerLabel>) {
        val names = labels.mapNotNull { (id, label) -> server.getPlayer(id.uuid)?.name?.let { it to label } }.toMap()
        val withHp = names.filterValues { it.hp != null }
        sink.submit(viewer, HudSurface.LABELS, HudFrame(
            objectives = if (withHp.isEmpty()) emptyMap() else mapOf("ast_np_hp" to HudObjective("<red>❤</red>", 2)),
            teams = names.entries.groupBy { it.value.group }.map { (group, members) ->
                // Hash the full group; seven prefix + nine hash characters fit the 16-character wire limit.
                ("ast_np_" + java.util.UUID.nameUUIDFromBytes(group.toByteArray()).toString().replace("-", "").take(9)) to
                    HudTeam(color = members.first().value.colorName, members = members.map { it.key }.toSet())
            }.toMap(),
            scores = withHp.map { (name, label) -> HudScore("ast_np_hp", name) to requireNotNull(label.hp) }.toMap(),
        ))
    }

    override fun clear(viewer: PlayerId) { sink.submit(viewer, HudSurface.LABELS, HudFrame()) }
}
