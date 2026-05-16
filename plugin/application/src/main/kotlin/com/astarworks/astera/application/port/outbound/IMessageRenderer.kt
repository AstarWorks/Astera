package com.astarworks.astera.application.port.outbound

import com.astarworks.astera.domain.model.i18n.MessageKey
import com.astarworks.astera.domain.model.player.PlayerId

/**
 * Outbound port: resolve an [MessageKey] into a MiniMessage-formatted string
 * for the given player (locale falls back to default when null or missing).
 *
 * `MessageKey` is used at the boundary so typos surface at compile time
 * instead of as missing-translation warnings at runtime.
 */
public interface IMessageRenderer {
    public fun render(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String> = emptyMap()): String
    public fun renderLore(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String> = emptyMap()): List<String>
}
