package com.astarworks.astera.application.port.outbound

import com.astarworks.astera.domain.model.player.PlayerId

/**
 * Outbound port: resolve an i18n key into a MiniMessage-formatted string for
 * a given player (locale falls back to en when null or missing).
 */
interface IMessageRenderer {
    fun render(playerId: PlayerId?, key: String, placeholders: Map<String, String> = emptyMap()): String
    fun renderLore(playerId: PlayerId?, key: String, placeholders: Map<String, String> = emptyMap()): List<String>
}
