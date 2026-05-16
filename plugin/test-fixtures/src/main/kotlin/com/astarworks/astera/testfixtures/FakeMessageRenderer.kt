package com.astarworks.astera.testfixtures

import com.astarworks.astera.application.port.outbound.IMessageRenderer
import com.astarworks.astera.domain.model.player.PlayerId

/**
 * Trivial [IMessageRenderer] for use case tests.
 *
 * Returns the key unchanged (with placeholders appended in stable order when
 * present) so tests can assert that the *right key* was looked up without
 * pulling in a real language table.
 */
class FakeMessageRenderer : IMessageRenderer {
    val calls: MutableList<Call> = mutableListOf()

    override fun render(playerId: PlayerId?, key: String, placeholders: Map<String, String>): String {
        calls += Call(playerId, key, placeholders)
        return format(key, placeholders)
    }

    override fun renderLore(playerId: PlayerId?, key: String, placeholders: Map<String, String>): List<String> {
        calls += Call(playerId, key, placeholders)
        return listOf(format(key, placeholders))
    }

    private fun format(key: String, placeholders: Map<String, String>): String =
        if (placeholders.isEmpty()) key
        else "$key${placeholders.toSortedMap()}"

    data class Call(val playerId: PlayerId?, val key: String, val placeholders: Map<String, String>)
}
