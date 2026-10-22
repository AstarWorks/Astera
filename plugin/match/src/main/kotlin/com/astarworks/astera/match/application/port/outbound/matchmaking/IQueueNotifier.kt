package com.astarworks.astera.match.application.port.outbound.matchmaking

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port (WP8): tell a queued / matched player something. The
 * platform wiring renders [key] through `IMessageRenderer` in the player's
 * locale and sends it as chat.
 */
public fun interface IQueueNotifier {
    public fun notify(playerId: PlayerId, key: MessageKey, placeholders: Map<String, String>)
}
