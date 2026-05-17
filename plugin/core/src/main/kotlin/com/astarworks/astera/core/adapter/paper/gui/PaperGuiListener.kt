package com.astarworks.astera.core.adapter.paper.gui

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.adapter.paper.entity.PaperPlayer
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.SlotIndex
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.slf4j.LoggerFactory

/**
 * Bukkit-side bridge from inventory events to the declarative-GUI runtime
 * (ADR-0021, Wave 4 R3).
 *
 * Responsibilities, in order of liveness criticality:
 *
 * 1. **Cancel writes.** Astera GUIs are read-only from the player's
 *    perspective. Every [InventoryClickEvent] / [InventoryDragEvent]
 *    targeting one of our inventories is cancelled unconditionally so vanilla
 *    item movement can never mutate the displayed stacks. This is the single
 *    most important behaviour of this listener — if cancellation breaks, a
 *    player can steal display items.
 *
 * 2. **Dispatch intents.** When a player clicks a [GuiSlot.Static] whose
 *    `onClick` is non-null, the listener resolves the slot from the adapter's
 *    current [com.astarworks.astera.core.domain.gui.GuiSpec] and pushes the
 *    [com.astarworks.astera.core.domain.gui.GuiIntent] through
 *    [IGuiIntentRouter]. Clicks on `Border` / `Reactive` / `Empty` are
 *    silently absorbed.
 *
 * 3. **Forget session on close / quit.** When the player closes the GUI (real
 *    close, not adapter-driven reopen), the adapter's session map must
 *    forget them so subsequent `update()` calls become harmless no-ops.
 *    Reopen-driven CLOSE events are filtered out via
 *    [PaperGuiAdapter.reopening].
 *
 * The [server] dependency is currently unused but reserved for Wave 4 Phase
 * C wiring (looking up the IMcPlayer via PlayerId rather than wrapping
 * Bukkit Player directly, once `PaperServer` exposes that path uniformly).
 */
public class PaperGuiListener(
    private val adapter: PaperGuiAdapter,
    private val router: IGuiIntentRouter,
    @Suppress("unused") private val server: IMcServer,
) : Listener {

    @EventHandler
    public fun onClick(e: InventoryClickEvent) {
        val holder = e.inventory.holder as? PaperInventoryHolder ?: return

        // (1) Unconditional cancel — applies to BOTH top-inv clicks and any
        // shift-click / number-key swap originating from the bottom inv that
        // targets the top.
        e.isCancelled = true

        // (2) Bottom-inv clicks: the unconditional cancel above already covers
        // item movement (including [InventoryAction.MOVE_TO_OTHER_INVENTORY]
        // i.e. shift-click from the player's inventory to the GUI). No intent
        // dispatch from the player's own inventory.
        if (e.clickedInventory != e.view.topInventory) {
            return
        }

        // (3) Top-inv click: resolve the slot and dispatch its intent (if any).
        val whoClicked = e.whoClicked
        if (whoClicked !is Player) return
        val player: IMcPlayer = PaperPlayer(whoClicked)

        val spec = adapter.currentSpec(player) ?: run {
            // Session was forgotten between click and event delivery — race
            // between user-initiated close and a queued click. Drop silently.
            log.debug("Click in Astera holder={} but no current spec for player={}", holder.guiId, player.name)
            return
        }

        val rawSlot = SlotIndex(e.rawSlot.coerceAtLeast(0))
        val guiSlot = spec.slotAt(rawSlot)
        if (guiSlot is GuiSlot.Static && guiSlot.onClick != null) {
            router.dispatch(player, guiSlot.onClick)
        }
    }

    @EventHandler
    public fun onDrag(e: InventoryDragEvent) {
        if (e.inventory.holder is PaperInventoryHolder) {
            e.isCancelled = true
        }
    }

    @EventHandler
    public fun onClose(e: InventoryCloseEvent) {
        if (e.inventory.holder !is PaperInventoryHolder) return
        val uuid = e.player.uniqueId
        if (uuid in adapter.reopening) {
            // Adapter is mid-reopen (title change or open replacing an
            // existing session). The new open will (re-)populate the session
            // map; we must NOT clear it here.
            return
        }
        adapter.forgetSession(uuid)
    }

    @EventHandler
    public fun onQuit(e: PlayerQuitEvent) {
        // Player disconnected mid-GUI: forget the session so the next login
        // doesn't see a ghost session map entry. Idempotent if the player
        // wasn't viewing a GUI.
        adapter.forgetSession(e.player.uniqueId)
    }

    private companion object {
        val log = LoggerFactory.getLogger(PaperGuiListener::class.java)
    }
}
