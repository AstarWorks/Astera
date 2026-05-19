package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IPlayerGateway] for use case tests.
 *
 * - [register] populates the name → id map consulted by [findByName].
 * - All [sendMessage] calls are recorded on [messages] so tests can assert
 *   on the exact invocations.
 *
 * ADR-0022 / ADR-0073 trim: pre-Phase-2 this fake also implemented item-give
 * methods. That capability moved to the Items plugin's outbound port; item-
 * delivery tests should use a `FakeItemDelivery` fixture from the Items
 * plugin's own test-fixtures.
 */
public class FakePlayerGateway : IPlayerGateway {
    public val players: MutableMap<String, PlayerId> = mutableMapOf()
    public val messages: MutableList<Pair<PlayerId, String>> = mutableListOf()

    public fun register(name: String, id: PlayerId) {
        players[name] = id
    }

    override fun findByName(name: String): PlayerId? = players[name]

    override fun sendMessage(playerId: PlayerId, rendered: String) {
        messages += playerId to rendered
    }
}
