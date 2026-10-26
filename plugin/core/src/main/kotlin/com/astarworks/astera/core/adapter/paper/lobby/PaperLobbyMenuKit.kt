package com.astarworks.astera.core.adapter.paper.lobby

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.adapter.paper.gui.PaperItemTemplateRenderer
import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import com.astarworks.astera.core.application.gui.ViewerVisibility
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.service.lobby.KitClickFacts
import com.astarworks.astera.core.application.service.lobby.LobbyKitLockRules
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.lobby.KitArea
import com.astarworks.astera.core.domain.lobby.KitEntry
import com.astarworks.astera.core.domain.lobby.LobbyKeybinds
import com.astarworks.astera.core.domain.lobby.LobbyMenuKit
import com.astarworks.astera.core.domain.lobby.LobbyMenuKitPolicy
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ServerRole
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import java.util.UUID

/** PDC marker on every kit stack; its value is the [KitEntry.id] (ADR-0185). */
public object MenuKitTag {
    private val KEY: NamespacedKey = NamespacedKey.fromString("astera:menu_kit")!!

    /** The kit entry id carried by [stack], or `null` for any other item. */
    public fun entryId(stack: ItemStack?): String? {
        if (stack == null || stack.isEmpty || !stack.hasItemMeta()) return null
        return stack.itemMeta?.persistentDataContainer?.get(KEY, PersistentDataType.STRING)
    }

    public fun isKit(stack: ItemStack?): Boolean = entryId(stack) != null

    internal fun mark(stack: ItemStack, entryId: String) {
        stack.editMeta { it.persistentDataContainer.set(KEY, PersistentDataType.STRING, entryId) }
    }
}

/**
 * Lobby hotbar kit + the menu buttons in the inventory's 2x2 crafting grid (ADR-0185).
 *
 * The player's own crafting grid is server-side state (`player.openInventory` is a
 * `CRAFTING` view even while no screen is open), so the buttons are simply kept in it:
 * set on join / respawn / world change and re-set after every close. Vanilla would hand
 * grid contents back to the inventory on close, so the grid is cleared inside
 * [InventoryCloseEvent] first. Every path that could move a kit item (click, drag,
 * number-key / offhand swap, double-click gather, shift-click, drop, hand swap, place,
 * death drop, craft) is cancelled; the snapshot serializer ignores kit stacks and
 * re-applies the kit after a restore.
 */
