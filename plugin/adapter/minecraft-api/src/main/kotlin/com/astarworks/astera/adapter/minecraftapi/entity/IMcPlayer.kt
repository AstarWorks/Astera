package com.astarworks.astera.adapter.minecraftapi.entity

import com.astarworks.astera.domain.model.geometry.Vec3
import com.astarworks.astera.domain.model.player.PlayerId

/**
 * Vendor-neutral Minecraft player handle.
 *
 * Implementations live in `adapter-minecraft-impl-*` (Paper, Folia, Spigot,
 * Velocity, ...). The application layer never sees these implementations
 * directly — only the outbound ports defined in `application/port/outbound`.
 *
 * Strings passed to [sendMessage] are already MiniMessage-formatted; the impl
 * is responsible for deserializing to its native Component / chat type.
 *
 * [giveMaterial] takes a vendor-neutral material reference. Paper resolves
 * via `Material.matchMaterial`; Oraxen provider resolves via the Oraxen
 * registry; etc.
 */
interface IMcPlayer {
    val id: PlayerId
    val name: String
    val location: Vec3
    val locale: String?

    fun sendMessage(rendered: String)
    fun giveMaterial(materialKey: String, displayName: String?, lore: List<String>)
}
