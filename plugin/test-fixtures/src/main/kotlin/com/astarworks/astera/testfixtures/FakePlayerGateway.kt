package com.astarworks.astera.testfixtures

import com.astarworks.astera.application.port.outbound.IPlayerGateway
import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.weapon.WeaponSpec

/**
 * In-memory [IPlayerGateway] for use case tests.
 *
 * - [register] populates the name → id map consulted by [findByName].
 * - All [sendMessage] and [giveWeapon] calls are recorded on the public mutable
 *   lists so tests can assert on the exact invocations.
 */
class FakePlayerGateway : IPlayerGateway {
    val players: MutableMap<String, PlayerId> = mutableMapOf()
    val messages: MutableList<Pair<PlayerId, String>> = mutableListOf()
    val given: MutableList<Pair<PlayerId, WeaponSpec>> = mutableListOf()

    fun register(name: String, id: PlayerId) {
        players[name] = id
    }

    override fun findByName(name: String): PlayerId? = players[name]

    override fun sendMessage(playerId: PlayerId, rendered: String) {
        messages += playerId to rendered
    }

    override fun giveWeapon(playerId: PlayerId, weapon: WeaponSpec) {
        given += playerId to weapon
    }
}
