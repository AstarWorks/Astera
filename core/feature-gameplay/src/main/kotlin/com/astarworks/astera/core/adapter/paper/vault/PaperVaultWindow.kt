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
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.slf4j.LoggerFactory

/**
 * Paper host of the personal vault (= the ender chest) (ADR-0211): a free-movement chest window of
 * [PersonalVault.PAGE_SIZE] item slots plus a locked navigation row (previous / sort / page / next), over
 * [PersonalVault.MAX_PAGES] pages. It is owned by [IVaultWindow], plus the Bukkit listener that routes
 * vanilla ender chest blocks to it.
 *
 *  - Right-click on an `ENDER_CHEST` block (or any vanilla ender chest open)
 *    is cancelled and replaced by [AccessPersonalVaultUseCase.open] so `/astera vault`
 *    and the block behave identically (including the in-match refusal).
 *  - On close, only the open page is encoded (Base64 `ItemStack` bytes in
 *    [PersonalVault.VaultItem.nbt]) and persisted via [SaveVaultUseCase.savePage]; other pages are untouched.
 *  - Navigation buttons occupy the bottom row; every click, drag, number-key swap or double-click gather
 *    that could move or take one is cancelled. Page switching closes (= saves) and reopens next tick.
 *
 * Bukkit types stay in this adapter; the aggregate / use cases are pure.
 */
