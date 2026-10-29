package com.astarworks.astera.core.adapter.paper.inventory

import com.astarworks.astera.core.application.port.outbound.IPlayerInventoryGateway
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.slf4j.LoggerFactory

/**
 * Paper [IPlayerInventoryGateway]: whole-inventory capture / overwrite through
 * [PaperInventorySnapshotSerializer]. Main-thread only (event-bus handlers
 * for match lifecycle events already run there).
 *
 * Any container window the player has open (personal vault, GUI menu) is closed first: its
 * contents are not part of the player inventory, so a snapshot taken or restored while it is
 * open would lose or duplicate items (a vault item taken out after the entry snapshot is
 * erased by the match-end restore). Closing also saves the vault and returns the cursor stack.
 */
public class PaperPlayerInventoryGateway(
    private val server: Server,
    private val serializer: PaperInventorySnapshotSerializer,
) : IPlayerInventoryGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun capture(playerId: PlayerId): PlayerInventorySnapshot? {
        val player = server.getPlayer(playerId.uuid) ?: return null
        settleOpenWindow(player)
        log.info("Inventory capture for {} (source=gateway, caller={})", player.name, callerHint())
        return serializer.serialize(player)
    }

    override fun apply(snapshot: PlayerInventorySnapshot): Boolean {
        val player = server.getPlayer(snapshot.playerId.uuid) ?: return false
        settleOpenWindow(player)
        log.info("Inventory overwrite for {} (source=gateway, savedAt={}, caller={})", player.name, snapshot.savedAt, callerHint())
        serializer.deserialize(snapshot, player)
        return true
    }

    /** First non-gateway frame outside this class: names which writer (handoff, match restore, ...) is calling. */
    private fun callerHint(): String =
        StackWalker.getInstance().walk { frames ->
            frames.skip(1).filter { it.className != javaClass.name }.findFirst().map { it.className.substringAfterLast('.') + "." + it.methodName }.orElse("?")
        }

    private fun settleOpenWindow(player: Player) {
        if (player.openInventory.topInventory.type != InventoryType.CRAFTING) player.closeInventory()
    }
}
