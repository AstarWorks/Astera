package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: vendor-neutral side-effecting operations on a player.
 *
 * Phrased in core domain terms only ([PlayerId]). Feature-specific delivery
 * (e.g. item-give, currency-grant) belongs in feature-plugin ports — the
 * Items plugin defines its own `IItemDelivery` outbound port and the Paper
 * adapter for it implements only that one method.
 *
 * The Minecraft adapter (`McPlayerGateway`) implements this in vendor-neutral
 * form by delegating to an [com.astarworks.astera.core.adapter.api.server.IMcServer];
 * the Paper concrete adapter then translates further into Bukkit API calls.
 *
 * ADR-0022 / ADR-0073 split: pre-Phase-2 this interface also carried item-
 * give methods. That coupling pulled item-domain types into Core; the
 * multi-plugin refactor (Phase 2) and Wave _items-1 push item delivery down
 * to `:plugin:items` so Core stays item-agnostic.
 */
public interface IPlayerGateway {
    public fun findByName(name: String): PlayerId?
    public fun sendMessage(playerId: PlayerId, rendered: String)
}