public class PaperVaultWindow(
    private val server: Server,
    private val plugin: Plugin,
    private val save: SaveVaultUseCase,
    private val access: () -> AccessPersonalVaultUseCase,
    private val messages: IMessageRenderer,
) : IVaultWindow, Listener {

    private val log = LoggerFactory.getLogger(PaperVaultWindow::class.java)

    private class VaultHolder(val owner: PlayerId, val page: Int) : InventoryHolder {
        lateinit var bound: Inventory
        override fun getInventory(): Inventory = bound
    }

    override fun show(vault: PersonalVault, page: Int): Boolean {
        val player = server.getPlayer(vault.playerId.uuid) ?: return false
        val shownPage = page.coerceIn(0, PersonalVault.MAX_PAGES - 1)
        val holder = VaultHolder(vault.playerId, shownPage)
        val title = AsteraTextDeserializer.deserialize(
            messages.render(vault.playerId, TITLE, mapOf("page" to (shownPage + 1).toString(), "pages" to PersonalVault.MAX_PAGES.toString())),
        )
        val inventory = server.createInventory(holder, WINDOW_SIZE, title)
        holder.bound = inventory
        for (item in vault.pageItems(shownPage)) {
            decode(item)?.let { inventory.setItem(item.slot, it) }
        }
        paintNavigation(inventory, vault.playerId, shownPage)
        player.openInventory(inventory)
        return true
    }

    override fun closeFor(playerId: PlayerId) {
        val player = server.getPlayer(playerId.uuid) ?: return
        if (!org.bukkit.Bukkit.isOwnedByCurrentRegion(player)) {
            player.scheduler.execute(plugin, Runnable { closeFor(playerId) }, null, 1L)
            return
        }
        if (player.openInventory.topInventory.holder is VaultHolder) player.closeInventory()
    }

    /** Close every open vault so contents are saved (called on plugin disable). */
    public fun closeAll() {
        server.onlinePlayers.map { PlayerId(it.uniqueId) }.forEach(::closeFor)
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

    @EventHandler(priority = EventPriority.HIGH)
    public fun onClick(event: InventoryClickEvent) {
        val top = event.view.topInventory
        val holder = top.holder as? VaultHolder ?: return
        val inNavRow = event.clickedInventory == top && event.rawSlot in NAV_START until WINDOW_SIZE
        // Double-click "gather" would pull identical nav stacks off the row.
        val gathersNav = event.action == InventoryAction.COLLECT_TO_CURSOR && event.cursor.type in NAV_MATERIALS
        if (!inNavRow && !gathersNav) return
        event.isCancelled = true
        if (!inNavRow || event.click != ClickType.LEFT) return
        val player = event.whoClicked as? Player ?: return
        when (event.rawSlot) {
            SLOT_PREV -> if (holder.page > 0) goToPage(player, holder.page - 1)
            SLOT_NEXT -> if (holder.page < PersonalVault.MAX_PAGES - 1) goToPage(player, holder.page + 1)
            SLOT_SORT -> sort(top)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onDrag(event: InventoryDragEvent) {
        val top = event.view.topInventory
        if (top.holder !is VaultHolder) return
        if (event.rawSlots.any { it in NAV_START until WINDOW_SIZE }) event.isCancelled = true
    }

    @EventHandler
    public fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? VaultHolder ?: return
        val items = (0 until PersonalVault.PAGE_SIZE).mapNotNull { slot ->
            event.inventory.getItem(slot)?.takeUnless { it.isEmpty }?.let { encode(slot, it) }
        }
        when (val result = save.savePage(holder.owner, holder.page, items)) {
            is Result.Success -> Unit
            is Result.Failure -> log.warn("Vault save failed for {}: {}", holder.owner, result.error)
        }
    }

    private fun goToPage(player: Player, page: Int) {
        val playerId = PlayerId(player.uniqueId)
        player.scheduler.execute(
            plugin,
            Runnable {
                if (!player.isOnline) return@Runnable
                player.closeInventory() // saves the page being left
                when (val result = access().open(playerId, page)) {
                    is Result.Success -> Unit
                    is Result.Failure -> player.sendMessage(
                        AsteraTextDeserializer.deserialize(messages.render(playerId, vaultErrorKey(result.error))),
                    )
                }
            }, null, 1L,
        )
    }

    /** Stack-merge then order the page's items by material and name; empty slots collect at the end. */
    private fun sort(inventory: Inventory) {
        val stacks = (0 until PersonalVault.PAGE_SIZE).mapNotNull { inventory.getItem(it)?.takeUnless { s -> s.isEmpty }?.clone() }
        val merged = ArrayList<ItemStack>()
        for (stack in stacks) {
            var rest = stack.amount
            for (existing in merged) {
                if (rest == 0) break
                if (existing.isSimilar(stack) && existing.amount < existing.maxStackSize) {
                    val moved = minOf(rest, existing.maxStackSize - existing.amount)
                    existing.amount += moved
                    rest -= moved
                }
            }
            if (rest > 0) merged += stack.clone().also { it.amount = rest }
        }
        merged.sortWith(
            compareBy<ItemStack> { it.type.key.toString() }
                .thenBy { it.itemMeta?.displayName()?.toString().orEmpty() }
                .thenByDescending { it.amount },
        )
        for (slot in 0 until PersonalVault.PAGE_SIZE) inventory.setItem(slot, merged.getOrNull(slot))
    }

    private fun paintNavigation(inventory: Inventory, playerId: PlayerId, page: Int) {
        val filler = button(Material.GRAY_STAINED_GLASS_PANE, null, playerId)
        for (slot in NAV_START until WINDOW_SIZE) inventory.setItem(slot, filler.clone())
        if (page > 0) inventory.setItem(SLOT_PREV, button(Material.ARROW, NAV_PREV, playerId, page))
        inventory.setItem(SLOT_SORT, button(Material.HOPPER, NAV_SORT, playerId))
        inventory.setItem(SLOT_PAGE, button(Material.PAPER, NAV_PAGE, playerId, page + 1))
        if (page < PersonalVault.MAX_PAGES - 1) inventory.setItem(SLOT_NEXT, button(Material.ARROW, NAV_NEXT, playerId, page + 2))
    }

    private fun button(material: Material, key: MessageKey?, playerId: PlayerId, shownPage: Int = 1): ItemStack {
        val stack = ItemStack(material)
        stack.editMeta { meta ->
            val text = if (key == null) " " else messages.render(
                playerId,
                key,
                mapOf("page" to shownPage.toString(), "pages" to PersonalVault.MAX_PAGES.toString()),
            )
            meta.displayName(AsteraTextDeserializer.deserialize(text))
        }
        return stack
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
        val NAV_PREV = MessageKey("astera.vault.nav.prev")
        val NAV_NEXT = MessageKey("astera.vault.nav.next")
        val NAV_SORT = MessageKey("astera.vault.nav.sort")
        val NAV_PAGE = MessageKey("astera.vault.nav.page")
        const val WINDOW_SIZE = 54
        const val NAV_START = PersonalVault.PAGE_SIZE
        const val SLOT_PREV = NAV_START
        const val SLOT_SORT = NAV_START + 2
        const val SLOT_PAGE = NAV_START + 4
        const val SLOT_NEXT = NAV_START + 8
        val NAV_MATERIALS = setOf(Material.GRAY_STAINED_GLASS_PANE, Material.ARROW, Material.HOPPER, Material.PAPER)
    }
}
