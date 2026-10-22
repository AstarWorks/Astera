package com.astarworks.astera.core.adapter.paper.inventory

import com.astarworks.astera.core.application.port.outbound.IPlayerInventoryGateway
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import org.bukkit.Server

/**
 * Paper [IPlayerInventoryGateway]: whole-inventory capture / overwrite through
 * [PaperInventorySnapshotSerializer]. Main-thread only (event-bus handlers
 * for match lifecycle events already run there).
 */
public class PaperPlayerInventoryGateway(
    private val server: Server,
    private val serializer: PaperInventorySnapshotSerializer,
) : IPlayerInventoryGateway {

    override fun capture(playerId: PlayerId): PlayerInventorySnapshot? {
        val player = server.getPlayer(playerId.uuid) ?: return null
        return serializer.serialize(player)
    }

    override fun apply(snapshot: PlayerInventorySnapshot): Boolean {
        val player = server.getPlayer(snapshot.playerId.uuid) ?: return false
        serializer.deserialize(snapshot, player)
        return true
    }
}
