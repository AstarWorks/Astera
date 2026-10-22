package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.application.port.outbound.IItemCooldownGateway
import com.astarworks.astera.core.application.port.outbound.ItemCooldownError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server

/**
 * Paper [IItemCooldownGateway] (F2-2, ADR-0194): `Player.setCooldown(ItemStack, ticks)`
 * on the held stack, which the client renders as the native sweep over the item.
 * Main thread only (called from the cast path, which runs there).
 */
public class PaperItemCooldownGateway(
    private val server: Server,
) : IItemCooldownGateway {

    override fun showHeldItemCooldown(playerId: PlayerId, ticks: Int): Result<Unit, ItemCooldownError> {
        if (ticks < 1) return Result.failure(ItemCooldownError.InvalidTicks(ticks))
        val player = server.getPlayer(playerId.uuid)
            ?: return Result.failure(ItemCooldownError.BackendUnavailable("player offline"))
        val held = player.inventory.itemInMainHand
        if (held.type.isAir) return Result.failure(ItemCooldownError.BackendUnavailable("nothing held"))
        player.setCooldown(held, ticks)
        return Result.success(Unit)
    }
}
