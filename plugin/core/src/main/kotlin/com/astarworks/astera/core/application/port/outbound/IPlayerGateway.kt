package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: vendor-neutral side-effecting operations on a player.
 *
 * Phrased in core domain terms only ([PlayerId]). Feature-specific delivery
 * (e.g. `giveWeapon(WeaponSpec)`) belongs in feature-plugin ports — the
 * Weapons plugin defines its own `IWeaponDelivery` outbound port and the
 * Paper adapter for it implements only that one method.
 *
 * The Minecraft adapter (`McPlayerGateway`) implements this in vendor-neutral
 * form by delegating to an [com.astarworks.astera.core.adapter.api.server.IMcServer];
 * the Paper concrete adapter then translates further into Bukkit API calls.
 *
 * ADR-0022 split: pre-Phase-2 this interface also carried
 * `giveWeapon(WeaponSpec)`. That coupling pulled the (Weapons-only)
 * `WeaponSpec` type into Core; the multi-plugin refactor (Phase 2) pushes
 * weapon delivery down to `:plugin:weapons` so Core stays weapon-agnostic.
 */
public interface IPlayerGateway {
    public fun findByName(name: String): PlayerId?
    public fun sendMessage(playerId: PlayerId, rendered: String)
}
