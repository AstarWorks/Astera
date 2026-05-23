package com.astarworks.astera.economy.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: estimate the marketplace value of every item a player
 * owns (Wave 5flex / ADR-0102 surface #4 — Power Index component).
 *
 * The port is intentionally vendor-neutral so the Economy plugin can
 * remain ADR-0022 isolated from Items. The default implementation is a
 * null-valuator (returns 0); the Items plugin overlays a real impl
 * (`MarketplacePricedInventoryValuator`) at priority 0 once Wave 5flex
 * Phase C wires it. The Items adapter reaches into
 * [com.astarworks.astera.items.application.port.outbound.IItemInstanceRepository]
 * and a per-item base-price registry.
 *
 * Sum semantics: every owned item / instance contributes its estimated
 * marketplace value (refinement layers move the needle). Returns 0 for
 * a player with no recorded items rather than throwing — a fresh
 * player should still get a snapshot (everything = 0).
 */
public interface IPlayerInventoryValuator {

    /**
     * Sum of estimated marketplace value (in the reference currency
     * configured by ops) for every item the player owns. Non-negative.
     */
    public fun estimatedEquipmentValue(playerId: PlayerId): Long

    /**
     * Sum of currency-equivalent value for items in the player's Wave 5e
     * PersonalVault. Returns 0 if the vault is empty / not present.
     */
    public fun estimatedVaultValue(playerId: PlayerId): Long
}

/**
 * Null-object implementation (Wave 5flex Phase 1 default). The Items
 * plugin overlays a real impl via the ServiceRegistry once both Items
 * and an item-pricing registry are loaded. Until then PowerIndex's
 * equipment + vault components stay zero — wallet + listings still
 * contribute via their own ports.
 */
public class NullInventoryValuator : IPlayerInventoryValuator {
    override fun estimatedEquipmentValue(playerId: PlayerId): Long = 0L
    override fun estimatedVaultValue(playerId: PlayerId): Long = 0L
}
