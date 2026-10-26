package com.astarworks.astera.core.adapter.paper.gui

import com.astarworks.astera.core.domain.gui.GuiId
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/**
 * Bukkit-required marker that identifies "our" inventories so the
 * [PaperGuiListener] can distinguish Astera-driven GUIs from any other plugin's
 * containers (chests, shulkers, third-party GUIs).
 *
 * Each opened [com.astarworks.astera.core.domain.gui.GuiSpec] gets a fresh
 * holder carrying its [guiId] — the listener never has to look up the spec by
 * UUID just to know "is this ours". Per ADR-0021 (Wave 4 R3 Declarative GUI),
 * holder identity is the single source of truth for "should we cancel this
 * click event".
 *
 * The Bukkit `InventoryHolder.getInventory()` contract requires returning the
 * bound `Inventory`, but `Bukkit.createInventory(holder, …)` takes the holder
 * *before* the inventory exists. We resolve the chicken-and-egg with the
 * standard lazy-set pattern: [PaperGuiAdapter] creates the inventory, then
 * writes back into [inventory] before any caller observes the holder.
 * Accessing [getInventory] before the back-reference is set is a programming
 * error and throws.
 *
 * Visibility is `internal` — no plugin outside `:plugin:core` has a reason to
 * construct or observe one.
 */
internal class PaperInventoryHolder(
    val guiId: GuiId,
) : InventoryHolder {

    /**
     * Back-reference set by [PaperGuiAdapter] immediately after
     * `Bukkit.createInventory(this, …)` returns. Stored under a non-property
     * name (`boundInventory`) to avoid a getter-signature clash with the
     * Java `InventoryHolder.getInventory(): Inventory` (non-null) override —
     * a Kotlin `var inventory: Inventory?` would auto-generate a getter that
     * returns `Inventory?` and refuse to override the platform method.
     *
     * Reading [getInventory] before the adapter has wired this field is a
     * programming error and throws.
     */
    @JvmField
    internal var boundInventory: Inventory? = null

    override fun getInventory(): Inventory =
        boundInventory ?: error(
            "PaperInventoryHolder($guiId).inventory accessed before PaperGuiAdapter " +
                "wired the back-reference — this is a programming error in the GUI adapter.",
        )
}
