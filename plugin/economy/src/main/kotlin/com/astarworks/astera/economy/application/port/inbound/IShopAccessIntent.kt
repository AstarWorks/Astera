package com.astarworks.astera.economy.application.port.inbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.economy.application.port.outbound.VendorId

/**
 * Inbound intent: "this player wants to open a shop, here's the route they
 * arrived through."
 *
 * Wave 5g (Core hardening): every shop-open path — slash command, NPC
 * right-click, chat alias, external EC redirect — is collapsed to one of
 * these four sealed variants and routed through a single
 * [com.astarworks.astera.economy.application.usecase.shop.ShopAccessIntentRouter]
 * → [com.astarworks.astera.core.application.usecase.shop.ShopBrowseUseCase].
 *
 * Per ADR-0071: routing convergence is the *whole point* of the port. Adding
 * a new entry surface (e.g. QR code, in-game terminal block) means adding a
 * sealed variant here, not duplicating browse-open code in another plugin.
 */
public sealed interface IShopAccessIntent {
    public val playerId: PlayerId

    /** Player typed `/astera-economy shop open <shopId>`. */
    public data class OpenFromCommand(
        public override val playerId: PlayerId,
        public val shopId: ShopId,
    ) : IShopAccessIntent

    /** Player right-clicked a bound vendor entity. */
    public data class OpenFromNpc(
        public override val playerId: PlayerId,
        public val vendorId: VendorId,
    ) : IShopAccessIntent

    /** Player typed a chat alias resolved to a shop (Phase 4 chat router). */
    public data class OpenFromChat(
        public override val playerId: PlayerId,
        public val alias: String,
    ) : IShopAccessIntent

    /** External system (Phase 4 Web admin / Discord / Velocity) requested a shop open. */
    public data class OpenFromExternal(
        public override val playerId: PlayerId,
        public val externalRef: String,
    ) : IShopAccessIntent
}
