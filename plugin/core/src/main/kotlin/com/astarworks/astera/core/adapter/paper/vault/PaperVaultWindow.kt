package com.astarworks.astera.core.adapter.paper.vault

import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import com.astarworks.astera.core.application.command.vaultErrorKey
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IVaultWindow
import com.astarworks.astera.core.application.usecase.vault.AccessPersonalVaultUseCase
import com.astarworks.astera.core.application.usecase.vault.SaveVaultUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.vault.PersonalVault
import java.util.Base64
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.event.block.Action
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.slf4j.LoggerFactory

/**
 * Paper host of the personal vault (= the ender chest): a free-movement
 * 27-slot chest inventory owned by [IVaultWindow], plus the Bukkit listener
 * that routes vanilla ender chest blocks to it.
 *
 *  - Right-click on an `ENDER_CHEST` block (or any vanilla ender chest open)
 *    is cancelled and replaced by [AccessPersonalVaultUseCase.open] so `/vault`
 *    and the block behave identically (including the in-match refusal).
 *  - On close, the chest contents are encoded (Base64 `ItemStack` bytes in
 *    [PersonalVault.VaultItem.nbt]) and persisted via [SaveVaultUseCase].
 *
 * Bukkit types stay in this adapter; the aggregate / use cases are pure.
 */
public class PaperVaultWindow(
    private val server: Server,
    private val save: SaveVaultUseCase,
    private val access: () -> AccessPersonalVaultUseCase,
    private val messages: IMessageRenderer,
) : IVaultWindow, Listener {

    private val log = LoggerFactory.getLogger(PaperVaultWindow::class.java)

    private class VaultHolder(val owner: PlayerId) : InventoryHolder {
        lateinit var bound: Inventory
        override fun getInventory(): Inventory = bound
    }

    override fun show(vault: PersonalVault): Boolean {
        val player = server.getPlayer(vault.playerId.uuid) ?: return false
        val holder = VaultHolder(vault.playerId)
        val title = AsteraTextDeserializer.deserialize(messages.render(vault.playerId, TITLE))
        val inventory = server.createInventory(holder, PersonalVault.SLOT_COUNT, title)
        holder.bound = inventory
        for (item in vault.items) {
            decode(item)?.let { inventory.setItem(item.slot, it) }
        }
        player.openInventory(inventory)
        return true
    }

    /** Close every open vault so contents are saved (called on plugin disable). */
    public fun closeAll() {
        for (player in server.onlinePlayers) {
            if (player.openInventory.topInventory.holder is VaultHolder) player.closeInventory()
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        if (event.clickedBlock?.type != Material.ENDER_CHEST) return
        val player = event.player
        // Sneak + held item = vanilla block placement against the chest; leave it alone.
        if (player.isSneaking && !player.inventory.itemInMainHand.isEmpty) return
        event.isCancelled = true
        openFor(player)
    }

    /** Safety net: no vanilla ender chest window may ever open (e.g. via plugins / spectators). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onOpen(event: InventoryOpenEvent) {
        if (event.inventory.type == InventoryType.ENDER_CHEST && event.inventory.holder !is VaultHolder) {
            event.isCancelled = true
        }
    }

    @EventHandler
    public fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? VaultHolder ?: return
        val items = (0 until PersonalVault.SLOT_COUNT).mapNotNull { slot ->
            event.inventory.getItem(slot)?.takeUnless { it.isEmpty }?.let { encode(slot, it) }
        }
        when (val result = save.save(PersonalVault(holder.owner, items))) {
            is Result.Success -> Unit
            is Result.Failure -> log.warn("Vault save failed for {}: {}", holder.owner, result.error)
        }
    }

    private fun openFor(player: Player) {
        val playerId = PlayerId(player.uniqueId)
        when (val result = access().open(playerId)) {
            is Result.Success -> Unit
            is Result.Failure -> player.sendMessage(
                AsteraTextDeserializer.deserialize(messages.render(playerId, vaultErrorKey(result.error))),
            )
        }
    }

    private fun encode(slot: Int, stack: ItemStack): PersonalVault.VaultItem =
        PersonalVault.VaultItem(
            slot = slot,
            itemId = stack.type.key.toString(),
            count = stack.amount,
            nbt = Base64.getEncoder().encodeToString(stack.serializeAsBytes()),
        )

    private fun decode(item: PersonalVault.VaultItem): ItemStack? {
        val encoded = item.nbt ?: return Material.matchMaterial(item.itemId)?.let { ItemStack(it, item.count) }
        return try {
            ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded))
        } catch (t: Throwable) {
            log.warn("Vault slot {} ({}) could not be decoded; dropped", item.slot, item.itemId, t)
            null
        }
    }

    private companion object {
        val TITLE = MessageKey("astera.vault.title")
    }
}
