package com.astarworks.astera.core.application.port.outbound.guidepth

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.guidepth.GuiModalSpec
import com.astarworks.astera.core.domain.guidepth.ModalHandle
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: modal / popup / overlay display
 * (Wave 5gui-depth, ADR-0114).
 *
 * Wave 5gui-depth ships `InMemoryGuiModalGateway` (records the handle,
 * publishes start/dismiss events). Wave 6 lands `PaperGuiModalGateway` over
 * the anvil-prompt + boss-bar + custom overlay packet stack at higher
 * priority.
 *
 * Vendor-neutral by construction — no Bukkit `Inventory` / packet types in
 * the port; the adapter unwraps [GuiModalSpec] variants.
 *
 * # Concurrency & ownership
 *
 * A player may carry multiple active modals (e.g., a `Notification` toast on
 * top of a `Confirmation` prompt). The gateway tracks them in show-order;
 * [dismissAllForPlayer] dismisses them all atomically (one
 * `ModalDismissed(reasonKind = "bulk_dismiss")` per handle).
 */
public interface IGuiModalGateway {

    /**
     * Show a modal to a player. Returns an opaque handle the caller uses for
     * later dismissal. Fires
     * [com.astarworks.astera.core.domain.event.ModalShown].
     */
    public fun showModal(
        playerId: PlayerId,
        modal: GuiModalSpec,
    ): Result<ModalHandle, GuiModalError>

    /**
     * Dismiss the modal identified by [handle]. Idempotent: dismissing an
     * unknown or already-dismissed handle returns Success. Fires
     * [com.astarworks.astera.core.domain.event.ModalDismissed] with
     * `reasonKind = "explicit"` on the first successful dismiss.
     */
    public fun dismissModal(handle: ModalHandle): Result<Unit, GuiModalError>

    /**
     * Dismiss every active modal for [playerId] (typically on player quit or
     * scene change). Fires
     * [com.astarworks.astera.core.domain.event.ModalDismissed] for each
     * dismissed handle with `reasonKind = "bulk_dismiss"`. Idempotent.
     */
    public fun dismissAllForPlayer(playerId: PlayerId): Result<Unit, GuiModalError>

    /**
     * All currently-visible modal handles for [playerId], in show-order
     * (oldest first). Empty list if the player has no active modals or is
     * unknown.
     */
    public fun activeModals(playerId: PlayerId): List<ModalHandle>
}

public sealed class GuiModalError {
    public data class UnknownPlayer(public val playerId: PlayerId) : GuiModalError()
    public data class Platform(public val detail: String) : GuiModalError()
}
