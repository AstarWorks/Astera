package com.astarworks.astera.core.domain.inventory

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Failure surface for
 * [com.astarworks.astera.core.application.port.outbound.inventory.IBulkInventoryOps.issueKit]
 * (Wave 5world-events, ADR-0111).
 *
 * Sealed because the application API contract forbids raw JVM throwables.
 * Adapters (Match-plugin `PreFillKit` resolver, Phase 3 admin kit overlay)
 * map their failure modes onto these variants.
 */
public sealed class KitError {

    /** No kit registered under [kitId]. */
    public data class UnknownKit(public val kitId: KitId) : KitError()

    /** Target player is offline / no inventory handle. */
    public data class PlayerOffline(public val playerId: PlayerId) : KitError()

    /**
     * The underlying mutator returned an error while delivering one of
     * the kit items. Wraps the inner [InventoryError] so callers keep
     * visibility into the original cause.
     */
    public data class DeliveryFailed(public val cause: InventoryError) : KitError()
}
