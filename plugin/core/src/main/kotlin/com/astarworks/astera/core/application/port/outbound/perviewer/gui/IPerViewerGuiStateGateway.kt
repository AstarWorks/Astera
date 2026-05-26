package com.astarworks.astera.core.application.port.outbound.perviewer.gui

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.perviewer.GuiHandle
import com.astarworks.astera.core.domain.perviewer.PerViewerOverrideError
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port — per-viewer GUI override (Wave 5y-ext, ADR-0112).
 *
 * Where [com.astarworks.astera.core.application.port.outbound.IGuiAdapter]
 * opens a [com.astarworks.astera.core.domain.gui.GuiSpec] **once per viewer**
 * (the same spec rendered into the same Bukkit `Inventory` for everyone who
 * opens it), this gateway stages **per-viewer divergence** on top of that
 * rendering. Use cases:
 *
 *  - **Two players viewing the same shop see different stock counts** —
 *    the spec carries the layout; per-viewer overrides paint the live
 *    inventory deltas for the looking player.
 *  - **Spectator GUI overlay** — render an admin's spy panel with the
 *    target player's private data filled into one slot, without leaking
 *    that data to every other admin who opens the same panel.
 *  - **Loadout preview** — show the previewing player their loadout in
 *    the static-layout shop while every other viewer keeps seeing the
 *    shop's default preview slot.
 *
 * # Override semantics
 *
 * Two axes:
 *  - **Slot content** — [overrideSlotContent] swaps the rendered [GuiSlot]
 *    for one [SlotIndex] in the viewer's inventory. The original spec slot
 *    is restored when [clearOverridesForViewer] runs.
 *  - **Title** — [overrideTitle] swaps the inventory title for that viewer.
 *    Anvil-GUI (text input) titles cannot be re-set after open per Bukkit
 *    protocol; the Wave 6 Paper adapter handles that constraint at egress
 *    by re-opening with the new title.
 *
 * # Handle binding
 *
 * Every operation takes a [GuiHandle] minted by the platform-side
 * `IGuiAdapter.openFor(...)`. The handle's [GuiHandle.viewer] must equal
 * the [viewer] argument or the gateway returns [PerViewerOverrideError.UnknownTarget]
 * — applying viewer A's overrides through viewer B's handle would corrupt
 * both views.
 *
 * # Persistence
 *
 * **Session-scoped only.** Overrides are dropped on GUI close (the Wave 6
 * adapter is responsible for calling [clearOverridesForViewer] on close
 * — the gateway has no view-lifecycle awareness on its own), and on
 * `PlayerQuitServer` for every still-open handle.
 *
 * Implementations are NOT thread-safe.
 */
public interface IPerViewerGuiStateGateway {

    /**
     * Replace the slot at [slotIndex] with [content] for the viewer that
     * owns [handle].
     *
     * The replacement is *additive* over the spec — the spec's slot at
     * that index keeps rendering for every other viewer. Re-applying the
     * same content for the same slot is a no-op (no event published).
     */
    public fun overrideSlotContent(
        viewer: PlayerId,
        handle: GuiHandle,
        slotIndex: SlotIndex,
        content: GuiSlot,
    ): Result<Unit, PerViewerOverrideError>

    /**
     * Override the inventory title for the viewer that owns [handle]. The
     * Paper adapter resolves [title] through `IMessageRenderer` at packet
     * send time. Anvil GUIs require a re-open round-trip; the Wave 6
     * adapter handles that internally.
     */
    public fun overrideTitle(
        viewer: PlayerId,
        handle: GuiHandle,
        title: MessageKey,
    ): Result<Unit, PerViewerOverrideError>

    /**
     * Drop every staged slot + title override for the viewer that owns
     * [handle]. Idempotent. Returns the number of (slot + title)
     * overrides that were cleared (useful for tests / audit).
     *
     * Called by the Wave 6 Paper adapter when the GUI closes; called
     * automatically by the gateway's `PlayerQuitServer` subscriber for
     * every handle the player still owns.
     */
    public fun clearOverridesForViewer(
        viewer: PlayerId,
        handle: GuiHandle,
    ): Int

    /**
     * Clear every override across every GUI handle owned by [viewer].
     * Returns the number of handles that held at least one override.
     * Wired to `PlayerQuitServer`.
     */
    public fun clearAllOverridesForViewer(viewer: PlayerId): Int
}
