package com.astarworks.astera.application.port.outbound

import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.weapon.WeaponSpec

/**
 * Outbound port: side-effecting operations on a player.
 *
 * Phrased in domain terms (`PlayerId`, `WeaponSpec`). The Minecraft adapter
 * implements this in vendor-neutral form; the Paper concrete adapter then
 * translates further into Bukkit API calls.
 */
interface IPlayerGateway {
    fun findByName(name: String): PlayerId?
    fun sendMessage(playerId: PlayerId, rendered: String)
    fun giveWeapon(playerId: PlayerId, weapon: WeaponSpec)
}
