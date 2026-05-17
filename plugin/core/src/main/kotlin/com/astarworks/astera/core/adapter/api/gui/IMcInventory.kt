package com.astarworks.astera.core.adapter.api.gui

import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Vendor-neutral snapshot of an inventory window currently open for a player.
 *
 * Sits between [com.astarworks.astera.core.application.port.outbound.IGuiAdapter]
 * (which knows about [com.astarworks.astera.core.domain.gui.GuiSpec]) and the
 * platform's inventory primitive (Bukkit `Inventory`, web DOM, headless test
 * matrix …). The adapter performs `SlotPatch` application by talking to
 * [IMcInventory], never to Bukkit directly inside the diff loop.
 *
 * Per ADR-0002, this interface MUST NOT import `org.bukkit.*` /
 * `io.papermc.*` — Konsist enforces it (see
 * `core adapter-api MUST stay vendor-neutral` rule).
 *
 * ### Title mutation semantics
 *
 * Most host platforms (Paper 1.21 in particular) do not support live title
 * mutation on an already-open inventory. Implementations are free to no-op
 * on [setTitle] and let the calling adapter handle the close-then-reopen
 * dance — the method is part of the interface only so that platforms that
 * *do* support live updates (future Adventure title API, web DOM, etc.) can
 * implement it without breaking the contract.
 */
public interface IMcInventory {

    /** Total slot count (matches the owning [GuiSpec.size.slotCount]). */
    public val size: Int

    /** i18n key currently rendered as the inventory's title. */
    public val title: MessageKey

    /**
     * Read the currently-rendered template at [index], or `null` if the slot
     * is empty. The adapter uses this for self-tests and for diagnostic
     * logging; the diff loop itself only writes.
     */
    public fun getTemplate(index: SlotIndex): IMcItemTemplate?

    /**
     * Write [template] into [index]. Passing `null` clears the slot
     * (equivalent to [clear]).
     */
    public fun setTemplate(index: SlotIndex, template: IMcItemTemplate?)

    /** Clear the slot at [index]. Equivalent to `setTemplate(index, null)`. */
    public fun clear(index: SlotIndex)

    /**
     * Request a title change to [title]. Implementations may no-op on hosts
     * that cannot mutate the title of an already-open inventory; the calling
     * adapter is responsible for the close-then-reopen fallback in that case.
     */
    public fun setTitle(title: MessageKey)
}