internal class PaperLobbyMenuKit(
    private val plugin: JavaPlugin,
    private val kitSource: () -> LobbyMenuKit,
    private val role: ServerRole,
    private val renderer: PaperItemTemplateRenderer,
    private val messages: IMessageRenderer,
    private val intents: IGuiIntentRouter,
    private val mcServer: IMcServer,
    private val inMatch: (PlayerId) -> Boolean,
    private val visibility: () -> ViewerVisibility?,
    private val queued: (PlayerId) -> Boolean = { false },
    private val onMenuKey: (PlayerId) -> Unit = {},
) : Listener {

    private val kit: LobbyMenuKit get() = kitSource()

    /** The kit as [player] sees it: `visible_if` entries resolved (ADR-0240); fails closed without a [ViewerVisibility]. */
    private fun kitFor(player: Player): LobbyMenuKit = kitFor(PlayerId(player.uniqueId))

    private fun kitFor(id: PlayerId): LobbyMenuKit {
        val all = kit
        if (!all.hasConditionalEntries) return all
        val v = visibility()
        return all.forViewer(
            hasPermission = { node -> v?.hasPermission(id, node) ?: false },
            hasState = { state ->
                when (state) {
                    "queued" -> queued(id)
                    "idle" -> !queued(id)
                    else -> false
                }
            },
        )
    }
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastPress = HashMap<UUID, Long>()

    // ---- apply / strip -------------------------------------------------

    private fun active(player: Player): Boolean =
        LobbyMenuKitPolicy.active(role, inMatch(PlayerId(player.uniqueId)))

    /** Remove every kit stack from [player], then give the kit back if it applies right now. */
    fun refresh(player: Player) {
        if (!player.isOnline) return
        try {
            strip(player)
            if (active(player)) give(player)
        } catch (t: Throwable) {
            log.warn("Lobby menu kit refresh failed for {}", player.name, t)
        }
    }

    /** Rebuild one player's kit on the next tick (ADR-0248: queue state changed). Safe from any thread. */
    fun refreshLater(id: PlayerId) {
        val player = plugin.server.getPlayer(id.uuid) ?: return
        later(player)
    }

    fun refreshAll() {
        for (p in plugin.server.onlinePlayers) later(p)
    }

    /** Plugin disable: playerdata is saved after plugins stop, so take the kit off everyone first. */
    fun stripAll() {
        for (p in plugin.server.onlinePlayers) stripOnOwner(p)
    }

    private fun stripOnOwner(player: Player) {
        if (org.bukkit.Bukkit.isOwnedByCurrentRegion(player)) strip(player)
        else player.scheduler.execute(plugin, Runnable { strip(player) }, null, 1L)
    }

    private fun later(player: Player) {
        player.scheduler.execute(plugin, Runnable { refresh(player) }, null, 1L)
    }

    private fun strip(player: Player) {
        val inv = player.inventory
        for (i in 0 until inv.size) {
            if (MenuKitTag.isKit(inv.getItem(i))) inv.setItem(i, null)
        }
        if (MenuKitTag.isKit(player.itemOnCursor)) player.setItemOnCursor(null)
        stripGrid(player)
    }

    private fun craftingTop(player: Player): Inventory? =
        player.openInventory.topInventory.takeIf { it.type == InventoryType.CRAFTING }

    private fun stripGrid(player: Player) {
        val top = craftingTop(player) ?: return
        for (i in 1..4) if (MenuKitTag.isKit(top.getItem(i))) top.setItem(i, null)
        if (MenuKitTag.isKit(top.getItem(0))) top.setItem(0, null)
    }

    private fun give(player: Player) {
        val inv = player.inventory
        val displaced = ArrayList<ItemStack>()
        val viewerKit = kitFor(player)
        val hotbar = viewerKit.entries(KitArea.HOTBAR).mapNotNull { e -> build(player, e)?.let { e to it } }
        for ((e, _) in hotbar) {
            inv.getItem(e.slot)?.takeIf { !it.isEmpty }?.let { displaced += it }
            inv.setItem(e.slot, null)
        }
        for ((e, stack) in hotbar) inv.setItem(e.slot, stack)
        giveGrid(player, viewerKit, displaced)
        for (stray in displaced) {
            inv.addItem(stray).values.forEach { player.world.dropItemNaturally(player.location, it) }
        }
    }

    private fun giveGrid(player: Player, viewerKit: LobbyMenuKit, displaced: MutableList<ItemStack>) {
        val top = craftingTop(player) ?: return
        val grid = viewerKit.entries(KitArea.CRAFTING_GRID)
        val result = viewerKit.entries(KitArea.CRAFTING_RESULT).firstOrNull()
        if (grid.isEmpty() && result == null) return
        for (i in 1..4) {
            top.getItem(i)?.takeIf { !it.isEmpty && !MenuKitTag.isKit(it) }?.let { displaced += it }
            top.setItem(i, null)
        }
        for (e in grid) build(player, e)?.let { top.setItem(e.slot + 1, it) }
        // Matrix writes recompute the result slot, so it is written last (and PrepareItemCraftEvent keeps it).
        result?.let { e -> build(player, e)?.let { top.setItem(0, it) } }
    }

    private fun build(player: Player, entry: KitEntry): ItemStack? {
        val stack = renderer.render(GuiSlot.Static(itemId = entry.itemId), PlayerId(player.uniqueId)) ?: return null
        stack.editMeta { meta ->
            entry.loreKey?.let { key ->
                val hint = AsteraTextDeserializer.deserialize(messages.render(PlayerId(player.uniqueId), key))
                meta.lore((meta.lore() ?: emptyList()) + hint)
            }
            val viewer = visibility()
            for (key in entry.extraLoreFor { node -> viewer?.hasPermission(PlayerId(player.uniqueId), node) ?: false }) {
                val line = AsteraTextDeserializer.deserialize(messages.render(PlayerId(player.uniqueId), key))
                meta.lore((meta.lore() ?: emptyList()) + line)
            }
            meta.setMaxStackSize(1)
        }
        MenuKitTag.mark(stack, entry.id)
        return stack
    }

    // ---- lifecycle -----------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(e: PlayerJoinEvent) {
        later(e.player)
        hintKeys(e.player)
    }

    /** Once per join: tell the player about the keybinds (ADR-0232). Silent where the kit does not apply. */
    private fun hintKeys(player: Player) {
        player.scheduler.execute(
            plugin,
            Runnable {
                if (!player.isOnline || !active(player)) return@Runnable
                val text = messages.render(PlayerId(player.uniqueId), com.astarworks.astera.core.domain.i18n.MessageKey(KEYS_HINT))
                player.sendActionBar(AsteraTextDeserializer.deserialize(text))
            },
            null, HINT_DELAY_TICKS,
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(e: PlayerRespawnEvent) = later(e.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorld(e: PlayerChangedWorldEvent) = later(e.player)

    /** Match entry / return teleports can stay inside one world (no PlayerChangedWorldEvent), so re-evaluate on every teleport. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(e: PlayerTeleportEvent) = later(e.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onGameMode(e: PlayerGameModeChangeEvent) = later(e.player)

    /** LOWEST: runs before the snapshot listener's MONITOR save, so playerdata never keeps the kit. */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(e: PlayerQuitEvent) {
        strip(e.player)
        lastPress.remove(e.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDeath(e: PlayerDeathEvent) {
        e.drops.removeIf { MenuKitTag.isKit(it) }
        e.itemsToKeep.removeIf { MenuKitTag.isKit(it) }
        strip(e.player)
    }

    /**
     * Closing the own-inventory screen: vanilla would return the grid to the inventory, so the
     * buttons are removed first. Any close schedules a refresh (a GUI open right after keeps the
     * hotbar; the grid comes back when the player is on the plain inventory view again).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onClose(e: InventoryCloseEvent) {
        val player = e.player as? Player ?: return
        if (e.view.topInventory.type == InventoryType.CRAFTING) stripGrid(player)
        later(player)
    }

    // ---- use -----------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(e: PlayerInteractEvent) {
        val id = MenuKitTag.entryId(e.item) ?: return
        e.isCancelled = true
        if (e.hand != EquipmentSlot.HAND || e.action == Action.PHYSICAL || !e.action.isRightClick) return
        press(e.player, id)
    }

    private fun press(player: Player, entryId: String) {
        val entry = kitFor(player).find(entryId)
        if (entry == null) {
            // Stale button (e.g. the viewer lost the permission it was gated on): rebuild the kit.
            later(player)
            return
        }
        press(player, entry)
    }

    private fun press(player: Player, entry: KitEntry) {
        if (!active(player)) {
            later(player)
            return
        }
        val now = System.currentTimeMillis()
        if (now - (lastPress[player.uniqueId] ?: 0L) < PRESS_COOLDOWN_MS) return
        lastPress[player.uniqueId] = now
        val mc = mcServer.findPlayer(PlayerId(player.uniqueId)) ?: return
        try {
            intents.dispatch(mc, entry.intent)
        } catch (t: Throwable) {
            log.warn("Kit intent '{}' failed for {}", entry.intent.key, player.name, t)
        }
    }

    // ---- locks ---------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(e: InventoryClickEvent) = handleClick(e, allowPress = true)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onCreativeClick(e: InventoryCreativeEvent) = handleClick(e, allowPress = false)

    private fun handleClick(e: InventoryClickEvent, allowPress: Boolean) {
        val player = e.whoClicked as? Player ?: return
        val top = e.view.topInventory
        val ownCrafting = top.type == InventoryType.CRAFTING
        val swapItem = when (e.click) {
            ClickType.NUMBER_KEY -> if (e.hotbarButton in 0..8) player.inventory.getItem(e.hotbarButton) else null
            ClickType.SWAP_OFFHAND -> player.inventory.itemInOffHand
            else -> null
        }
        val facts = KitClickFacts(
            clickedIsKit = MenuKitTag.isKit(e.currentItem),
            cursorIsKit = MenuKitTag.isKit(e.cursor),
            swapSourceIsKit = MenuKitTag.isKit(swapItem),
            inCraftingZone = ownCrafting && e.rawSlot in 0..4,
            buttonsShown = ownCrafting && gridShowsKit(top),
            collectToCursor = e.action == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR,
            topIsOwnCrafting = ownCrafting,
        )
        if (!LobbyKitLockRules.cancelClick(facts)) return
        e.isCancelled = true
        if (allowPress && LobbyKitLockRules.pressesButton(facts, e.isLeftClick || e.isRightClick)) {
            val id = MenuKitTag.entryId(e.currentItem) ?: return
            // Closing from inside the click handler is unsafe; do it next tick, then open the target.
            player.scheduler.execute(
                plugin,
                Runnable {
                    player.closeInventory()
                    press(player, id)
                }, null, 1L,
            )
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrag(e: InventoryDragEvent) {
        val top = e.view.topInventory
        val ownCrafting = top.type == InventoryType.CRAFTING
        val cancel = LobbyKitLockRules.cancelDrag(
            cursorIsKit = MenuKitTag.isKit(e.oldCursor) || MenuKitTag.isKit(e.cursor),
            buttonsShown = ownCrafting && gridShowsKit(top),
            touchesCraftingZone = ownCrafting && e.rawSlots.any { it in 0..4 },
            touchesKitSlot = e.rawSlots.any { MenuKitTag.isKit(e.view.getItem(it)) },
        )
        if (cancel) e.isCancelled = true
    }

    /** The result slot never produces a real craft while buttons are in the grid. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onCraft(e: CraftItemEvent) {
        if (e.inventory.matrix.any { MenuKitTag.isKit(it) } || MenuKitTag.isKit(e.inventory.result)) e.isCancelled = true
    }

    /** Vanilla recomputes the result on every matrix write; keep it the Play button while buttons are shown. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPrepareCraft(e: PrepareItemCraftEvent) {
        if (e.inventory.matrix.none { MenuKitTag.isKit(it) }) return
        val player = e.view.player as? Player ?: return
        val result = kitFor(player).entries(KitArea.CRAFTING_RESULT).firstOrNull()?.let { build(player, it) }
        e.inventory.result = result
    }

    /** Q on the kit item is never a drop; it fires the `key_drop` entry (ADR-0232). */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrop(e: PlayerDropItemEvent) {
        if (!MenuKitTag.isKit(e.itemDrop.itemStack)) return
        e.isCancelled = true
        kitFor(e.player).at(KitArea.KEY_DROP, 0)?.let { press(e.player, it) }
    }

    /**
     * F (swap hand) while not holding a weapon opens the menu, sneak+F the second key entry (ADR-0232).
     * Weapons never bind F, so this does not collide with skill triggers; with a weapon in hand F stays vanilla.
     * Sneak+F by an operator / `astera.admin.panel` holder is taken first by the admin hub (ADR-0233,
     * NORMAL priority, cancels the event), so `ignoreCancelled` leaves it alone here.
     * Nothing is intercepted outside the lobby kit (matches keep vanilla F).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwapHands(e: PlayerSwapHandItemsEvent) {
        val player = e.player
        if (active(player)) {
            val held = player.inventory.itemInMainHand
            val holdingWeapon = !held.isEmpty && !MenuKitTag.isKit(held) && isAsteraItem(held)
            val area = LobbyKeybinds.swapArea(holdingWeapon, player.isSneaking)
            val entry = area?.let { kitFor(player).at(it, 0) }
            if (entry != null) {
                e.isCancelled = true
                press(player, entry)
                if (!player.isSneaking) onMenuKey(PlayerId(player.uniqueId))
                return
            }
        }
        if (MenuKitTag.isKit(e.mainHandItem) || MenuKitTag.isKit(e.offHandItem)) e.isCancelled = true
    }

    private fun isAsteraItem(stack: ItemStack): Boolean =
        stack.hasItemMeta() && stack.itemMeta?.persistentDataContainer?.keys?.any { it.key == WEAPON_ID_KEY_NAME } == true

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlace(e: BlockPlaceEvent) {
        if (MenuKitTag.isKit(e.itemInHand)) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteractEntity(e: PlayerInteractEntityEvent) {
        if (MenuKitTag.isKit(e.player.inventory.getItem(e.hand))) e.isCancelled = true
    }

    private fun gridShowsKit(top: Inventory): Boolean = (0..4).any { MenuKitTag.isKit(top.getItem(it)) }

    private companion object {
        const val PRESS_COOLDOWN_MS = 200L
        const val HINT_DELAY_TICKS = 40L
        const val KEYS_HINT = "astera.lobby-kit.hint.keys"
        /** The items plugin stamps `<plugin>:item_id` on every skill item; matched by key name (as combat does). */
        const val WEAPON_ID_KEY_NAME = "item_id"
    }
}
