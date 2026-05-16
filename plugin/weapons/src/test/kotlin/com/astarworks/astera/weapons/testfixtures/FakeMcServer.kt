package com.astarworks.astera.weapons.testfixtures

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IMcServer] for Weapons use case tests that need to bridge
 * `PlayerId → IMcPlayer` (Phase 3 GiveWeaponUseCase resolves the target
 * IMcPlayer through this port to hand to IWeaponDelivery).
 *
 * Tests use [register] to populate `(id, IMcPlayer)` pairs; `findPlayer`
 * and `findPlayerByName` return them. The shipped [FakeMcPlayer] is a
 * minimal IMcPlayer that records `sendMessage` / `giveItem` calls.
 */
public class FakeMcServer : IMcServer {
    private val byId: MutableMap<PlayerId, IMcPlayer> = mutableMapOf()

    public fun register(player: IMcPlayer) {
        byId[player.id] = player
    }

    override fun findPlayer(id: PlayerId): IMcPlayer? = byId[id]

    override fun findPlayerByName(name: String): IMcPlayer? =
        byId.values.firstOrNull { it.name == name }
}

/**
 * In-memory [IMcPlayer]. Records `sendMessage` / `giveItem` invocations so
 * tests can assert on what the use case did.
 */
public class FakeMcPlayer(
    override val id: PlayerId,
    override val name: String = "fake-player",
    override val location: Vec3 = Vec3(0.0, 64.0, 0.0),
    override val locale: String? = "en",
    public var heldMaterialKey: String? = null,
) : IMcPlayer {

    public data class GiveItemCall(
        val template: IMcItemTemplate,
        val displayNameOverride: String?,
        val loreOverride: List<String>,
    )

    public val messages: MutableList<String> = mutableListOf()
    public val given: MutableList<GiveItemCall> = mutableListOf()

    override fun sendMessage(rendered: String) {
        messages += rendered
    }

    override fun giveItem(template: IMcItemTemplate, displayNameOverride: String?, loreOverride: List<String>) {
        given += GiveItemCall(template, displayNameOverride, loreOverride)
    }

    override fun heldItemMaterialKey(): String? = heldMaterialKey
}
